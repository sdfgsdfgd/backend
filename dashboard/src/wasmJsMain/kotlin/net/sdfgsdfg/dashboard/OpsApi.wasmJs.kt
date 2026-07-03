package net.sdfgsdfg.dashboard

import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.js.ExperimentalWasmJsInterop
import net.sdfgsdfg.data.model.IssueMutationRequestDto
import net.sdfgsdfg.data.model.OPS_AUTH_GITHUB_START_PATH
import net.sdfgsdfg.data.model.OPS_AUTH_LOGOUT_PATH
import net.sdfgsdfg.data.model.OPS_ISSUES_PATH
import net.sdfgsdfg.data.model.OPS_SUMMARY_PATH
import net.sdfgsdfg.data.model.OPS_VIEWER_PATH
import net.sdfgsdfg.data.model.OPS_WS_PATH
import net.sdfgsdfg.data.model.OpsIssuePatchDto
import net.sdfgsdfg.data.model.OpsSocketMessageDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsViewerDto
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import org.w3c.dom.WebSocket
import org.w3c.xhr.XMLHttpRequest

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun loadOpsSummary(
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
) = loadOpsJson(opsSummaryUrl(), "GET $OPS_SUMMARY_PATH", onLoaded, onFailed)

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun loadOpsViewer(
    onLoaded: (OpsViewerDto) -> Unit,
    onFailed: (String) -> Unit,
) = loadOpsJson(opsUrl(OPS_VIEWER_PATH), "GET $OPS_VIEWER_PATH", onLoaded, onFailed)

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun loadOpsText(
    path: String,
    onLoaded: (String) -> Unit,
    onFailed: (String) -> Unit,
) {
    val url = opsUrl(path)
    XMLHttpRequest().apply {
        open("GET", url)
        withCredentials = true
        onload = {
            if (status.toInt() in 200..299) {
                onLoaded(responseText)
            } else {
                onFailed("GET $url failed with $status ${responseText.take(120)}")
            }
            null
        }
        onerror = {
            onFailed("GET $url failed")
            null
        }
        send()
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
private inline fun <reified T> loadOpsJson(
    url: String,
    label: String,
    noinline onLoaded: (T) -> Unit,
    noinline onFailed: (String) -> Unit,
) {
    XMLHttpRequest().apply {
        open("GET", url)
        withCredentials = true
        onload = {
            if (status.toInt() in 200..299) {
                runCatching { dashboardJson.decodeFromString<T>(responseText) }
                    .fold(onLoaded, { onFailed(it.message ?: "Failed to decode $label") })
            } else {
                onFailed("$label failed with $status ${responseText.take(120)}")
            }
            null
        }
        onerror = {
            onFailed("$label failed")
            null
        }
        send()
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun mutateIssue(
    request: IssueMutationRequestDto,
    onLoaded: (OpsIssuePatchDto) -> Unit,
    onFailed: (String) -> Unit,
) {
    val body = dashboardJson.encodeToString(request)
    val url = opsUrl(OPS_ISSUES_PATH)
    XMLHttpRequest().apply {
        open("POST", url)
        withCredentials = true
        setRequestHeader("Content-Type", "application/json")
        onload = {
            if (status.toInt() in 200..299) {
                runCatching { dashboardJson.decodeFromString<OpsIssuePatchDto>(responseText) }
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

private fun navigateOpsUrl(url: String) {
    window.location.href = opsUrl(url)
}

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun startOpsGithubAuth(onComplete: () -> Unit) {
    val authUrl = opsUrl(opsAuthPath(OPS_AUTH_GITHUB_START_PATH, "popup" to "1"))
    val popup = window.open(authUrl, "ops-github-auth", "popup,width=560,height=680")
    if (popup == null) {
        navigateOpsUrl(opsAuthPath(OPS_AUTH_GITHUB_START_PATH))
        return
    }

    var completed = false
    var closeTimer = 0
    lateinit var listener: (Event) -> Unit
    fun complete() {
        if (completed) return
        completed = true
        window.clearInterval(closeTimer)
        window.removeEventListener("message", listener)
        onComplete()
    }
    listener = { event ->
        val message = event as? MessageEvent
        if (message?.data?.toString()?.contains("ops.github.auth") == true) complete()
    }
    window.addEventListener("message", listener)
    closeTimer = window.setInterval({
        if (popup.closed) complete()
        null
    }, 700)
}

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun endOpsGithubAuth(onComplete: () -> Unit) {
    XMLHttpRequest().apply {
        open("POST", opsUrl(opsAuthPath(OPS_AUTH_LOGOUT_PATH, "api" to "1")))
        withCredentials = true
        onload = {
            onComplete()
            null
        }
        onerror = {
            onComplete()
            null
        }
        send()
    }
}

internal actual fun cancelOpsGithubAuth() = Unit

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
    return opsUrl(OPS_SUMMARY_PATH)
}

private fun opsWsUrl(): String {
    val scheme = if (window.location.protocol == "https:") "wss:" else "ws:"
    return opsUrl(OPS_WS_PATH).replace(Regex("^https?:"), scheme)
}

private fun opsUrl(pathOrUrl: String): String {
    if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl
    val port = window.location.port
    return if (port.isBlank() || port == "80" || port == "443") {
        pathOrUrl
    } else {
        val host = window.location.hostname.takeIf { it == "localhost" || it == "127.0.0.1" } ?: "127.0.0.1"
        "http://$host$pathOrUrl"
    }
}

private fun urlComponent(value: String) = buildString(value.length) {
    value.forEach { char ->
        if (char.isLetterOrDigit() || char in "-_.~") {
            append(char)
        } else {
            char.toString().encodeToByteArray().forEach { raw ->
                val byte = raw.toInt() and 0xff
                append("0123456789ABCDEF"[byte ushr 4])
                append("0123456789ABCDEF"[byte and 0xf])
            }
        }
    }
}

private fun opsAuthPath(path: String, vararg params: Pair<String, String>) = buildString {
    append(path)
    append("?returnTo=")
    append(urlComponent(window.location.href))
    params.forEach { (key, value) ->
        append('&')
        append(urlComponent(key))
        append('=')
        append(urlComponent(value))
    }
}
