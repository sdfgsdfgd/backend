@file:OptIn(DelicateCoroutinesApi::class)

package net.sdfgsdfg

import SimpleReverseProxy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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

        githubWebhookRoute()

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

fun Route.githubWebhookRoute() {
    // Minimal GitHub webhook route
    post("/webhook/github") {
        val payload = call.receiveText()
        println("GitHub payload: $payload")

        // 1) Respond right away, so GitHub doesn't time out
        call.respondText(text = "Deployment triggered! We'll do it asynchronously.", status = HttpStatusCode(202, "Accepted"))


        // 2) In the background, do the deploy
        GlobalScope.launch {
            // Or GlobalScope.launch if you prefer
//            "./0_scripts/deploy.main.kts deploy".shell()
//
            "systemctl restart backend.service".shell()
        }
    }
}

// region Disabled / Archive
//
// 1)
//@Suppress("unused")
//fun Application.modules_disabled() {
//    configureMonitoring()       // xx Metrics
//    configureSerialization()    // gson-ktor examples ?
//    configureRouting() // low priority, static page stuff
//    configureTemplating() // low priority, static page stuff
//}
//
//
// 2)
///**
// * Verifies X-Hub-Signature-256. Compare computed HMAC (sha256) of [body] with [signatureHeader].
// */
//private fun verifyGitHubSignature(signatureHeader: String?, secret: String, body: ByteArray): Boolean {
//    if (signatureHeader.isNullOrBlank()) return false
//    // Usually the header is in format: "sha256=..."
//    val expectedPrefix = "sha256="
//    if (!signatureHeader.startsWith(expectedPrefix)) return false
//
//    val signature = signatureHeader.removePrefix(expectedPrefix)
//
//    // Calculate HMAC-SHA256 on the body using 'secret'
//    val hmacSha256 = Mac.getInstance("HmacSHA256").apply {
//        init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
//    }
//    val computed = hmacSha256.doFinal(body).toHexString()
//
//    return MessageDigest.isEqual(signature.toByteArray(), computed.toByteArray())
//}
//
///** Handy extension to convert ByteArray -> Hex String */
//private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
//
//
// 3) for github webhooks etc...  todo  GITHUB_SECRETS impl     (security yay)
//    post("/webhook/github") {
//        // 1) Read the raw body
//        val bodyBytes = call.receiveStream().readBytes()
//
//        // 2) Optional: verify signature if you have a webhook secret
//        val secret = System.getenv("GITHUB_WEBHOOK_SECRET") ?: ""
//        if (secret.isNotBlank()) {
//            val signature = call.request.headers["X-Hub-Signature-256"]
//            if (!verifyGitHubSignature(signature, secret, bodyBytes)) {
//                call.respond("Invalid signature", typeInfo = TypeInfo(String::class))
//                return@post
//            }
//        }
//
//        // 3) Optionally parse the JSON to see which branch was pushed, etc.
//        //    e.g. val payload = Json.decodeFromString<PushPayload>(bodyBytes.decodeToString())
//
//        // 4) Run your `deploy.main.kts` script in the background so it doesn’t block Ktor
//        val output = withContext(Dispatchers.IO) {
//            // This example calls your script in the same directory or adjust path as needed:
//            runCommand("./0_scripts/deploy.main.kts deploy")
//        }
//
//        // 5) Respond with whatever you like
//        call.respondText("Deployment triggered.\n\n$output")
//    }
// endregion