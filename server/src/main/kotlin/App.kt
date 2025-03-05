@file:OptIn(DelicateCoroutinesApi::class)

package net.sdfgsdfg

import SimpleReverseProxy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

fun main() {
    // TODO: db placeholder

    embeddedServer(Netty, port = 80, module = Application::module)  // , host = "0.0.0.0"
        .start(wait = true)
}

fun Application.module() {
    // 1) Configs
    cfg()                       // xx Auth Routes
    configureSerialization()

    // 2)   Netty  |   CIO   |  OkHttp
    val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 7500
            connectTimeoutMillis = 5000
        }
    }
    // 3) release ze proxy kraken --> pointing to Next.js  at port 3000
    val nextProxy = SimpleReverseProxy(httpClient, Url("http://localhost:3000"))

    // xx BG Job: Periodically monitor active conns  that logs connection count every 30s (only if > 0)
    launch {
        while (isActive) {
            delay(30_000)
            val count = ConnectionsManager.activeConnectionsCount()
            if (count > 0) log.info("[WS] Currently $count active WebSocket connection(s).")
        }
    }

    // 4) Routes
    routing {
        get("/test") {
            call.respondText(" 🥰  [ OK ]")
        }

        githubWebhookRoute()

        // WEBSOCKET todo: gRPC cfg for best simultaneous audio stream with together with textstream
        webSocket("/ws") {
            // TODO:   also send a Queue Number - losers have to wait after 10, we are low on resources. Consume appropriately.
            // Limit to 10 concurrent connections
            if (ConnectionsManager.activeConnectionsCount() >= 10) {
                // If 10 or more connections are already active, close connection immediately
                application.log.warn("<---------- !!!  Rejecting new WS connection because we are at capacity (10)  !!! ---------->")
                close(
                    CloseReason(
                        code = CloseReason.Codes.TRY_AGAIN_LATER,
                        message = "Server has reached maximum connections."
                    )
                )
                return@webSocket
            }

            // Register new connection
            val connection = ConnectionsManager.register(this)

            try {
                application.log.info("[WS] Client ${connection.id} connected. Active: ${ConnectionsManager.activeConnectionsCount()}")

                // Listen for messages until the client disconnects
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val incomingText = frame.readText().trim()
                        application.log.info("[WS-${connection.id}] Received: $incomingText")

                        // Convert JSON -> sealed WsMessage
                        val msg = parseWsMessage(incomingText)
                        when (msg) {
                            is WsMessage.Ping -> {
                                // Echo back a Pong, preserving the clientTimestamp
                                val pong = WsMessage.Pong(
                                    clientTimestamp = msg.clientTimestamp,
                                    serverTimestamp = System.currentTimeMillis()
                                )
                                // Optional debug log:
                                application.log.info("[WS-${connection.id}] Sending Pong: ${pong.toJson()}")

                                connection.session.sendAsJson(pong)
                            }

                            is WsMessage.Pong -> {
                                // Typically your client won't send Pong,
                                // but if it does, handle or ignore it.
                                application.log.info("[WS-${connection.id}] Received an unexpected Pong.")
                            }

                            is WsMessage.Echo -> {
                                // Echo back the same content
                                connection.session.sendAsJson(msg)
                            }

                            is WsMessage.Bye -> {
                                close(CloseReason(CloseReason.Codes.NORMAL, "Client says Bye"))
                            }

                            is WsMessage.Unknown -> {
                                application.log.warn(
                                    "[WS-${connection.id}] Received unknown message. " +
                                    "Raw: $incomingText   Decoded: ${msg}"
                                )
                            }
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                application.log.info("[WS-${connection.id}] Closed by client.")
            } catch (t: Throwable) {
                application.log.error("[WS-${connection.id}] Error: ${t.message}", t)
            } finally {
                // Clean up
                ConnectionsManager.unregister(connection)
                application.log.info("[WS-${connection.id}] Disconnected. Active: ${ConnectionsManager.activeConnectionsCount()}")
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

/**
 * Thread-safe set of active connections.
 * We also demonstrate an ID + session wrapper for logging convenience.
 */
object ConnectionsManager {
    private val connections = ConcurrentHashMap.newKeySet<Connection>()

    fun register(session: DefaultWebSocketServerSession): Connection {
        val connection = Connection(
            id = "client-${System.currentTimeMillis()}",
            session = session
        )
        connections.add(connection)
        return connection
    }

    fun unregister(connection: Connection) {
        connections.remove(connection)
    }

    fun activeConnectionsCount(): Int = connections.size
}

data class Connection(
    val id: String,
    val session: DefaultWebSocketServerSession
)

/**
 * Send a WsMessage to this WebSocket session as JSON text frame.
 */
suspend fun DefaultWebSocketServerSession.sendAsJson(message: WsMessage) {
    send(Frame.Text(message.toJson()))
}

/**
 * Shared JSON instance (kotlinx.serialization).
 * We configure it to allow unknown keys and always encode defaults
 * so that "type" is always output for each message.
 */
private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Our sealed class of possible WebSocket message types.
 * We explicitly store "type" in each data class so that the JSON
 * includes it automatically.
 */
sealed class WsMessage {
    abstract val type: String

    @Serializable
    data class Ping(
        override val type: String = "ping",
        val clientTimestamp: Long? = null
    ) : WsMessage()

    @Serializable
    data class Pong(
        override val type: String = "pong",
        val clientTimestamp: Long? = null,
        val serverTimestamp: Long? = null
    ) : WsMessage()

    @Serializable
    data class Echo(
        override val type: String = "echo",
        val content: String
    ) : WsMessage()

    // Because Bye/Unknown have no extra data, we can store type
    // in the constructor or do a small "object" with a custom serializer.
    // For simplicity, let's do them as data classes with default props:
    @Serializable
    data class Bye(
        override val type: String = "bye"
    ) : WsMessage()

    @Serializable
    data class Unknown(
        override val type: String = "unknown"
    ) : WsMessage()
}

/**
 * Converts a WsMessage into a JSON string.
 * Now includes "type" automatically.
 */
fun WsMessage.toJson(): String = when (this) {
    is WsMessage.Ping -> json.encodeToString(WsMessage.Ping.serializer(), this)
    is WsMessage.Pong -> json.encodeToString(WsMessage.Pong.serializer(), this)
    is WsMessage.Echo -> json.encodeToString(WsMessage.Echo.serializer(), this)
    is WsMessage.Bye -> json.encodeToString(WsMessage.Bye.serializer(), this)
    is WsMessage.Unknown -> json.encodeToString(WsMessage.Unknown.serializer(), this)
}

/**
 * Parses an incoming JSON string and picks the correct WsMessage variant
 * based on the "type" field. Now that our data classes have a `type` property
 * by default, we can rely on normal decode calls.
 */
fun parseWsMessage(raw: String): WsMessage {
    return try {
        // Peek at the "type" field
        val elem = json.parseToJsonElement(raw).jsonObject
        val typeVal = elem["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""

        return when (typeVal) {
            "ping" -> json.decodeFromString(WsMessage.Ping.serializer(), raw)
            "pong" -> json.decodeFromString(WsMessage.Pong.serializer(), raw)
            "echo" -> json.decodeFromString(WsMessage.Echo.serializer(), raw)
            "bye" -> json.decodeFromString(WsMessage.Bye.serializer(), raw)
            else -> json.decodeFromString(WsMessage.Unknown.serializer(), raw)
        }
    } catch (e: Exception) {
        // If it fails (invalid JSON, etc.), fallback to Unknown
        WsMessage.Unknown()
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