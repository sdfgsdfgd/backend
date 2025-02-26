package net.sdfgsdfg

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText

// TODO:    distZip / installDist  within  .run  that triggers a webhook/ endpoint on our server to redeploy itself
//          via sh  ,   taking care of also    netw/file/ logs / IPC / process-kill, confirm existing server killed,   mgmt
fun main() {
    // TODO: db placeholder

    embeddedServer(Netty, port = 80, module = Application::module)  // , host = "0.0.0.0"
        .start(wait = true)
}

fun Application.module() {
    cfg()                       // xx Auth Routes
    configureMonitoring()       // xx Metrics
    configureSerialization()    // gson-ktor examples ?

    routing {
        webSocket("/ws") { // websocketSession
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    outgoing.send(Frame.Text("YOU SAID: $text"))
                    if (text.equals("bye", ignoreCase = true)) {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                    }
                }
            }
        }
    }

//    configureHTTP() // https
//    configureRouting() // low priority, static page stuff
//    configureTemplating() // low priority, static page stuff
}
