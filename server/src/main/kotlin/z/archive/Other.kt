package net.sdfgsdfg.z.archive

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.sse.SSE

fun Application.configureRouting() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
    install(SSE)
    install(DoubleReceive)
//    routing {
//        get("/") {
//            call.respondText("Hello World!")
//        }
//        // Static plugin. Try to access `/static/index.html`
//        staticResources("/static", "static")
//        sse("/hello") {
//            send(ServerSentEvent("world"))
//        }
//        post("/double-receive") {
//            val first = call.receiveText()
//            val theSame = call.receiveText()
//            call.respondText(first + " " + theSame)
//        }
//    }
}


// TODO: Ktor template gen left this here, find out wtf this is,
//object TlsRawSocket {
//    @JvmStatic
//    fun main(args: Array<String>) {
//        runBlocking {
//            val selectorManager = ActorSelectorManager(Dispatchers.IO)
//            val socket = aSocket(selectorManager).tcp().connect("www.google.com", port = 443).tls(coroutineContext = coroutineContext)
//            val write = socket.openWriteChannel()
//            val EOL = "\r\n"
//            write.writeStringUtf8("GET / HTTP/1.1${EOL}Host: www.google.com${EOL}Connection: close${EOL}${EOL}")
//            write.flush()
//            println(socket.openReadChannel().readRemaining().readBytes().toString(Charsets.UTF_8))
//        }
//    }
//}
