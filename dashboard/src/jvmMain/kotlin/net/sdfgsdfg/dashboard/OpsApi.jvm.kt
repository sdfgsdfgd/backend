package net.sdfgsdfg.dashboard

import kotlinx.serialization.encodeToString
import net.sdfgsdfg.data.model.IssueMutationRequestDto
import net.sdfgsdfg.data.model.OPS_AUTH_GITHUB_DEVICE_POLL_PATH
import net.sdfgsdfg.data.model.OPS_AUTH_GITHUB_DEVICE_START_PATH
import net.sdfgsdfg.data.model.OPS_ISSUES_PATH
import net.sdfgsdfg.data.model.OPS_SUMMARY_PATH
import net.sdfgsdfg.data.model.OPS_VIEWER_PATH
import net.sdfgsdfg.data.model.OPS_WS_PATH
import net.sdfgsdfg.data.model.OpsGithubDevicePollDto
import net.sdfgsdfg.data.model.OpsGithubDeviceStartDto
import net.sdfgsdfg.data.model.OpsGithubTokenDto
import net.sdfgsdfg.data.model.OpsIssuePatchDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsSocketMessageDto
import net.sdfgsdfg.data.model.OpsViewerDto
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences

private const val githubTokenPrefKey = "ops.github.token"
private const val opsSocketSendTimeoutSeconds = 10L
private val dashboardPrefs = Preferences.userRoot().node("net/sdfgsdfg/dashboard")
// One selector/pool serves the desktop process; Compose owns sockets, main owns transport shutdown.
private val opsHttp = HttpClient.newHttpClient()
private var activeOpsApiBase: String? = null
private enum class OpsApiMode { Dev, Local, Prod }
@Volatile private var activeGithubToken: String? = readDashboardPref(githubTokenPrefKey)
private val activeGithubAuth = AtomicReference<AtomicBoolean?>(null)
private val activeGithubAuthThread = AtomicReference<Thread?>(null)

internal actual fun loadOpsSummary(
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
) = loadOpsData("ops-summary-loader", "Failed to load ops summary", onLoaded, onFailed) { fetchOpsJson(OPS_SUMMARY_PATH) }

internal actual fun loadOpsViewer(
    onLoaded: (OpsViewerDto) -> Unit,
    onFailed: (String) -> Unit,
) = loadOpsData("ops-viewer-loader", "Failed to load ops viewer", onLoaded, onFailed) { fetchOpsJson(OPS_VIEWER_PATH) }

internal actual fun loadOpsText(
    path: String,
    onLoaded: (String) -> Unit,
    onFailed: (String) -> Unit,
) = loadOpsData("ops-text-loader", "Failed to load ops artifact", onLoaded, onFailed) { fetchOpsText(path) }

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
                onSuccess = { EventQueue.invokeLater { onLoaded(it) } },
                onFailure = { error -> EventQueue.invokeLater { onFailed(error.message ?: fallbackError) } },
            )
    }, threadName).apply {
        isDaemon = true
        start()
    }
}

internal actual fun openOpsUrl(url: String) {
    browse(opsUrl(url))
}

