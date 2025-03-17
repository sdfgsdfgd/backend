package net.sdfgsdfg

import com.google.gson.Gson
import io.ktor.server.application.log
import io.ktor.server.routing.Route
import io.ktor.server.websocket.application
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicInteger

fun Route.ws() {
    val gson = Gson()
    val repoManager = RepositoryManager()

    webSocket("/ws") {
        // Reject if at capacity
        if (!ConnectionCounter.tryAcquire()) {
            application.log.warn("Rejecting WebSocket connection: at capacity.")
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Server at capacity."))
            return@webSocket
        }

        val clientId = "client-${System.currentTimeMillis()}" // Generate an ID for logging
        application.log.info("[WS-$clientId] Connected. Active: ${ConnectionCounter.count()}")

        runCatching {
            // xx When our API is stable, we migrate to minimalist use of   receiveDeserialized<WsMessage>() , for now this is powerful, best for prototyping
            incoming.receiveAsFlow().collect { frame ->
                (frame as? Frame.Text)
                    ?.readText()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.also { application.log.info("[WS-$clientId] Received: $it") }
                    // Parse
                    ?.let { rawText ->
                        runCatching {
                            gson.fromJson(rawText, WsMessage::class.java)
                        }.onSuccess { baseMessage ->
                            when (baseMessage.type?.lowercase()) {
                                "ping" -> sendSerialized(WsMessage("pong", baseMessage.clientTimestamp, System.currentTimeMillis()))
                                "echo" -> sendSerialized(baseMessage)
                                "bye" -> close(CloseReason(CloseReason.Codes.NORMAL, "Client Bye")).also {
                                    application.log.info("[WS-$clientId] Client sent Bye")
                                }

                                // xx  [ Content Sync ]
                                "workspace_select_github" -> {
                                    val gitMessage = gson.fromJson(rawText, GitHubRepoSelectMessage::class.java)
                                    handleGitHubRepoSelect(gitMessage, clientId, repoManager)
                                }

                                else -> application.log.warn("[WS-$clientId] Unknown message: $baseMessage")
                            }
                        }.getOrElse { error ->
                            application.log.error("[WS-$clientId] Failed to parse message: ${error.message}")
                            sendSerialized(WsMessage("error", null, System.currentTimeMillis(), "Invalid message format"))
                        }
                    }
            }
        }.onFailure { e ->
            when (e) {
                is ClosedReceiveChannelException -> application.log.info("[WS-$clientId] Client closed connection.")
                else -> application.log.error("[WS-$clientId] Error: ${e.message}", e)
            }
        }.also {
            // Cleanup any active operations for this client
            activeGitOperations.entries.removeIf { (_, job) ->
                job.cancel()
                true
            }
            ConnectionCounter.release() // Decrement the connection count and log
            application.log.info("[WS-$clientId] Disconnected. Active: ${ConnectionCounter.count()}")
        }
    }
}

// Basic WebSocket message class
data class WsMessage(
    val type: String? = null,
    val clientTimestamp: Long? = null,
    val serverTimestamp: Long? = null,
    val content: String? = null
)

// Connection pool management
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