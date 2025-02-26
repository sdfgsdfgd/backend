package net.sdfgsdfg

import SimpleReverseProxy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import net.sdfgsdfg.z.archive.configureRouting
import net.sdfgsdfg.z.archive.configureTemplating

// TODO:    distZip / installDist  within  .run  that triggers a webhook/ endpoint on our server to redeploy itself
//          via sh  ,   taking care of also    netw/file/ logs / IPC / process-kill, confirm existing server killed,   mgmt
fun main() {
    // TODO: db placeholder

    embeddedServer(Netty, port = 80, module = Application::module)  // , host = "0.0.0.0"
        .start(wait = true)
}

fun Application.module() {
    // 1) Configs
    cfg()                       // xx Auth Routes

    // 2)   Netty  |   CIO   |  OkHttp
    val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 7500
            connectTimeoutMillis = 5000
        }
    }
    // 3) release ze proxy kraken --> pointing to Next.js  at port 3000
    val nextProxy = SimpleReverseProxy(httpClient, Url("http://localhost:3000"))

    // 4) Routes
    routing {
        get("/test") {
            call.respondText(" 🥰  [ OK ]")
        }

        // WEBSOCKET todo: gRPC cfg for best simultaneous audio stream with together with textstream
        webSocket("/ws") {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    // xx INPUT
                    val text = frame.readText()

                    // xx OUTPUT
                    outgoing.send(Frame.Text("<agent resp> ....... $text"))

                    if (text.equals("bye", ignoreCase = true)) {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                    }
                }
            }
        }

        // Sail away to Next.js  ( Sail away, sail away, sail away ... )
        route("/{...}") {
            handle {
                nextProxy.proxy(call)
            }
        }
    }
}

// Disabled / Archive
@Suppress("unused")
fun Application.modules_disabled() {
    configureMonitoring()       // xx Metrics
    configureSerialization()    // gson-ktor examples ?
    configureRouting() // low priority, static page stuff
    configureTemplating() // low priority, static page stuff
}
