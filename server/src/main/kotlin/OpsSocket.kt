package net.sdfgsdfg

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.sdfgsdfg.data.model.IssueSummaryDto
import net.sdfgsdfg.data.model.OpsIssuePatchDto
import net.sdfgsdfg.data.model.OpsRunEventDto
import net.sdfgsdfg.data.model.OpsSessionCommandDto
import net.sdfgsdfg.data.model.OpsSessionEventDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSocketMessageDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsWorkspaceActionDto
import net.sdfgsdfg.data.model.OpsWorkspaceCommandDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventKindDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventStatusDto
import net.sdfgsdfg.data.model.RepoIssuePatchDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
import net.sdfgsdfg.data.model.canRunSessions
import net.sdfgsdfg.data.model.isFreshForIssuePatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

private const val opsSocketRefreshMs = 45_000L
private const val opsSocketBroadcastDebounceMs = 120L
private const val issuePatchEventLimit = 6

@OptIn(FlowPreview::class)
/** Runtime-local fanout and transient run state; separate instances must never cross-contaminate. */
internal class OpsSocketHub(
    private val sessionHandler: OpsSessionCommandHandler = OpsSessionService(),
) : AutoCloseable {
    private data class Client(
        val session: DefaultWebSocketServerSession,
        val principal: OpsSocketPrincipal,
        val sendLock: Mutex = Mutex(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = CopyOnWriteArraySet<Client>()
    private val loopLock = Mutex()
    private val summaryBroadcastRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val issuePatchBroadcastRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val activeRuns = ConcurrentHashMap<String, OpsRunEventDto>()
    private val workspaceJobs = ConcurrentHashMap<String, Job>()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val closed = AtomicBoolean()
    private var loopJob: Job? = null
    private var summaryProvider: (() -> OpsSummaryDto)? = null
    private var workspaceHandler: OpsWorkspaceCommandHandler? = null

    init {
        scope.launch {
            summaryBroadcastRequests
                .debounce(opsSocketBroadcastDebounceMs)
                .collect {
                    if (clients.isNotEmpty()) broadcastSummaryNow()
                }
        }
        scope.launch {
            issuePatchBroadcastRequests
                .debounce(opsSocketBroadcastDebounceMs)
                .collect {
                    if (clients.isNotEmpty()) broadcastIssuePatchNow()
                }
        }
    }

    val clientCount: Int
        get() = clients.size

    fun configure(provider: () -> OpsSummaryDto, workspace: OpsWorkspaceCommandHandler? = null) {
        summaryProvider = provider
        workspaceHandler = workspace
    }

    suspend fun serve(session: DefaultWebSocketServerSession, principal: OpsSocketPrincipal) {
        val client = Client(session, principal)
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
                    "workspace_command" -> decoded.workspaceCommand?.let { dispatchWorkspace(client, it) }
                        ?: client.send(OpsSocketMessageDto("error", message = "Workspace command is missing"))
                    "session_command" -> decoded.sessionCommand?.let { dispatchSession(client, it) }
                        ?: client.send(OpsSocketMessageDto("error", message = "Session command is missing"))
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
        if (clients.isNotEmpty()) summaryBroadcastRequests.tryEmit(Unit)
    }

    fun broadcastIssuePatch() {
        if (clients.isNotEmpty()) issuePatchBroadcastRequests.tryEmit(Unit)
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        clients.clear()
        activeRuns.clear()
        workspaceJobs.clear()
        sessionLocks.clear()
        (sessionHandler as? AutoCloseable)?.close()
        summaryProvider = null
        workspaceHandler = null
        loopJob = null
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
        val summary = currentSummary() ?: return
        broadcastNow(summary.summaryMessage())
    }

    private suspend fun broadcastIssuePatchNow() {
        val summary = currentSummary() ?: return
        broadcastNow(summary.issuePatchMessage())
    }

    private suspend fun broadcastNow(message: OpsSocketMessageDto) {
        clients.forEach { client ->
            runCatching { client.send(message) }.onFailure { clients -= client }
        }
        stopLoopIfIdle()
    }

    private suspend fun sendSummary(client: Client) {
        currentSummary()?.let {
            runCatching { client.send(it.summaryMessage()) }.onFailure { clients -= client }
        }
    }

    private suspend fun dispatchWorkspace(client: Client, command: OpsWorkspaceCommandDto) {
        if (!client.principal.viewer.canRunSessions()) {
            client.send(command.errorEvent("Workspace access requires the owner session").message())
            return
        }
        val handler = workspaceHandler ?: run {
            client.send(command.errorEvent("Workspace service is unavailable").message())
            return
        }
        val key = "${client.principal.viewer.userId}:${if (command.action == OpsWorkspaceActionDto.SELECT_REPOSITORY) "sync" else "list"}"
        workspaceJobs.remove(key)?.cancel()
        val job = scope.launch {
            handler.handle(client.principal, command) { event ->
                runCatching { client.send(event.message()) }
            }
        }
        workspaceJobs[key] = job
        job.invokeOnCompletion { workspaceJobs.remove(key, job) }
    }

    private suspend fun dispatchSession(client: Client, command: OpsSessionCommandDto) {
        if (!client.principal.viewer.canRunSessions()) {
            client.send(command.errorEvent("Session access requires the owner session").message())
            return
        }
        val viewerId = client.principal.viewer.userId
        scope.launch {
            sessionLocks.computeIfAbsent(viewerId) { Mutex() }.withLock {
                sessionHandler.handle(client.principal, command) { event ->
                    if (event.replay || event.sequence == null) client.send(event.message())
                    else broadcastViewer(viewerId, event.message())
                }
            }
        }
    }

    private suspend fun broadcastViewer(viewerId: String, message: OpsSocketMessageDto) {
        clients.filter { it.principal.viewer.userId == viewerId }.forEach { client ->
            runCatching { client.send(message) }.onFailure { clients -= client }
        }
    }

    private suspend fun currentSummary() = withContext(Dispatchers.IO) { summaryProvider?.invoke() }

    private suspend fun Client.send(message: OpsSocketMessageDto) = sendLock.withLock {
        session.send(json.encodeToString(message))
    }
}

private fun OpsWorkspaceCommandDto.errorEvent(message: String) = OpsWorkspaceEventDto(
    requestId = requestId,
    kind = if (action == OpsWorkspaceActionDto.LIST_REPOSITORIES) OpsWorkspaceEventKindDto.REPOSITORIES else OpsWorkspaceEventKindDto.SYNC,
    status = OpsWorkspaceEventStatusDto.ERROR,
    message = message,
    repositoryId = repositoryId,
)

private fun OpsSessionCommandDto.errorEvent(message: String) = OpsSessionEventDto(
    requestId = requestId,
    kind = net.sdfgsdfg.data.model.OpsSessionEventKindDto.ERROR,
    runtimeId = runtimeId,
    sessionId = sessionId,
    workspaceId = workspaceId,
    agent = agent,
    channel = net.sdfgsdfg.data.model.OpsSessionChannelDto.SYSTEM,
    text = message,
)

private fun OpsWorkspaceEventDto.message() =
    OpsSocketMessageDto("workspace_event", serverTimestamp = System.currentTimeMillis(), workspaceEvent = this)

private fun OpsSessionEventDto.message() =
    OpsSocketMessageDto("session_event", serverTimestamp = System.currentTimeMillis(), sessionEvent = this)

private fun OpsSummaryDto.summaryMessage() =
    OpsSocketMessageDto("summary", serverTimestamp = System.currentTimeMillis(), summary = this)

private fun OpsSummaryDto.issuePatchMessage() =
    OpsSocketMessageDto("issue_patch", serverTimestamp = System.currentTimeMillis(), issuePatch = issuePatch())

internal fun OpsSummaryDto.issuePatch() = OpsIssuePatchDto(
    generatedAtMs = generatedAtMs,
    repos = repos.map { RepoIssuePatchDto(it.id, it.issues.issuePatch(generatedAtMs)) },
)

private fun IssueSummaryDto.issuePatch(nowMs: Long) = copy(
    items = items.map { if (it.isFreshForIssuePatch(nowMs)) it else it.copy(description = "", notes = "") },
    events = events.sortedByDescending { it.tsMs ?: 0L }.take(issuePatchEventLimit),
)

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
