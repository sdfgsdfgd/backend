package net.sdfgsdfg

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.sdfgsdfg.data.model.OpsRunEventDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSocketMessageDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

private const val opsSocketRefreshMs = 45_000L

internal object OpsSocketHub {
    private data class Client(val session: DefaultWebSocketServerSession, val sendLock: Mutex = Mutex())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = CopyOnWriteArraySet<Client>()
    private val loopLock = Mutex()
    private val activeRuns = ConcurrentHashMap<String, OpsRunEventDto>()
    private var loopJob: Job? = null
    private var summaryProvider: (() -> OpsSummaryDto)? = null

    val clientCount: Int
        get() = clients.size

    fun configure(provider: () -> OpsSummaryDto) {
        summaryProvider = provider
    }

    suspend fun serve(session: DefaultWebSocketServerSession) {
        val client = Client(session)
        clients += client
        ensureLoop()
        sendSummary(client)
        try {
            for (frame in session.incoming) {
                val raw = (frame as? Frame.Text)?.readText()?.trim()?.takeIf { it.isNotBlank() } ?: continue
                val decoded = runCatching { json.decodeFromString<OpsSocketMessageDto>(raw) }.getOrNull()
                when (val type = decoded?.type) {
                    "ping" -> client.send(OpsSocketMessageDto("pong", decoded.clientTimestamp, System.currentTimeMillis()))
                    "refresh" -> sendSummary(client)
                    null -> client.send(OpsSocketMessageDto("error", message = "Invalid ops socket message"))
                    else -> client.send(OpsSocketMessageDto("error", message = "Unknown ops socket message: $type"))
                }
            }
        } finally {
            clients -= client
            stopLoopIfIdle()
        }
    }

    fun broadcastSummary() {
        if (clients.isNotEmpty()) scope.launch { broadcastSummaryNow() }
    }

    fun broadcastRunStarted(repoId: String, run: TestRunSummaryDto) {
        val event = OpsRunEventDto(repoId, run)
        activeRuns[event.activeKey()] = event
        broadcast(OpsSocketMessageDto("run_started", serverTimestamp = System.currentTimeMillis(), runEvent = event))
    }

    fun withActiveRuns(summary: OpsSummaryDto): OpsSummaryDto {
        val active = activeRuns.values.toList()
        if (active.isEmpty()) return summary
        val resolved = mutableSetOf<String>()
        val repos = summary.repos.map { repo ->
            val finalRuns = repo.history + repo.runs + listOfNotNull(repo.latestRun)
            val pending = active.filter { event ->
                if (event.repoId != repo.id) return@filter false
                val complete = finalRuns.any { run -> run.resolves(event.run) }
                if (complete) resolved += event.activeKey()
                !complete
            }
            if (pending.isEmpty()) repo else repo.copy(history = (pending.map { it.run } + repo.history).distinctBy { it.historyKey() })
        }
        resolved.forEach(activeRuns::remove)
        return summary.copy(repos = repos)
    }

    private fun broadcast(message: OpsSocketMessageDto) {
        if (clients.isNotEmpty()) scope.launch { broadcastNow(message) }
    }

    private suspend fun ensureLoop() {
        loopLock.withLock {
            if (loopJob?.isActive == true) return
            loopJob = scope.launch {
                while (isActive) {
                    delay(opsSocketRefreshMs)
                    if (clients.isEmpty()) break
                    broadcastSummaryNow()
                }
            }
        }
    }

    private suspend fun stopLoopIfIdle() {
        loopLock.withLock {
            if (clients.isNotEmpty()) return
            loopJob?.cancel()
            loopJob = null
        }
    }

    private suspend fun broadcastSummaryNow() {
        val message = summaryMessage() ?: return
        broadcastNow(message)
    }

    private suspend fun broadcastNow(message: OpsSocketMessageDto) {
        clients.forEach { client ->
            runCatching { client.send(message) }.onFailure { clients -= client }
        }
        stopLoopIfIdle()
    }

    private suspend fun sendSummary(client: Client) {
        summaryMessage()?.let { runCatching { client.send(it) }.onFailure { clients -= client } }
    }

    private suspend fun summaryMessage() = withContext(Dispatchers.IO) {
        summaryProvider?.let {
            OpsSocketMessageDto("summary", serverTimestamp = System.currentTimeMillis(), summary = it())
        }
    }

    private suspend fun Client.send(message: OpsSocketMessageDto) = sendLock.withLock {
        session.send(json.encodeToString(message))
    }
}

private fun OpsRunEventDto.activeKey() = "$repoId:${run.label.lifecycleLabel()}"

private fun String.lifecycleLabel() = when {
    startsWith("deploy ") -> "deploy"
    this == "live e2e selftest" || this == "live selftest" -> "live selftest"
    else -> this
}

private fun TestRunSummaryDto.resolves(active: TestRunSummaryDto) =
    status != OpsStatusDto.WIP &&
        (label == active.label || label.lifecycleLabel() == active.label.lifecycleLabel()) &&
        (active.timestampMs == null || (timestampMs ?: 0L) >= active.timestampMs!!)

private fun TestRunSummaryDto.historyKey() = "${label.lifecycleLabel()}:${timestampMs ?: url ?: detail ?: status.name}"