internal actual fun startOpsGithubAuth(onComplete: () -> Unit) {
    cancelOpsGithubAuth()
    val active = AtomicBoolean(true)
    activeGithubAuth.set(active)
    showOpsGithubAuthWindow(
        OpsGithubAuthWindowState(
            title = "Opening GitHub",
            detail = "Preparing device authorization.",
            status = "Waiting for GitHub...",
        )
    )
    Thread({
        runCatching {
            val start = postOpsJson<OpsGithubDeviceStartDto>(OPS_AUTH_GITHUB_DEVICE_START_PATH)
            if (!active.get()) error("GitHub login cancelled")
            val copied = copyToClipboard(start.userCode)
            showOpsGithubAuthWindow(
                OpsGithubAuthWindowState(
                    title = "GitHub Login",
                    code = start.userCode,
                    detail = if (copied) "Code copied to clipboard. Paste it if GitHub asks." else "Clipboard unavailable. Copy this code manually.",
                    status = "Waiting for authorization in your browser...",
                )
            )
            browse(start.verificationUriComplete ?: start.verificationUri)
            activeGithubToken = pollOpsGithubDevice(start, active).accessToken.also { writeDashboardPref(githubTokenPrefKey, it) }
        }.fold(
            onSuccess = {
                showOpsGithubAuthWindow(
                    OpsGithubAuthWindowState(
                        title = "GitHub Connected",
                        detail = "Returning to Trio Ops Cockpit.",
                        status = "Done.",
                        terminal = true,
                        success = true,
                    )
                )
                Thread({
                    Thread.sleep(900)
                    hideOpsGithubAuthWindow()
                }, "ops-github-auth-window-close").apply {
                    isDaemon = true
                    start()
                }
                EventQueue.invokeLater(onComplete)
            },
            onFailure = {
                val message = it.message ?: "GitHub auth failed"
                if (!active.get()) {
                    hideOpsGithubAuthWindow()
                    EventQueue.invokeLater(onComplete)
                    return@fold
                }
                System.err.println("GitHub auth failed: $message")
                showOpsGithubAuthWindow(
                    OpsGithubAuthWindowState(
                        title = "GitHub Login Failed",
                        code = null,
                        detail = message,
                        status = "Close this window and try again.",
                        terminal = true,
                    )
                )
                EventQueue.invokeLater {
                    onComplete()
                }
            },
        )
        activeGithubAuth.compareAndSet(active, null)
        activeGithubAuthThread.compareAndSet(Thread.currentThread(), null)
    }, "ops-github-auth").apply {
        isDaemon = true
        activeGithubAuthThread.set(this)
        start()
    }
}

private fun copyToClipboard(value: String) =
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }.isSuccess

internal actual fun endOpsGithubAuth(onComplete: () -> Unit) {
    cancelOpsGithubAuth()
    activeGithubToken = null
    writeDashboardPref(githubTokenPrefKey, null)
    EventQueue.invokeLater(onComplete)
}

internal actual fun cancelOpsGithubAuth() {
    activeGithubAuth.getAndSet(null)?.set(false)
    activeGithubAuthThread.getAndSet(null)?.interrupt()
}

private fun browse(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url))
    }
}

internal actual fun readDashboardPref(key: String): String? {
    val envKey = key.uppercase().replace('.', '_')
    return System.getProperty(key)
        ?: System.getProperty("dashboard.$key")
        ?: System.getenv(envKey)
        ?: System.getenv("DASHBOARD_$envKey")
        ?: runCatching { dashboardPrefs.get(key, null) }.getOrNull()
}

internal actual fun writeDashboardPref(key: String, value: String?) {
    runCatching {
        if (value == null) dashboardPrefs.remove(key) else dashboardPrefs.put(key, value)
        dashboardPrefs.flush()
    }
}

