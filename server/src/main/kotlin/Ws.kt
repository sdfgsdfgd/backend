package net.sdfgsdfg

import com.google.gson.Gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.routing.Route
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.application
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Define the WebSocket endpoint, handling concurrency limit, ping/pong, etc.
 */
fun Route.webSocketRoutes() {
    val gson = Gson()

    webSocket("/ws") {
        // Reject if at capacity
        if (!ConnectionCounter.tryAcquire()) {
            application.log.warn("Rejecting WebSocket connection: at capacity.")
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Server at capacity."))
            return@webSocket
        }

        // Generate an ID for logging
        val clientId = "client-${System.currentTimeMillis()}"
        application.log.info("[WS-$clientId] Connected. Active: ${ConnectionCounter.count()}")

        try {
            // Collect incoming frames until disconnected
            incoming.receiveAsFlow().collect { frame ->
                if (frame is Frame.Text) {
                    val rawText = frame.readText().trim()
                    application.log.info("[WS-$clientId] Received: $rawText")

                    // Parse as WsMessage (fallback to "unknown" if it fails)
                    val message = runCatching {
                        gson.fromJson(rawText, WsMessage::class.java)
                    }.getOrElse { WsMessage(type = "unknown") }

                    when (message.type?.lowercase()) {
                        "ping" -> {
                            // Send back a pong
                            val pong = WsMessage(
                                type = "pong",
                                clientTimestamp = message.clientTimestamp,
                                serverTimestamp = System.currentTimeMillis()
                            )
                            send(Frame.Text(gson.toJson(pong)))
                        }

                        "pong" -> {
                            // Usually we don't get these from clients
                            application.log.info("[WS-$clientId] Unexpected PONG.")
                        }

                        "echo" -> {
                            // Echo the entire message back
                            send(Frame.Text(gson.toJson(message)))
                        }

                        "bye" -> {
                            // Close gracefully
                            application.log.info("[WS-$clientId] Client sent Bye.")
                            close(CloseReason(CloseReason.Codes.NORMAL, "Client Bye"))
                        }

                        else -> {
                            // Unknown message type
                            application.log.warn("[WS-$clientId] Unknown message: $rawText")
                        }
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            application.log.info("[WS-$clientId] Disconnected by client.")
        } catch (t: Throwable) {
            application.log.error("[WS-$clientId] Error: ${t.message}", t)
        } finally {
            // Decrement the connection count and log
            ConnectionCounter.release()
            application.log.info("[WS-$clientId] Disconnected. Active: ${ConnectionCounter.count()}")
        }
    }
}

// region Helpars
/**
 * WS Client Connection Pool tracking
 */
object ConnectionCounter {
    private val current = AtomicInteger(0)
    private const val MAX_CONNECTIONS = 2

    fun tryAcquire(): Boolean {
        val existing = current.get()
        // Reject immediately if we already hit the limit
        return if (existing >= MAX_CONNECTIONS) false
        else current.compareAndSet(existing, existing + 1)
    }

    fun release() {
        current.decrementAndGet()
    }

    fun count(): Int = current.get()
}

/**
 * Our simple data class for incoming/outgoing messages via Gson.
 */
data class WsMessage(
    val type: String? = null,
    val clientTimestamp: Long? = null,
    val serverTimestamp: Long? = null,
    val content: String? = null
)

/**
 * Install WebSockets with sensible ping/timeout settings.
 */
fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }
}
// endregion