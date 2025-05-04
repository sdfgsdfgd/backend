package net.sdfgsdfg

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

fun Route.ws() {
    val json = Json { ignoreUnknownKeys = true }
    val repoManager = RepositoryManager()

    webSocket("/ws") {
        if (!ConnectionCounter.tryAcquire()) { // Reject if at capacity
            application.log.warn("Rejecting WebSocket connection: at capacity.")
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Server at capacity."))
            return@webSocket
        }

        val clientId = "client-${System.currentTimeMillis()}" // Generate an ID for logging
        application.log.info("[WS-$clientId] Connected. Active: ${ConnectionCounter.count()}")

        runCatching {
            // todo: STREAMLINE under a new Banner sealed Class, and --> receiveDeserialized<WsMessage>()
            incoming.receiveAsFlow().collect { frame ->
                (frame as? Frame.Text)?.readText()?.trim()?.takeIf { it.isNotBlank() }?.let { rawText ->
                    application.log.info("[WS-$clientId] Received: $rawText")

                    runCatching {
                        json.decodeFromString<WsMessage>(rawText)
                    }.onSuccess { baseMessage ->
                        when (baseMessage.type?.lowercase()) {
                            "ping" -> sendSerialized(WsMessage("pong", baseMessage.clientTimestamp, System.currentTimeMillis()))
                            "echo" -> sendSerialized(baseMessage)
                            "bye" -> close(CloseReason(CloseReason.Codes.NORMAL, "Client Bye"))

                            // xx  [ Content Sync ]
                            "workspace_select_github" -> {
                                val gitMessage = json.decodeFromString<GitHubRepoSelectMessage>(rawText)
                                handleGitHubRepoSelect(gitMessage, clientId, repoManager)
                            }

                            // xx [ Container Management ]
                            "arcana_start", "container_start", "container_input", "container_stop" -> {
                                handleContainerRequest(json.decodeFromString<ContainerMessage>(rawText), clientId)
                            }

                            // xx [ gRPC ] First PoC demo ! ?
                            "scrape_gpt", "scrape_browse" -> {}

                            else -> application.log.warn("[WS-$clientId] Unknown message: $baseMessage")
                        }
                    }.onFailure { err ->
                        application.log.error("[WS-$clientId] Parse error: ${err.message}", err)
                        sendSerialized(WsMessage("error", null, System.currentTimeMillis(), "Invalid message"))
                    }
                }
            }
        }.onFailure { e ->
            when (e) {
                is ClosedReceiveChannelException -> application.log.info("[WS-$clientId] Client closed connection.")
                else -> application.log.error("[WS-$clientId] Error: ${e.message}", e)
            }
        }.also {
            // Cleanup
            activeGitOperations[clientId]?.cancel()
            stopContainerInternal(clientId)
            WorkspaceTracker.removeClient(clientId)
            ConnectionCounter.release()
            application.log.info("[WS-$clientId] Disconnected. Active: ${ConnectionCounter.count()}")
        }
    }
}

@Serializable
data class WsMessage(
    val type: String?,
    val clientTimestamp: Long?,
    val serverTimestamp: Long? = null,
    val payload: String? = null
)

// Connection pool management
object ConnectionCounter {
    private val current = AtomicInteger(0)
    private const val MAX_CONNECTIONS = 2

    fun tryAcquire(): Boolean {
        val existing = current.get()
        return if (existing >= MAX_CONNECTIONS) false // Reject immediately if we already hit the limit
        else current.compareAndSet(existing, existing + 1)
    }

    fun release() {
        current.decrementAndGet()
    }

    fun count(): Int = current.get()
}