internal actual fun connectOpsSocket(
    onMessage: (OpsSocketMessageDto) -> Unit,
    onState: (OpsSocketState) -> Unit,
): OpsSocketConnection {
    val active = AtomicBoolean(true)
    val socket = AtomicReference<WebSocket?>()
    val sendLock = Any()
    var sendTail: CompletionStage<*> = CompletableFuture.completedFuture(Unit)

    fun disconnect(current: WebSocket, abort: Boolean = false) {
        if (abort) current.abort()
        if (socket.compareAndSet(current, null) && active.get()) {
            EventQueue.invokeLater { onState(OpsSocketState(OpsSocketStatus.DISCONNECTED)) }
        }
    }

    fun send(message: OpsSocketMessageDto): Boolean {
        val current = socket.get()?.takeUnless { it.isOutputClosed } ?: return false
        val payload = runCatching { dashboardJson.encodeToString(message) }.getOrNull() ?: return false
        val queued = synchronized(sendLock) {
            sendTail = sendTail.handle { _, _ -> Unit }.thenCompose {
                if (socket.get() === current && !current.isOutputClosed) {
                    current.sendText(payload, true).toCompletableFuture()
                        .orTimeout(opsSocketSendTimeoutSeconds, TimeUnit.SECONDS)
                }
                else CompletableFuture.failedFuture(IllegalStateException("Ops socket changed before send"))
            }
            sendTail
        }
        queued.whenComplete { _, error -> if (error != null) disconnect(current, abort = true) }
        return true
    }

    fun send(type: String, clientTimestamp: Long? = null) = send(OpsSocketMessageDto(type, clientTimestamp))

    Thread({
        while (active.get()) {
            for (endpoint in opsApiEndpoints()) {
                if (!active.get()) break
                EventQueue.invokeLater { onState(OpsSocketState(OpsSocketStatus.CONNECTING)) }
                runCatching {
                    val wsUrl = endpoint
                        .replaceFirst("https://", "wss://")
                        .replaceFirst("http://", "ws://") + OPS_WS_PATH
                    val builder = opsHttp.newWebSocketBuilder().applyOpsAuth()
                    val listener = object : WebSocket.Listener {
                        private var text = ""

                        override fun onOpen(webSocket: WebSocket) {
                            socket.set(webSocket)
                            activeOpsApiBase = endpoint
                            EventQueue.invokeLater { onState(OpsSocketState(OpsSocketStatus.CONNECTED)) }
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
                                        EventQueue.invokeLater { onState(OpsSocketState(OpsSocketStatus.CONNECTED, latency)) }
                                    } else {
                                        EventQueue.invokeLater { onMessage(message) }
                                    }
                                }
                            }
                            webSocket.request(1)
                            return CompletableFuture.completedFuture(null)
                        }

                        override fun onError(webSocket: WebSocket, error: Throwable) {
                            disconnect(webSocket)
                        }

                        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                            disconnect(webSocket)
                            return CompletableFuture.completedFuture(null)
                        }
                    }
                    socket.set(builder.buildAsync(URI.create(wsUrl), listener).join())
                    while (active.get() && socket.get()?.isInputClosed == false && socket.get()?.isOutputClosed == false) {
                        send("ping", System.currentTimeMillis())
                        Thread.sleep(15_000)
                    }
                }.onFailure {
                    socket.getAndSet(null)?.abort()
                    if (active.get()) EventQueue.invokeLater { onState(OpsSocketState(OpsSocketStatus.DISCONNECTED)) }
                }
            }
            runCatching { Thread.sleep(2_500) }
        }
    }, "ops-socket").apply {
        isDaemon = true
        start()
    }

    return OpsSocketConnection(::send) {
        active.set(false)
        runCatching { socket.getAndSet(null)?.sendClose(WebSocket.NORMAL_CLOSURE, "dashboard disposed") }
    }
}

internal actual fun mutateIssue(
    request: IssueMutationRequestDto,
    onLoaded: (OpsIssuePatchDto) -> Unit,
    onFailed: (String) -> Unit,
) {
    Thread({
        runCatching {
            val (_, body) = postOps(OPS_ISSUES_PATH, dashboardJson.encodeToString(request))
            dashboardJson.decodeFromString<OpsIssuePatchDto>(body)
        }.fold(
            onSuccess = { EventQueue.invokeLater { onLoaded(it) } },
            onFailure = { EventQueue.invokeLater { onFailed(it.message ?: "Issue mutation failed") } },
        )
    }, "ops-issue-mutator").apply {
        isDaemon = true
        start()
    }
}

private inline fun <reified T> fetchOpsJson(path: String): T {
    return dashboardJson.decodeFromString(fetchOpsText(path))
}

