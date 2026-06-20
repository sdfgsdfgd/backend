package net.sdfgsdfg.dashboard

import kotlinx.serialization.encodeToString
import net.sdfgsdfg.data.model.IssueMutationRequestDto
import net.sdfgsdfg.data.model.OpsIssuePatchDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsSocketMessageDto
import net.sdfgsdfg.data.model.OpsViewerDto
import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

private var activeOpsApiBase: String? = null

internal actual fun loadOpsSummary(
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
) = loadOpsData("ops-summary-loader", "Failed to load ops summary", onLoaded, onFailed) { fetchOpsJson("/api/ops/summary") }

internal actual fun loadOpsViewer(
    onLoaded: (OpsViewerDto) -> Unit,
    onFailed: (String) -> Unit,
) = loadOpsData("ops-viewer-loader", "Failed to load ops viewer", onLoaded, onFailed) { fetchOpsJson("/api/ops/viewer") }

private fun <T> loadOpsData(
    threadName: String,
    fallbackError: String,
    onLoaded: (T) -> Unit,
    onFailed: (String) -> Unit,
    fetch: () -> T,
) {
    Thread({
        runCatching(fetch)
            .fold(
                onSuccess = { SwingUtilities.invokeLater { onLoaded(it) } },
                onFailure = { error -> SwingUtilities.invokeLater { onFailed(error.message ?: fallbackError) } },
            )
    }, threadName).apply {
        isDaemon = true
        start()
    }
}

internal actual fun openOpsUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(opsUrl(url)))
    }
}

internal actual fun readDashboardPref(key: String): String? {
    val envKey = key.uppercase().replace('.', '_')
    return System.getProperty(key)
        ?: System.getProperty("dashboard.$key")
        ?: System.getenv(envKey)
        ?: System.getenv("DASHBOARD_$envKey")
}

internal actual fun writeDashboardPref(key: String, value: String?) {
}

internal actual fun connectOpsSocket(
    onMessage: (OpsSocketMessageDto) -> Unit,
    onState: (OpsSocketState) -> Unit,
): () -> Unit {
    val active = AtomicBoolean(true)
    val client = HttpClient.newHttpClient()
    var socket: WebSocket? = null

    fun send(type: String, clientTimestamp: Long? = null) {
        socket?.takeUnless { it.isOutputClosed }?.sendText(dashboardJson.encodeToString(OpsSocketMessageDto(type, clientTimestamp)), true)
    }

    Thread({
        while (active.get()) {
            val endpoints = configuredOpsApiBase()
                ?.let(::listOf)
                ?: listOfNotNull(activeOpsApiBase, "http://127.0.0.1", "https://ops.sdfgsdfg.net").distinct()
            for (endpoint in endpoints) {
                if (!active.get()) break
                SwingUtilities.invokeLater { onState(OpsSocketState(OpsSocketStatus.CONNECTING)) }
                runCatching {
                    val wsUrl = endpoint
                        .replaceFirst("https://", "wss://")
                        .replaceFirst("http://", "ws://") + "/api/ops/ws"
                    val listener = object : WebSocket.Listener {
                        private var text = ""

                        override fun onOpen(webSocket: WebSocket) {
                            socket = webSocket
                            activeOpsApiBase = endpoint
                            SwingUtilities.invokeLater { onState(OpsSocketState(OpsSocketStatus.CONNECTED)) }
                            send("refresh")
                            webSocket.request(1)
                        }

                        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                            text += data
                            if (last) {
                                val raw = text
                                text = ""
                                runCatching { dashboardJson.decodeFromString<OpsSocketMessageDto>(raw) }.getOrNull()?.let { message ->
                                    if (message.type == "pong") {
                                        val latency = message.clientTimestamp?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
                                        SwingUtilities.invokeLater { onState(OpsSocketState(OpsSocketStatus.CONNECTED, latency)) }
                                    } else {
                                        SwingUtilities.invokeLater { onMessage(message) }
                                    }
                                }
                            }
                            webSocket.request(1)
                            return CompletableFuture.completedFuture(null)
                        }

                        override fun onError(webSocket: WebSocket, error: Throwable) {
                            if (active.get()) SwingUtilities.invokeLater { onState(OpsSocketState(OpsSocketStatus.DISCONNECTED)) }
                        }

                        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                            if (active.get()) SwingUtilities.invokeLater { onState(OpsSocketState(OpsSocketStatus.DISCONNECTED)) }
                            return CompletableFuture.completedFuture(null)
                        }
                    }
                    socket = client.newWebSocketBuilder().buildAsync(URI.create(wsUrl), listener).join()
                    while (active.get() && socket?.isInputClosed == false && socket?.isOutputClosed == false) {
                        send("ping", System.currentTimeMillis())
                        Thread.sleep(15_000)
                    }
                }.onFailure {
                    socket = null
                    if (active.get()) SwingUtilities.invokeLater { onState(OpsSocketState(OpsSocketStatus.DISCONNECTED)) }
                }
            }
            Thread.sleep(2_500)
        }
    }, "ops-socket").apply {
        isDaemon = true
        start()
    }

    return {
        active.set(false)
        runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "dashboard disposed") }
    }
}

internal actual fun mutateIssue(
    request: IssueMutationRequestDto,
    onLoaded: (OpsIssuePatchDto) -> Unit,
    onFailed: (String) -> Unit,
) {
    Thread({
        runCatching {
            val endpoint = activeOpsApiBase ?: configuredOpsApiBase() ?: "http://127.0.0.1"
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(URI.create("$endpoint/api/ops/issues"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(dashboardJson.encodeToString(request)))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() !in 200..299) error("POST $endpoint/api/ops/issues failed with ${response.statusCode()}")
            dashboardJson.decodeFromString<OpsIssuePatchDto>(response.body())
        }.fold(
            onSuccess = { SwingUtilities.invokeLater { onLoaded(it) } },
            onFailure = { SwingUtilities.invokeLater { onFailed(it.message ?: "Issue mutation failed") } },
        )
    }, "ops-issue-mutator").apply {
        isDaemon = true
        start()
    }
}

private inline fun <reified T> fetchOpsJson(path: String): T {
    val endpoints = configuredOpsApiBase()
        ?.let(::listOf)
        ?: listOf("http://127.0.0.1", "https://ops.sdfgsdfg.net")

    val client = HttpClient.newHttpClient()
    var lastError: Throwable? = null
    endpoints.forEach { endpoint ->
        runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$endpoint$path"))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("GET $endpoint$path failed with ${response.statusCode()}")
            }
            activeOpsApiBase = endpoint
            return dashboardJson.decodeFromString<T>(response.body())
        }.onFailure { lastError = it }
    }
    throw lastError ?: error("No ops API endpoints configured")
}

private fun opsUrl(pathOrUrl: String): String = when {
    pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
    else -> "${activeOpsApiBase ?: configuredOpsApiBase() ?: "https://ops.sdfgsdfg.net"}$pathOrUrl"
}

private fun configuredOpsApiBase(): String? = System.getenv("OPS_API_BASE")
    ?.trim()
    ?.trimEnd('/')
    ?.takeIf { it.isNotBlank() }
