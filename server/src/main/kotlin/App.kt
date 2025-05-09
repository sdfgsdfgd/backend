package net.sdfgsdfg

import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import proxy.SimpleReverseProxy
import kotlin.time.Duration.Companion.seconds

fun main() {
    // TODO: DB ( Exposed - best for Ktor - NextJS duo )
    embeddedServer(
        Netty,
//        port = 80,
        configure = {                     // NettyApplicationEngine.Configuration
            connectors.add(EngineConnectorBuilder().apply {
                host = "0.0.0.0"    // xx Interesting easter egg. 0.0.0.0 (the wildcard) tries an IPv6 dual-stack socket on [::]:80, which OSX ipv6 low-port guard is off for user acc
                port = 80           //  JVM first opens IPv6 dual-stack socket [::]:80  - - - macOS default: IPv6 low-port guard OFF (ip6.lowportreserved = 0) since 2018
            })                      //  1. OSX -->      so DeploY , on OSX, for debugging, works ! Without requiring privileges. But 127.0.0.1 defaults to IPv4  &  blows up
            tcpKeepAlive = true     //  2. Linux -->    keeps both guards on  ( so we end up setting root on systemd to allow low-ports )
            responseWriteTimeoutSeconds = 0   // disable idle-write timeout
        },
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    cfg()

    // 1. Routes
    routing {
        // for quick up-checks
        get("/test") {
            call.respondText(" 🥰  [ OK ]")
        }

        // [ Webhooks ]
        githubWebhookRoute()

        // [ WS ]
        ws()

        // [ gRPC ]
        grpc()

        // [ Reverse Proxy ] -->  Next.js @ :3000
        route("/{...}") {
            handle {
                SimpleReverseProxy(httpClient, Url("http://localhost:3000")).proxy(call)
            }
        }
    }

    // 3. ✨ [ Websocket Monitor ] ✨ //
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