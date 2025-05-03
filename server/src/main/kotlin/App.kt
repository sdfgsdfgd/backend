package net.sdfgsdfg

import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.application.log
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
        port = 80,
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