package net.sdfgsdfg

import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.server.request.host
import io.ktor.server.request.uri
import io.ktor.server.plugins.origin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import proxy.SimpleReverseProxy
import proxy.HostRouter
import proxy.HostRule
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.request.headers
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import java.nio.file.Files
import java.nio.file.Paths

fun main() {
    // TODO: DB ( Exposed - best for Ktor - NextJS duo )
    //  - db & analytics --> https://chatgpt.com/c/68abba53-9be4-832f-a171-88519b652f44    -- Ktor + Exposed + Micrometer/Prometheus
    embeddedServer(
        factory = Netty,
        configure = {
            connectors.add(EngineConnectorBuilder().apply {
                host = "0.0.0.0"  // xx Interesting easter egg. 0.0.0.0 (the wildcard) tries an IPv6 dual-stack socket on [::]:80, which OSX ipv6 low-port guard is off for user acc
                port = 80         //  JVM first opens IPv6 dual-stack socket [::]:80  - - - macOS default: IPv6 low-port guard OFF (ip6.lowportreserved = 0) since 2018
            })                    //  1. OSX -->      so DeploY , on OSX, for debugging, works ! Without requiring privileges. But 127.0.0.1 defaults to IPv4  &  blows up
            tcpKeepAlive = true   //  2. Linux -->    keeps both guards on  ( so we end up setting root on systemd to allow low-ports )
            responseWriteTimeoutSeconds = 0   // disable idle-write timeout
        },
        module = Application::module
    ).start(wait = true)
}

// ---[ ModuleS ]----- //
fun Application.module() {
    cfg()
    db()
    routes()
    analytics()
}

// ------------[ Routes ]--------------
private fun Application.routes() = routing {
    // Host-aware reverse proxy targets. Add more domains by extending this list.
    val hostRouter = HostRouter(
        httpClient = httpClient,
        rules = listOf(
            HostRule(
                hosts = setOf("x.sdfgsdfg.net"),
                target = Url("http://127.0.0.1:3300"),
                name = "grafana"
            ),
            HostRule(
                hosts = setOf("leospecial.com", "www.leospecial.com"),
                target = Url("http://localhost:3001")
            ),
            HostRule(
                hosts = setOf("sdfgsdfg.net", "www.sdfgsdfg.net", "localhost", "127.0.0.1"),
                target = Url("http://localhost:3000")
            )
        ),
        defaultTarget = Url("http://localhost:3000")
    )

    get("/test") { call.respondText(" 🥰  [ OK ]") }

    // [ WS ]
    ws()

    // [ gRPC ]
    grpc()

    // [ Webhooks ]
    githubWebhookRoute()

    // [ Reverse Proxy ] -->  Next.js @ :3000
    webSocket("/api/live/ws") {
        if (call.request.host().lowercase() != "x.sdfgsdfg.net") {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "host not allowed"))
            return@webSocket
        }

        val requestUri = call.request.uri
        val targetUrl = "ws://127.0.0.1:3300$requestUri"
        val remoteIp = resolveGrafanaClientIp(call)

        val clientSession = this
        wsClient.webSocket(urlString = targetUrl, request = {
            headers {
                listOf("Cookie", "Authorization", "Origin", "User-Agent").forEach { header ->
                    call.request.headers[header]?.let { value ->
                        append(header, value)
                    }
                }
                if (isTrustedGrafanaIp(remoteIp)) {
                    append("X-WEBAUTH-USER", "x")
                }
            }
        }) {
            val upstream = this

            val toUpstream = launch {
                for (frame in clientSession.incoming) {
                    upstream.send(frame)
                }
            }
            val toClient = launch {
                for (frame in upstream.incoming) {
                    clientSession.send(frame)
                }
            }

            try {
                joinAll(toUpstream, toClient)
            } finally {
                toUpstream.cancelAndJoin()
                toClient.cancelAndJoin()
            }
        }
    }

    route("/{...}") {
        handle {
            hostRouter.proxy(call)
        }
    }
}

//    ✨    [   ANALYTICS  -  Websocket Monitor etc...   ]     ✨
private fun Application.analytics() {
    launch(
        context = CoroutineScope(Dispatchers.IO + SupervisorJob()).coroutineContext,
        start = CoroutineStart.UNDISPATCHED
    ) {
        while (isActive) {
            runCatching { ConnectionCounter.count() }
                .onFailure { log.error("Error in WS monitor:", it) }
                .getOrNull()
                ?.takeIf { it > 0 }
                ?.let { count -> log.info("[WS] Currently $count active connection(s).") }
            delay(30.seconds)
        }
    }
}

private fun Application.db() {
    RequestEvents.init()
}

private val grafanaPublicIpFile = Paths.get("/home/x/Desktop/SCRIPTS/public_ip.txt")
private const val grafanaIpCacheTtlMs = 30_000L
@Volatile private var grafanaIpCache: String? = null
@Volatile private var grafanaIpCacheAtMs: Long = 0

private fun resolveGrafanaClientIp(call: ApplicationCall): String {
    val cf = call.request.headers["CF-Connecting-IP"]
    if (!cf.isNullOrBlank()) return cf
    val forwarded = call.request.headers["X-Forwarded-For"]
        ?.split(',')
        ?.firstOrNull()
        ?.trim()
    if (!forwarded.isNullOrBlank()) return forwarded
    return call.request.origin.remoteHost
}

private fun isTrustedGrafanaIp(ip: String): Boolean {
    if (ip == "127.0.0.1" || ip == "::1") return true
    if (ip.startsWith("192.168.1.")) return true
    val publicIp = currentGrafanaPublicIp()
    return publicIp != null && ip == publicIp
}

private fun currentGrafanaPublicIp(): String? {
    val now = System.currentTimeMillis()
    val cached = grafanaIpCache
    if (cached != null && now - grafanaIpCacheAtMs < grafanaIpCacheTtlMs) return cached
    val ip = runCatching { Files.readString(grafanaPublicIpFile).trim() }.getOrNull()
        ?.takeIf { it.isNotBlank() }
    if (ip != null) {
        grafanaIpCache = ip
        grafanaIpCacheAtMs = now
    }
    return ip
}