private fun fetchOpsText(path: String): String {
    var lastError: Throwable? = null
    val absolute = path.startsWith("http://") || path.startsWith("https://")
    val endpoints = if (absolute) listOf("") else opsApiEndpoints()
    endpoints.forEach { endpoint ->
        runCatching {
            val url = if (absolute) path else "$endpoint$path"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .applyOpsAuth()
                .GET()
                .build()
            val response = opsHttp.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("GET $url failed with ${response.statusCode()}")
            }
            if (!absolute) activeOpsApiBase = endpoint
            return response.body()
        }.onFailure { lastError = it }
    }
    throw lastError ?: error("No ops API endpoints configured")
}

private fun pollOpsGithubDevice(start: OpsGithubDeviceStartDto, active: AtomicBoolean): OpsGithubTokenDto {
    val expiresAt = System.currentTimeMillis() + start.expiresIn.coerceAtLeast(1) * 1_000L
    val intervalMs = start.interval.coerceAtLeast(1) * 1_000L
    while (active.get() && System.currentTimeMillis() < expiresAt) {
        try {
            Thread.sleep(intervalMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            error("GitHub login cancelled")
        }
        if (!active.get()) error("GitHub login cancelled")
        val (status, body) = postOps(OPS_AUTH_GITHUB_DEVICE_POLL_PATH, dashboardJson.encodeToString(OpsGithubDevicePollDto(start.deviceCode)))
        if (status == 202) continue
        return dashboardJson.decodeFromString<OpsGithubTokenDto>(body)
    }
    if (!active.get()) error("GitHub login cancelled")
    error("GitHub device auth expired")
}

private inline fun <reified T> postOpsJson(path: String, body: String = ""): T {
    val (status, responseBody) = postOps(path, body)
    if (status !in 200..299) error("POST $path failed with $status")
    return dashboardJson.decodeFromString<T>(responseBody)
}

private fun postOps(path: String, body: String = ""): Pair<Int, String> {
    var lastError: Throwable? = null
    opsApiEndpoints().forEach { endpoint ->
        runCatching {
            val response = opsHttp.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("$endpoint$path"))
                    .header("Content-Type", "application/json")
                    .applyOpsAuth()
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() !in 200..299 && response.statusCode() != 202) error(
                "POST $endpoint$path failed with ${response.statusCode()}: ${response.body().take(160)}"
            )
            activeOpsApiBase = endpoint
            return response.statusCode() to response.body()
        }.onFailure { lastError = it }
    }
    throw lastError ?: error("No ops API endpoints configured")
}

private fun HttpRequest.Builder.applyOpsAuth(): HttpRequest.Builder = apply {
    activeGithubToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
}

private fun WebSocket.Builder.applyOpsAuth(): WebSocket.Builder = apply {
    activeGithubToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
}

fun closeOpsTransport() {
    cancelOpsGithubAuth()
    opsHttp.shutdownNow()
}

private fun opsApiEndpoints(): List<String> =
    configuredOpsApiBase()?.let(::listOf) ?: when (opsApiMode()) {
        OpsApiMode.Dev -> listOfNotNull("http://127.0.0.1", activeOpsApiBase, "https://ops.sdfgsdfg.net").distinct()
        OpsApiMode.Local -> listOf("http://127.0.0.1")
        OpsApiMode.Prod -> listOf("https://ops.sdfgsdfg.net")
    }

private fun opsUrl(pathOrUrl: String): String = when {
    pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
    else -> "${activeOpsApiBase ?: opsApiEndpoints().first()}$pathOrUrl"
}

private fun configuredOpsApiBase(): String? = System.getenv("OPS_API_BASE")
    ?.trim()
    ?.trimEnd('/')
    ?.takeIf { it.isNotBlank() }

private fun opsApiMode(): OpsApiMode = when (System.getenv("OPS_ENV")?.trim()?.lowercase()) {
    "dev", "development", "local-dev", "fallback" -> OpsApiMode.Dev
    "local" -> OpsApiMode.Local
    "prod", "production", "remote" -> OpsApiMode.Prod
    else -> OpsApiMode.Prod
}
