package net.sdfgsdfg.z.archive

import java.io.File
import java.time.LocalDateTime

//fun Application.configureRouting() {
//    install(StatusPages) {
//        exception<Throwable> { call, cause ->
//            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
//        }
//    }
//    install(SSE)
//    install(DoubleReceive)
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
//}
// TODO: Ktor template gen left this here, find out wtf this is
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

// TODO: Quest: Can't we reuse 1 symdef in kts and codebase ?
//fun String.shell(logFile: File): String = runCatching {
//    val process = ProcessBuilder("bash", "-c", this).apply {
//        redirectErrorStream(true)
//    }.start()
//
//    val reader = process.inputStream.bufferedReader()
//    val outputBuffer = StringBuilder()
//
//    reader.useLines { lines ->
//        lines.forEach { line ->
//            logFile.appendText("🔸 $line\n")  // Log to file
//        }
//    }
//
//    val exitCode = process.waitFor()
//    logFile.appendText("🔎 Command: $this | Exit code: $exitCode\n")
//
//    return outputBuffer.toString()
//}.getOrElse {
//    logFile.appendText("❌ ERROR running '$this': ${it.localizedMessage}\n")
//    return ""
//}

fun logToFile(logFile: File, message: String) {
    logFile.appendText("${LocalDateTime.now()} | $message\n")
}