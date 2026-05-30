package net.sdfgsdfg.dashboard

import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.js.ExperimentalWasmJsInterop
import net.sdfgsdfg.data.model.IssueMutationRequestDto
import net.sdfgsdfg.data.model.OpsSocketMessageDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket
import org.w3c.fetch.Response
import org.w3c.xhr.XMLHttpRequest

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun loadOpsSummary(
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
) {
    window.fetch(opsSummaryUrl()).then(
        onFulfilled = { response: Response ->
            if (!response.ok) {
                onFailed("GET /api/ops/summary failed with ${response.status}")
            } else {
                response.text().then(
                    onFulfilled = { body ->
                        runCatching { dashboardJson.decodeFromString<OpsSummaryDto>("$body") }
                            .fold(
                                onSuccess = onLoaded,
                                onFailure = { onFailed(it.message ?: "Failed to decode ops summary") },
                            )
                        null
                    },
                    onRejected = {
                        onFailed("GET /api/ops/summary response body failed")
                        null
                    },
                )
            }
            null
        },
        onRejected = {
            onFailed("GET /api/ops/summary failed")
            null
        },
    )
}

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun mutateIssue(
    request: IssueMutationRequestDto,
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
) {
    val body = dashboardJson.encodeToString(request)
    val url = opsUrl("/api/ops/issues")
    XMLHttpRequest().apply {
        open("POST", url)
        setRequestHeader("Content-Type", "application/json")
        onload = {
            if (status.toInt() in 200..299) {
                runCatching { dashboardJson.decodeFromString<OpsSummaryDto>(responseText) }
                    .fold(onLoaded, { onFailed("POST $url decoded badly: ${it.message}") })
            } else {
                onFailed("POST $url failed with $status ${responseText.take(120)}")
            }
            null
        }
        onerror = {
            onFailed("POST $url failed before response")
            null
        }
        send(body)
    }
}

internal actual fun openOpsUrl(url: String) {
    window.open(opsUrl(url), "_blank")
}

internal actual fun readDashboardPref(key: String): String? =
    runCatching { localStorage.getItem(key) }.getOrNull()

internal actual fun writeDashboardPref(key: String, value: String?) {
    runCatching {
        if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value)
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun connectOpsSocket(
    onMessage: (OpsSocketMessageDto) -> Unit,
    onState: (OpsSocketState) -> Unit,
): () -> Unit {
    var active = true
    var socket: WebSocket? = null
    var pingTimer = 0
    var reconnectTimer = 0

    fun send(type: String, clientTimestamp: Long? = null) {
        val ws = socket ?: return
        if (ws.readyState == WebSocket.OPEN) {
            ws.send(dashboardJson.encodeToString(OpsSocketMessageDto(type, clientTimestamp)))
        }
    }

    fun connect() {
        if (!active) return
        onState(OpsSocketState(OpsSocketStatus.CONNECTING))
        socket = WebSocket(opsWsUrl()).also { ws ->
            fun ping() = send("ping", window.performance.now().toLong())
            ws.onopen = {
                onState(OpsSocketState(OpsSocketStatus.CONNECTED))
                send("refresh")
                ping()
                window.clearInterval(pingTimer)
                pingTimer = window.setInterval({ ping(); null }, 15_000)
                null
            }
            ws.onmessage = { event: MessageEvent ->
                val message = runCatching { dashboardJson.decodeFromString<OpsSocketMessageDto>(event.data.toString()) }.getOrNull()
                if (message?.type == "pong") {
                    val latency = message.clientTimestamp?.let { (window.performance.now() - it).toLong().coerceAtLeast(0L) }
                    onState(OpsSocketState(OpsSocketStatus.CONNECTED, latency))
                } else if (message != null) {
                    onMessage(message)
                }
                null
            }
            ws.onerror = {
                onState(OpsSocketState(OpsSocketStatus.DISCONNECTED))
                null
            }
            ws.onclose = {
                window.clearInterval(pingTimer)
                socket = null
                if (active) {
                    onState(OpsSocketState(OpsSocketStatus.DISCONNECTED))
                    window.clearTimeout(reconnectTimer)
                    reconnectTimer = window.setTimeout({ connect(); null }, 5_000)
                }
                null
            }
        }
    }

    connect()
    return {
        active = false
        window.clearInterval(pingTimer)
        window.clearTimeout(reconnectTimer)
        socket?.close()
        socket = null
    }
}

private fun opsSummaryUrl(): String {
    return opsUrl("/api/ops/summary")
}

private fun opsWsUrl(): String {
    val scheme = if (window.location.protocol == "https:") "wss:" else "ws:"
    return opsUrl("/api/ops/ws").replace(Regex("^https?:"), scheme)
}

private fun opsUrl(pathOrUrl: String): String {
    if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl
    val port = window.location.port
    return if (port.isBlank() || port == "80" || port == "443") {
        pathOrUrl
    } else {
        "http://127.0.0.1$pathOrUrl"
    }
}
