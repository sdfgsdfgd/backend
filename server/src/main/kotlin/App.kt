package net.sdfgsdfg

import io.ktor.http.Url
import io.ktor.http.HttpStatusCode
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
import java.net.InetAddress
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

    get("/admin/ip/blacklist") {
        val requesterIp = resolveGrafanaClientIp(call)
        if (!isTrustedGrafanaIp(requesterIp)) {
            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return@get
        }
        val ip = call.request.queryParameters["ip"]?.trim()
        if (ip.isNullOrBlank()) {
            call.respondText("Missing ip", status = HttpStatusCode.BadRequest)
            return@get
        }
        val reason = call.request.queryParameters["reason"]?.trim()?.take(200)
        val country = call.request.queryParameters["country"]?.trim()?.take(2)?.uppercase()
        RequestEvents.blacklist(ip, reason ?: "manual", country, async = false)
        call.respondText("OK")
    }

    get("/admin/ip/allowlist") {
        val requesterIp = resolveGrafanaClientIp(call)
        if (!isTrustedGrafanaIp(requesterIp)) {
            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return@get
        }
        val ip = call.request.queryParameters["ip"]?.trim()
        if (ip.isNullOrBlank()) {
            call.respondText("Missing ip", status = HttpStatusCode.BadRequest)
            return@get
        }
        val note = call.request.queryParameters["note"]?.trim()?.take(200)
        RequestEvents.allowlist(ip, note, async = false)
        call.respondText("OK")
    }

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

        val serverCall = call
        val clientSession = this
        val trusted = isTrustedGrafanaIp(remoteIp)
        wsClient.webSocket(urlString = targetUrl, request = {
            headers {
                listOf("Cookie", "Authorization", "Origin", "User-Agent").forEach { header ->
                    call.request.headers[header]?.let { value ->
                        append(header, value)
                    }
                }
                call.request.headers["Sec-WebSocket-Protocol"]?.let { value ->
                    append("Sec-WebSocket-Protocol", value)
                }
                append("Host", call.request.host())
                append("X-Forwarded-Host", call.request.host())
                append("X-Forwarded-Proto", if (call.request.origin.scheme == "https") "https" else "http")
                append("X-Forwarded-For", remoteIp)
                append("X-Real-IP", remoteIp)
                if (trusted) {
                    append("X-WEBAUTH-USER", "x")
                }
            }
        }) {
            serverCall.application.log.info("Grafana WS connected: remote={} trusted={}", remoteIp, trusted)
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
@Volatile private var grafanaIpv4Cache: String? = null
@Volatile private var grafanaIpv6Cache: String? = null
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
    val (ipv4, ipv6) = currentGrafanaPublicIps()
    return if (ip.contains(":")) {
        ipv6 != null && sameGrafanaIpv6Prefix64(ip, ipv6)
    } else {
        ipv4 != null && ip == ipv4
    }
}

private fun currentGrafanaPublicIps(): Pair<String?, String?> {
    val now = System.currentTimeMillis()
    if (now - grafanaIpCacheAtMs < grafanaIpCacheTtlMs) {
        return grafanaIpv4Cache to grafanaIpv6Cache
    }
    val raw = runCatching { Files.readString(grafanaPublicIpFile) }.getOrNull().orEmpty()
    var v4: String? = null
    var v6: String? = null
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { line ->
            when {
                line.startsWith("ipv4=") -> v4 = line.removePrefix("ipv4=")
                line.startsWith("ipv6=") -> v6 = line.removePrefix("ipv6=")
                line.contains(":") -> v6 = line
                line.count { it == '.' } == 3 -> v4 = line
            }
        }
    if (v4 != null || v6 != null) {
        grafanaIpv4Cache = v4
        grafanaIpv6Cache = v6
        grafanaIpCacheAtMs = now
    }
    return v4 to v6
}

private fun sameGrafanaIpv6Prefix64(left: String, right: String): Boolean {
    val leftAddr = runCatching { InetAddress.getByName(left) }.getOrNull()
    val rightAddr = runCatching { InetAddress.getByName(right) }.getOrNull()
    val leftBytes = leftAddr?.address ?: return false
    val rightBytes = rightAddr?.address ?: return false
    if (leftBytes.size != 16 || rightBytes.size != 16) return false
    for (i in 0 until 8) {
        if (leftBytes[i] != rightBytes[i]) return false
    }
    return true
}
