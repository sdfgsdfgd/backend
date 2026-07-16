package net.sdfgsdfg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.sdfgsdfg.data.model.OpsAgentDto
import net.sdfgsdfg.data.model.OpsArcanaModeDto
import net.sdfgsdfg.data.model.OpsPacingProfileDto
import net.sdfgsdfg.data.model.OpsSessionActionDto
import net.sdfgsdfg.data.model.OpsSessionChannelDto
import net.sdfgsdfg.data.model.OpsSessionCommandDto
import net.sdfgsdfg.data.model.OpsSessionEventDto
import net.sdfgsdfg.data.model.OpsSessionEventKindDto
import net.sdfgsdfg.data.model.OpsSessionStateDto
import net.sdfgsdfg.data.model.OpsSessionSummaryDto
import net.sdfgsdfg.data.model.OpsStructuredEventDto
import net.sdfgsdfg.data.model.isActiveRuntime
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest

private const val sessionReplayLimit = 4_000
private const val sessionInputLimit = 100_000
private const val terminalRuntimeReplayGraceMs = 15 * 60_000L
private const val arcanaEventPrefix = "@@ARCANA_EVENT_V1@@"

internal fun interface OpsSessionCommandHandler {
    suspend fun handle(
        principal: OpsSocketPrincipal,
        command: OpsSessionCommandDto,
        emit: suspend (OpsSessionEventDto) -> Unit,
    )
}

internal class OpsSessionService(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    adapters: Map<OpsAgentDto, OpsAgentAdapter>? = null,
    private val pacing: suspend () -> OpsPacingProfileDto = { error("Provider pacing is unavailable") },
    private val terminalReplayGraceMs: Long = terminalRuntimeReplayGraceMs,
) : OpsSessionCommandHandler, AutoCloseable {
    private inner class Runtime(
        val id: String,
        val viewerId: String,
        val workspaceId: String,
        val workspace: File,
        val repositoryId: Long?,
        val workspaceName: String,
        val agent: OpsAgentDto,
        nativeSessionId: String?,
        val title: String,
        val request: OpsSessionCommandDto,
        val emit: suspend (OpsSessionEventDto) -> Unit,
    ) {
        private val eventLock = Mutex()
        private val commandLock = Mutex()
        private val replayExpiryScheduled = AtomicBoolean()
        private val ledger = ArrayDeque<OpsSessionEventDto>()
        private var sequence = 0L
        @Volatile var sessionId: String? = nativeSessionId
        @Volatile var state = OpsSessionStateDto.STARTING
        @Volatile var updatedAtMs = System.currentTimeMillis()
        @Volatile var process: OpsAgentProcess? = null
        @Volatile var changesKnown = false
        @Volatile var hasChanges = false
        private val processReady = CompletableDeferred<OpsAgentProcess>()
        val requestId get() = request.requestId

        fun attach(process: OpsAgentProcess) {
            this.process = process
            processReady.complete(process)
        }

        fun attachFailure(error: Throwable) {
            processReady.completeExceptionally(error)
        }

        private suspend fun target() = withTimeout(30_000) { processReady.await() }

        suspend fun lifecycle(
            next: OpsSessionStateDto,
            nativeSessionId: String? = null,
            text: String? = null,
            exitCode: Int? = null,
        ) {
            if (!nativeSessionId.isNullOrBlank()) sessionId = nativeSessionId
            if (state !in setOf(OpsSessionStateDto.INTERRUPTED, OpsSessionStateDto.STOPPED) && (state != OpsSessionStateDto.CONCLUDED || next == OpsSessionStateDto.FAILED)) state = next
            record(OpsSessionEventKindDto.LIFECYCLE, OpsSessionChannelDto.SYSTEM, text, exitCode)
            scheduleReplayExpiry()
        }

        suspend fun stream(channel: OpsSessionChannelDto, text: String) {
            if (text.isEmpty()) return
            record(OpsSessionEventKindDto.STREAM, channel, text)
        }

        suspend fun structured(event: OpsStructuredEventDto) {
            if (event.type == "session_state") {
                event.payload.text("session_id")?.let { sessionId = it }
                event.payload.boolean("changes_known")?.let { changesKnown = it }
                event.payload.boolean("has_changes")?.let { hasChanges = it }
                state = arcanaSessionState(event.payload.text("status"), active = true)
            }
            record(OpsSessionEventKindDto.STRUCTURED, OpsSessionChannelDto.SYSTEM, null, structured = event)
            scheduleReplayExpiry()
        }

        suspend fun fail(text: String) {
            state = OpsSessionStateDto.FAILED
            record(OpsSessionEventKindDto.ERROR, OpsSessionChannelDto.STDERR, text)
            scheduleReplayExpiry()
        }

        suspend fun input(text: String) = commandLock.withLock {
            val target = target()
            stream(OpsSessionChannelDto.STDIN, text)
            target.input(text)
        }

        suspend fun interrupt() = commandLock.withLock {
            target().interrupt()
        }

        suspend fun stop() = commandLock.withLock {
            target().stop()
        }

        suspend fun replay(after: Long, target: suspend (OpsSessionEventDto) -> Unit) = eventLock.withLock {
            val first = ledger.firstOrNull()?.sequence ?: sequence + 1
            if (after < first - 1) {
                target(
                    OpsSessionEventDto(
                        kind = OpsSessionEventKindDto.ERROR,
                        runtimeId = id,
                        sessionId = sessionId,
                        workspaceId = workspaceId,
                        agent = agent,
                        state = state,
                        channel = OpsSessionChannelDto.SYSTEM,
                        text = "Replay starts at sequence $first; older output expired",
                        timestampMs = System.currentTimeMillis(),
                        replay = true,
                    ),
                )
            }
            ledger.filter { (it.sequence ?: 0) > after }.forEach { target(it.copy(replay = true)) }
        }

        fun summary() = OpsSessionSummaryDto(
            sessionId = sessionId ?: id,
            agent = agent,
            title = title,
            updatedAtMs = updatedAtMs,
            workspaceId = workspaceId,
            repositoryId = repositoryId,
            workspaceName = workspaceName,
            runtimeId = id,
            state = state,
            detail = if (state.isActiveRuntime) "live" else "retained",
            changesKnown = changesKnown,
            hasChanges = hasChanges,
        )

        private fun scheduleReplayExpiry() {
            if (state.isActiveRuntime || !replayExpiryScheduled.compareAndSet(false, true)) return
            scope.launch {
                delay(terminalReplayGraceMs.coerceAtLeast(0L))
                if (!state.isActiveRuntime) {
                    runtimes.remove(id, this@Runtime)
                } else {
                    replayExpiryScheduled.set(false)
                }
            }
        }

        private suspend fun record(
            kind: OpsSessionEventKindDto,
            channel: OpsSessionChannelDto,
            text: String?,
            exitCode: Int? = null,
            structured: OpsStructuredEventDto? = null,
        ) = eventLock.withLock {
            updatedAtMs = System.currentTimeMillis()
            val event = OpsSessionEventDto(
                requestId = requestId,
                kind = kind,
                runtimeId = id,
                sessionId = sessionId,
                workspaceId = workspaceId,
                agent = agent,
                sequence = ++sequence,
                timestampMs = updatedAtMs,
                state = state,
                channel = channel,
                text = text,
                structured = structured,
                exitCode = exitCode,
            )
            ledger.addLast(event)
            while (ledger.size > sessionReplayLimit) ledger.removeFirst()
            emit(event)
        }
    }

    private val closed = AtomicBoolean()
    private val runtimes = ConcurrentHashMap<String, Runtime>()
    private val adapters = adapters ?: mapOf(
        OpsAgentDto.ARCANA to ArcanaAgentAdapter(scope),
        OpsAgentDto.CODEX to CodexAgentAdapter(scope),
    )

    override suspend fun handle(
        principal: OpsSocketPrincipal,
        command: OpsSessionCommandDto,
        emit: suspend (OpsSessionEventDto) -> Unit,
    ) {
        runCatching {
            when (command.action) {
                OpsSessionActionDto.LIST_SESSIONS -> if (command.workspaceId == null) listActive(principal, command, emit) else list(principal, command, emit)
                OpsSessionActionDto.CREATE_SESSION -> start(principal, command, emit, resume = false)
                OpsSessionActionDto.RESUME_SESSION -> start(principal, command, emit, resume = true)
                OpsSessionActionDto.ATTACH_SESSION -> runtime(principal, command).replay(command.afterSequence ?: 0, emit)
                OpsSessionActionDto.INPUT -> {
                    val text = command.text ?: error("Session input is missing")
                    require(text.length <= sessionInputLimit) { "Session input exceeds $sessionInputLimit characters" }
                    runtime(principal, command).input(text)
                }
                OpsSessionActionDto.INTERRUPT -> runtime(principal, command).interrupt()
                OpsSessionActionDto.STOP -> runtime(principal, command).stop()
                OpsSessionActionDto.PACING_PROFILE -> emit(
                    OpsSessionEventDto(
                        requestId = command.requestId,
                        kind = OpsSessionEventKindDto.PACING_PROFILE,
                        agent = OpsAgentDto.ARCANA,
                        pacing = pacing(),
                    ),
                )
            }
        }.onFailure { error -> emit(command.error(error.safeSessionMessage())) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { runBlocking { withTimeout(5_000) { runtimes.values.forEach { runCatching { it.process?.stop() } } } } }
        scope.cancel()
        runtimes.clear()
    }

    private suspend fun list(
        principal: OpsSocketPrincipal,
        command: OpsSessionCommandDto,
        emit: suspend (OpsSessionEventDto) -> Unit,
    ) {
        val (selected, agent) = workspace(principal, command)
        val persisted = adapter(agent).list(File(selected.getPath())).map { it.dto(agent, selected) }
        val live = runtimes.values
            .filter {
                it.viewerId == principal.viewer.userId && it.workspaceId == command.workspaceId &&
                    it.agent == agent && it.state.isActiveRuntime
            }
            .map(Runtime::summary)
        emit(
            OpsSessionEventDto(
                requestId = command.requestId,
                kind = OpsSessionEventKindDto.SESSIONS,
                workspaceId = command.workspaceId,
                agent = agent,
                sessions = (live + persisted)
                    .distinctBy(OpsSessionSummaryDto::sessionId)
                    .sortedByDescending(OpsSessionSummaryDto::updatedAtMs),
            ),
        )
    }

    private suspend fun listActive(
        principal: OpsSocketPrincipal,
        command: OpsSessionCommandDto,
        emit: suspend (OpsSessionEventDto) -> Unit,
    ) = emit(
        OpsSessionEventDto(
            requestId = command.requestId,
            kind = OpsSessionEventKindDto.SESSIONS,
            sessions = runtimes.values
                .filter { it.viewerId == principal.viewer.userId && it.state.isActiveRuntime }
                .map(Runtime::summary)
                .sortedByDescending(OpsSessionSummaryDto::updatedAtMs),
        ),
    )

    private suspend fun start(
        principal: OpsSocketPrincipal,
        command: OpsSessionCommandDto,
        emit: suspend (OpsSessionEventDto) -> Unit,
        resume: Boolean,
    ) {
        check(!closed.get()) { "Session service is closed" }
        runtimes.values.firstOrNull { it.viewerId == principal.viewer.userId && it.requestId == command.requestId }?.let { existing ->
            require(existing.request == command) { "Session request id was reused with different content" }
            existing.replay(0, emit)
            return
        }
        val (selected, agent) = workspace(principal, command)
        val workspace = File(selected.getPath()).canonicalFile
        val initialInput = command.text.orEmpty()
        if (!resume) require(initialInput.isNotBlank()) { "Describe the first turn before creating a session" }
        val nativeSessionId = command.sessionId?.takeIf(String::isNotBlank)
        val resumedSession = if (resume) {
            require(nativeSessionId != null) { "Session id is missing" }
            adapter(agent).list(workspace).firstOrNull { it.sessionId == nativeSessionId }
                ?: error("Session is not available in this workspace")
        } else null
        val runtime = Runtime(
            id = UUID.randomUUID().toString(),
            viewerId = principal.viewer.userId,
            workspaceId = command.workspaceId!!,
            workspace = workspace,
            repositoryId = selected.repositoryId,
            workspaceName = selected.name,
            agent = agent,
            nativeSessionId = nativeSessionId,
            title = initialInput.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank {
                resumedSession?.title ?: "Resume ${agent.name.lowercase()}"
            },
            request = command,
            emit = emit,
        )
        runtimes[runtime.id] = runtime
        runtime.lifecycle(OpsSessionStateDto.STARTING, text = if (resume) "Resuming ${agent.name.lowercase()} session…" else "Creating ${agent.name.lowercase()} session…")
        scope.launch {
            runCatching {
                adapter(agent).launch(
                    OpsAgentLaunch(
                        workspace, nativeSessionId, initialInput, command.model, command.noPace,
                        command.paceMinSeconds, command.paceMaxSeconds,
                        command.auto == true, command.indexSync == true, command.arcanaMode ?: OpsArcanaModeDto.WORKSPACE,
                    ),
                    runtime::stream,
                    runtime::structured,
                    runtime::lifecycle,
                )
            }.onSuccess { process ->
                runtime.attach(process)
           }.onFailure { error ->
                runtime.attachFailure(error)
               runtime.fail(error.safeSessionMessage())
            }
        }
    }

    private fun runtime(principal: OpsSocketPrincipal, command: OpsSessionCommandDto): Runtime {
        val id = command.runtimeId ?: error("Runtime id is missing")
        return runtimes[id]?.takeIf { it.viewerId == principal.viewer.userId }
            ?: error("Session runtime is not available")
    }

    private fun workspace(principal: OpsSocketPrincipal, command: OpsSessionCommandDto): Pair<WorkspaceTracker.WorkspaceInfo, OpsAgentDto> {
        val selected = WorkspaceTracker.getCurrentWorkspace(principal.viewer.userId)
            ?: error("Select and synchronize a repository first")
        require(command.workspaceId == selected.workspaceId) { "Workspace selection is stale" }
        val path = File(selected.getPath()).canonicalFile
        require(path.isDirectory) { "Workspace repository is missing" }
        return selected to (command.agent ?: error("Session agent is missing"))
    }

    private fun adapter(agent: OpsAgentDto) = adapters[agent] ?: error("${agent.name.lowercase()} adapter is unavailable")
}

internal data class OpsNativeSession(
    val sessionId: String,
    val title: String,
    val updatedAtMs: Long,
    val detail: String? = null,
    val state: OpsSessionStateDto = OpsSessionStateDto.ONGOING,
    val changesKnown: Boolean = false,
    val hasChanges: Boolean = false,
) {
    fun dto(agent: OpsAgentDto, workspace: WorkspaceTracker.WorkspaceInfo) = OpsSessionSummaryDto(
        sessionId, agent, title, updatedAtMs, state = state, detail = detail,
        workspaceId = workspace.workspaceId, repositoryId = workspace.repositoryId, workspaceName = workspace.name,
        changesKnown = changesKnown, hasChanges = hasChanges,
    )
}

internal data class OpsAgentLaunch(
    val workspace: File,
    val resumeSessionId: String?,
    val initialInput: String,
    val model: String?,
    val noPace: Boolean?,
    val paceMinSeconds: Double?,
    val paceMaxSeconds: Double?,
    val auto: Boolean,
    val indexSync: Boolean,
    val mode: OpsArcanaModeDto,
)

internal interface OpsAgentProcess {
    suspend fun input(text: String)
    suspend fun interrupt()
    suspend fun stop()
}

internal interface OpsAgentAdapter {
    suspend fun list(workspace: File): List<OpsNativeSession>

    suspend fun launch(
        request: OpsAgentLaunch,
        output: suspend (OpsSessionChannelDto, String) -> Unit,
        structured: suspend (OpsStructuredEventDto) -> Unit,
        lifecycle: suspend (OpsSessionStateDto, String?, String?, Int?) -> Unit,
    ): OpsAgentProcess
}

private class ArcanaAgentAdapter(private val scope: CoroutineScope) : OpsAgentAdapter {
    private val json = Json { ignoreUnknownKeys = true }
    private val models = setOf("5.6-pro", "deepseek-expert", "deepseek-instant")
    private val home = File(System.getenv("ARCANA_HOME") ?: "${System.getProperty("user.home")}/Desktop/py/arcana")
    private val python get() = File(home, ".venv/bin/python").takeIf(File::canExecute)?.absolutePath ?: "python3"
    private val entry get() = File(home, "_0.py")

    override suspend fun list(workspace: File): List<OpsNativeSession> = withContext(Dispatchers.IO) {
        check(entry.isFile) { "Arcana is not installed at ${home.path}" }
        val process = ProcessBuilder(python, entry.absolutePath, "--path", workspace.path, "--list-sessions")
            .directory(home)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Arcana session listing timed out")
        }
        check(process.exitValue() == 0) { stderr.ifBlank { "Arcana session listing failed" } }
        (json.parseToJsonElement(stdout.trim()) as? JsonArray).orEmpty().mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            val id = value.text("session_id") ?: return@mapNotNull null
            OpsNativeSession(
                sessionId = id,
                title = value.text("title") ?: id,
                updatedAtMs = runCatching { Instant.parse(value.text("updated_at")).toEpochMilli() }.getOrDefault(0),
                detail = listOfNotNull(value.text("age"), value["rounds"]?.jsonPrimitive?.contentOrNull?.let { "$it rounds" }).joinToString(" · "),
                state = arcanaSessionState(value.text("status"), active = false),
                changesKnown = value.boolean("changes_known") == true,
                hasChanges = value.boolean("has_changes") == true,
            )
        }
    }

    override suspend fun launch(
        request: OpsAgentLaunch,
        output: suspend (OpsSessionChannelDto, String) -> Unit,
        structured: suspend (OpsStructuredEventDto) -> Unit,
        lifecycle: suspend (OpsSessionStateDto, String?, String?, Int?) -> Unit,
    ): OpsAgentProcess = withContext(Dispatchers.IO) {
        check(entry.isFile) { "Arcana is not installed at ${home.path}" }
        val args = buildList {
            add(python)
            add(entry.absolutePath)
            addAll(listOf("--path", request.workspace.path, "--sandbox", if (request.auto) "--auto" else "--manual", "--stdio"))
            if (!request.indexSync) add("--no-index-sync")
            when (request.mode) {
                OpsArcanaModeDto.WORKSPACE -> Unit
                OpsArcanaModeDto.ISSUES -> add("--issues")
                OpsArcanaModeDto.GENERAL -> add("--general")
            }
            request.model?.let {
                require(it in models) { "Unsupported Arcana model: $it" }
                addAll(listOf("--model", it))
            }
            if (request.noPace == true) add("--no-pace")
            request.paceMinSeconds?.let { addAll(listOf("--pace-min-seconds", it.toString())) }
            request.paceMaxSeconds?.let { addAll(listOf("--pace-max-seconds", it.toString())) }
            request.resumeSessionId?.let { addAll(listOf("--resume-session", it)) }
            request.initialInput.takeIf(String::isNotBlank)?.let { addAll(listOf("--query", it)) }
        }
        val eventToken = UUID.randomUUID().toString().replace("-", "")
        val process = ProcessBuilder(args)
            .directory(home)
            .apply {
                environment()["PYTHONUNBUFFERED"] = "1"
                environment()["ARCANA_EVENT_TOKEN"] = eventToken
            }
            .start()
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        val stopped = AtomicBoolean()
        val stdout = scope.launch { readCharacters(process.inputStream) { output(OpsSessionChannelDto.STDOUT, it) } }
        val stderr = scope.launch { readArcanaStderr(process.errorStream, eventToken, output, structured) }
        scope.launch {
            val exit = withContext(Dispatchers.IO) { process.waitFor() }
            joinAll(stdout, stderr)
            lifecycle(
                if (stopped.get()) OpsSessionStateDto.STOPPED else if (exit == 0) OpsSessionStateDto.EXITED else OpsSessionStateDto.FAILED,
                request.resumeSessionId,
                "Arcana exited with code $exit",
                exit,
            )
        }
        lifecycle(OpsSessionStateDto.RUNNING, request.resumeSessionId, "Arcana process attached", null)
        StreamProcess(process, writer, stopped, lifecycle)
    }
}

private class StreamProcess(
    private val process: Process,
    private val writer: BufferedWriter,
    private val stopped: AtomicBoolean,
    private val lifecycle: suspend (OpsSessionStateDto, String?, String?, Int?) -> Unit,
) : OpsAgentProcess {
    private val writeLock = Mutex()

    override suspend fun input(text: String) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            writer.write(buildJsonObject {
                put("type", "arcana.input.v1")
                put("text", text)
            }.toString())
            writer.newLine()
            writer.flush()
        }
    }

    override suspend fun interrupt() {
        val interrupted = withContext(Dispatchers.IO) {
            runCatching { ProcessBuilder("kill", "-INT", process.pid().toString()).start().waitFor() == 0 }.getOrDefault(false)
        }
        if (!interrupted) process.destroy()
        lifecycle(OpsSessionStateDto.INTERRUPTED, null, "Interrupt sent", null)
    }

    override suspend fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            runCatching { writer.close() }
            runCatching { ProcessBuilder("kill", "-INT", process.pid().toString()).start().waitFor() }
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

internal class CodexAgentAdapter(
    private val scope: CoroutineScope,
    private val command: List<String> = listOf(System.getenv("CODEX_BIN") ?: "codex"),
) : OpsAgentAdapter {
    override suspend fun list(workspace: File): List<OpsNativeSession> {
        val stderr = StringBuilder()
        val client = CodexAppClient(scope, command, onStderr = { stderr.append(it) })
        return try {
            client.initialize()
            val result = client.request(
                "thread/list",
                buildJsonObject {
                    put("cwd", workspace.path)
                    put("limit", 50)
                    put("sortKey", "updated_at")
                    put("sortDirection", "desc")
                    put("sourceKinds", buildJsonArray {
                        listOf("cli", "vscode", "exec", "appServer", "subAgent", "subAgentReview", "subAgentCompact", "subAgentThreadSpawn", "subAgentOther", "unknown")
                            .forEach { add(JsonPrimitive(it)) }
                    })
                },
            )
            result["data"]?.jsonArray.orEmpty().mapNotNull { item ->
                val value = item as? JsonObject ?: return@mapNotNull null
                val id = value.text("id") ?: return@mapNotNull null
                OpsNativeSession(
                    sessionId = id,
                    title = value.text("name") ?: value.text("preview")?.lineSequence()?.firstOrNull()?.take(160) ?: id,
                    updatedAtMs = (value["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0) * 1_000,
                    detail = value.text("modelProvider") ?: "Codex",
                )
            }
        } catch (error: Throwable) {
            error(stderr.toString().trim().ifBlank { error.message ?: "Codex session listing failed" })
        } finally {
            client.close()
        }
    }

    override suspend fun launch(
        request: OpsAgentLaunch,
        output: suspend (OpsSessionChannelDto, String) -> Unit,
        structured: suspend (OpsStructuredEventDto) -> Unit,
        lifecycle: suspend (OpsSessionStateDto, String?, String?, Int?) -> Unit,
    ): OpsAgentProcess {
        val processRef = AtomicReference<CodexAgentProcess?>()
        val startupLock = Any()
        val startupMessages = ArrayDeque<CodexInboundMessage>()
        val protocolFailed = AtomicBoolean()
        lateinit var client: CodexAppClient
        suspend fun dispatch(message: CodexInboundMessage) {
            val process = synchronized(startupLock) {
                processRef.get() ?: run {
                    startupMessages.addLast(message)
                    null
                }
            }
            if (process != null) {
                if (message.request) process.request(message.message) else process.notification(message.message)
            }
        }
        client = CodexAppClient(
            scope = scope,
            command = command,
            onNotification = { dispatch(CodexInboundMessage(request = false, message = it)) },
            onRequest = { dispatch(CodexInboundMessage(request = true, message = it)) },
            onStderr = { output(OpsSessionChannelDto.STDERR, it) },
            onProtocolFailure = { message ->
                protocolFailed.set(true)
                lifecycle(OpsSessionStateDto.FAILED, processRef.get()?.threadId, message, null)
            },
            onExit = { code ->
                lifecycle(
                    if (protocolFailed.get() || code != 0) OpsSessionStateDto.FAILED else OpsSessionStateDto.EXITED,
                    processRef.get()?.threadId,
                    "Codex app-server exited with code $code",
                    code,
                )
            },
        )
        try {
            client.initialize()
            val method = if (request.resumeSessionId == null) "thread/start" else "thread/resume"
            val result = client.request(
                method,
                buildJsonObject {
                    put("cwd", request.workspace.path)
                    put("approvalPolicy", "on-request")
                    put("approvalsReviewer", "user")
                    put("sandbox", "workspace-write")
                    request.resumeSessionId?.let { put("threadId", it) }
                },
            )
            val threadId = result["thread"]?.jsonObject?.text("id") ?: error("Codex did not return a thread id")
            val history = request.resumeSessionId?.let {
                client.request("thread/read", buildJsonObject {
                    put("threadId", threadId)
                    put("includeTurns", true)
                })["thread"] as? JsonObject
            }
            history?.let { emitCodexHistory(it, structured) }
            val process = CodexAgentProcess(scope, client, threadId, output, structured, lifecycle)
            val buffered = synchronized(startupLock) {
                processRef.set(process)
                startupMessages.toList().also { startupMessages.clear() }
            }
            buffered.forEach { message ->
                if (message.request) process.request(message.message) else process.notification(message.message)
            }
            lifecycle(OpsSessionStateDto.READY, threadId, "Codex thread attached", null)
            request.initialInput.takeIf(String::isNotBlank)?.let { process.input(it) }
            return process
        } catch (error: Throwable) {
            client.close()
            throw error
        }
    }
}

private data class CodexInboundMessage(val request: Boolean, val message: JsonObject)

private suspend fun emitCodexHistory(
    thread: JsonObject,
    structured: suspend (OpsStructuredEventDto) -> Unit,
) {
    val threadId = thread.text("id") ?: return
    thread["turns"]?.jsonArray.orEmpty().forEach { turnElement ->
        val turn = turnElement as? JsonObject ?: return@forEach
        val turnId = turn.text("id") ?: return@forEach
        turn["items"]?.jsonArray.orEmpty().forEach { item ->
            structured(
                OpsStructuredEventDto(
                    type = "item/completed",
                    phase = "codex",
                    schema = "codex.app-server.v2",
                    payload = buildJsonObject {
                        put("threadId", threadId)
                        put("turnId", turnId)
                        put("turn_status", turn.text("status").orEmpty())
                        put("history", true)
                        put("item", item)
                    },
                ),
            )
        }
    }
}

internal class CodexAgentProcess(
    private val scope: CoroutineScope,
    private val client: CodexAppClient,
    val threadId: String,
    private val output: suspend (OpsSessionChannelDto, String) -> Unit,
    private val structured: suspend (OpsStructuredEventDto) -> Unit,
    private val lifecycle: suspend (OpsSessionStateDto, String?, String?, Int?) -> Unit,
) : OpsAgentProcess {
    private val activeTurn = AtomicReference<String?>()
    private val pendingRequest = AtomicReference<CodexServerRequest?>()
    private val pendingTimeout = AtomicReference<Job?>()

    override suspend fun input(text: String) {
        val pending = pendingRequest.get()
        if (pending != null) {
            val response = pending.response(text)
            if (response == null) {
                output(OpsSessionChannelDto.STDERR, pending.invalidResponse(text))
                emitInputRequest(pending)
                return
            }
            if (!pendingRequest.compareAndSet(pending, null)) return
            pendingTimeout.getAndSet(null)?.cancel()
            try {
                client.respond(pending.id, response)
                structured(
                    OpsStructuredEventDto(
                        type = "input_resolved",
                        phase = "codex",
                        schema = "codex.app-server.v2",
                        payload = buildJsonObject {
                            put("request_id", pending.id)
                            put("method", pending.method)
                            put("response", response)
                        },
                    ),
                )
            } catch (error: Throwable) {
                pendingRequest.compareAndSet(null, pending)
                emitInputRequest(pending)
                throw error
            }
            return
        }
        val input = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        val active = activeTurn.get()
        val result = client.request(
            if (active == null) "turn/start" else "turn/steer",
            buildJsonObject {
                put("threadId", threadId)
                put("input", input)
                active?.let { put("expectedTurnId", it) }
            },
        )
        if (active == null) result["turn"]?.jsonObject?.text("id")?.let(activeTurn::set)
    }

    override suspend fun interrupt() {
        val turn = activeTurn.get() ?: return
        client.request("turn/interrupt", buildJsonObject { put("threadId", threadId); put("turnId", turn) })
        activeTurn.compareAndSet(turn, null)
        lifecycle(OpsSessionStateDto.READY, threadId, "Codex turn interrupted", null)
    }

    override suspend fun stop() {
        client.close()
        lifecycle(OpsSessionStateDto.STOPPED, threadId, "Codex app-server stopped", null)
    }

    suspend fun notification(message: JsonObject) {
        val method = message.text("method") ?: return
        val params = message["params"] as? JsonObject ?: return
        structured(OpsStructuredEventDto(type = method, phase = "codex", schema = "codex.app-server.v2", payload = params))
        when (method) {
            "turn/started" -> {
                val id = params["turn"]?.jsonObject?.text("id")
                if (id != null) activeTurn.set(id)
                lifecycle(OpsSessionStateDto.RUNNING, threadId, "Codex turn started", null)
            }
            "turn/completed" -> {
                val turn = params["turn"] as? JsonObject
                activeTurn.set(null)
                val status = turn?.text("status")
                lifecycle(if (status == "failed") OpsSessionStateDto.FAILED else OpsSessionStateDto.READY, threadId, status?.let { "Codex turn $it" }, null)
            }
            "item/agentMessage/delta", "item/commandExecution/outputDelta" -> params.text("delta")?.let { output(OpsSessionChannelDto.STDOUT, it) }
            "error" -> {
                val error = params["error"] as? JsonObject
                output(OpsSessionChannelDto.STDERR, error?.text("message") ?: "Codex turn failed")
            }
        }
    }

    suspend fun request(message: JsonObject) {
        val request = CodexServerRequest.from(message) ?: run {
            client.respondError(message, "Malformed Codex server request")
            return
        }
        structured(OpsStructuredEventDto(type = request.method, phase = "codex", schema = "codex.app-server.v2", payload = request.params))
        if (!request.isInteractive) {
            client.respondError(message, "Codex request ${request.method} is not supported by this client")
            output(OpsSessionChannelDto.STDERR, "Unsupported Codex server request: ${request.method}\n")
            return
        }
        if (!pendingRequest.compareAndSet(null, request)) {
            client.respondError(message, "Another Codex input request is already pending")
            output(OpsSessionChannelDto.STDERR, "Codex input request rejected: another request is pending\n")
            return
        }
        emitInputRequest(request)
        request.autoResolutionMs?.takeIf { it > 0 }?.let { timeout ->
            pendingTimeout.set(scope.launch {
                delay(timeout)
                if (pendingRequest.compareAndSet(request, null)) {
                    runCatching { client.respond(request.id, request.timeoutResponse()) }
                    structured(
                        OpsStructuredEventDto(
                            type = "input_timeout",
                            phase = "codex",
                            schema = "codex.app-server.v2",
                            payload = buildJsonObject {
                                put("request_id", request.id)
                                put("method", request.method)
                                put("timeout_ms", timeout)
                            },
                        ),
                    )
                }
            })
        }
    }

    private suspend fun emitInputRequest(request: CodexServerRequest) = structured(
        OpsStructuredEventDto(
            type = "input_request",
            phase = "codex",
            schema = "trio.ops.codex-input.v1",
            payload = request.inputPayload(),
        ),
    )
}

private data class CodexServerRequest(
    val id: JsonElement,
    val method: String,
    val params: JsonObject,
) {
    val autoResolutionMs get() = params["autoResolutionMs"]?.jsonPrimitive?.longOrNull
    val isInteractive get() = method in interactiveMethods

    fun inputPayload() = buildJsonObject {
        put("kind", if (method == "item/tool/requestUserInput") "codex_user_input" else "codex_approval")
        put("prompt", prompt())
        put("request_id", id)
        put("method", method)
        put("allow_empty", false)
        options()?.let { put("options", it) }
    }

    fun response(text: String): JsonObject? {
        val input = text.trim()
        return when (method) {
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> approval(input)?.let { decision ->
                buildJsonObject { put("decision", decision) }
            }
            "item/permissions/requestApproval" -> permissionResponse(input)
            "item/tool/requestUserInput" -> userInputResponse(input)
            "mcpServer/elicitation/request" -> input.lowercase().takeIf { it in setOf("accept", "decline", "cancel") }?.let { action ->
                buildJsonObject { put("action", action) }
            }
            else -> null
        }
    }

    fun timeoutResponse() = when (method) {
        "item/tool/requestUserInput" -> buildJsonObject { put("answers", buildJsonObject {}) }
        "mcpServer/elicitation/request" -> buildJsonObject { put("action", "cancel") }
        "item/permissions/requestApproval" -> buildJsonObject { put("permissions", buildJsonObject {}) }
        else -> buildJsonObject { put("decision", "cancel") }
    }

    fun invalidResponse(text: String) = "${prompt()} Reply ${expectedResponse()} (received ${text.trim().ifBlank { "empty" }}).\n"

    private fun prompt(): String = when (method) {
        "item/commandExecution/requestApproval" -> listOfNotNull(
            "Approve command",
            params.text("command"),
            params.text("reason"),
        ).joinToString(" · ")
        "item/fileChange/requestApproval" -> listOfNotNull("Approve file changes", params.text("reason")).joinToString(" · ")
        "item/permissions/requestApproval" -> listOfNotNull("Approve additional permissions", params.text("reason")).joinToString(" · ")
        "item/tool/requestUserInput" -> params["questions"]?.jsonArray?.joinToString(" · ") { question ->
            (question as? JsonObject)?.let { "${it.text("header")}: ${it.text("question")}" }.orEmpty()
        }.orEmpty().ifBlank { "Codex needs input" }
        "mcpServer/elicitation/request" -> "MCP ${params.text("serverName").orEmpty().ifBlank { "server" }} needs a response"
        else -> "Codex needs a response"
    }

    private fun expectedResponse() = when (method) {
        "item/tool/requestUserInput" -> "with an answer${if ((params["questions"] as? JsonArray)?.size == 1) "" else " JSON object keyed by question id"}"
        "mcpServer/elicitation/request" -> "accept, decline, or cancel"
        else -> "accept, acceptForSession, decline, or cancel"
    }

    private fun options(): JsonElement? = when (method) {
        "item/tool/requestUserInput" -> params["questions"]
        "mcpServer/elicitation/request" -> buildJsonArray { listOf("accept", "decline", "cancel").forEach { add(JsonPrimitive(it)) } }
        else -> buildJsonArray { listOf("accept", "acceptForSession", "decline", "cancel").forEach { add(JsonPrimitive(it)) } }
    }

    private fun approval(input: String) = input.lowercase().let {
        when (it) {
            "accept", "allow", "yes" -> "accept"
            "acceptforsession", "accept_for_session", "always" -> "acceptForSession"
            "decline", "deny", "no" -> "decline"
            "cancel" -> "cancel"
            else -> null
        }
    }

    private fun permissionResponse(input: String): JsonObject? = when (approval(input)) {
        "accept", "acceptForSession" -> buildJsonObject {
            put("permissions", params["permissions"] ?: buildJsonObject {})
            put("scope", if (approval(input) == "acceptForSession") "session" else "turn")
        }
        "decline", "cancel" -> buildJsonObject { put("permissions", buildJsonObject {}) }
        else -> null
    }

    private fun userInputResponse(input: String): JsonObject? {
        val questions = params["questions"] as? JsonArray ?: return null
        if (questions.size == 1) {
            val id = (questions.single() as? JsonObject)?.text("id") ?: return null
            return buildJsonObject {
                put("answers", buildJsonObject {
                    put(id, buildJsonObject { put("answers", buildJsonArray { add(JsonPrimitive(input)) }) })
                })
            }
        }
        val answers = runCatching { Json.parseToJsonElement(input).jsonObject }.getOrNull() ?: return null
        return buildJsonObject {
            put("answers", buildJsonObject {
                answers.forEach { (id, value) ->
                    val values = value as? JsonArray ?: return null
                    put(id, buildJsonObject { put("answers", values) })
                }
            })
        }
    }

    companion object {
        private val interactiveMethods = setOf(
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/permissions/requestApproval",
            "item/tool/requestUserInput",
            "mcpServer/elicitation/request",
        )

        fun from(message: JsonObject): CodexServerRequest? {
            val id = message["id"] ?: return null
            val method = message.text("method") ?: return null
            val params = message["params"] as? JsonObject ?: return null
            return CodexServerRequest(id, method, params)
        }
    }
}

internal class CodexAppClient(
    scope: CoroutineScope,
    command: List<String> = listOf(System.getenv("CODEX_BIN") ?: "codex"),
    private val onNotification: suspend (JsonObject) -> Unit = {},
    private val onRequest: suspend (JsonObject) -> Unit = {},
    private val onStderr: suspend (String) -> Unit = {},
    private val onProtocolFailure: suspend (String) -> Unit = {},
    private val onExit: suspend (Int) -> Unit = {},
    private val process: Process = ProcessBuilder(command + listOf("app-server", "--stdio")).start(),
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val writeLock = Mutex()
    private val requestIds = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val closed = AtomicBoolean()
    private val protocolFailed = AtomicBoolean()
    private val stdoutJob: Job
    private val stderrJob: Job

    init {
        stdoutJob = scope.launch {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    if (message == null) {
                        failProtocol("Invalid Codex protocol output")
                    } else {
                        val id = message["id"]?.jsonPrimitive?.longOrNull
                        when {
                            message["method"] != null && message["id"] != null -> onRequest(message)
                            id != null -> pending.remove(id)?.complete(message)
                            else -> onNotification(message)
                        }
                    }
                }
            }
        }
        stderrJob = scope.launch { readCharacters(process.errorStream, onStderr) }
        scope.launch {
            val exit = withContext(Dispatchers.IO) { process.waitFor() }
            val failure = IllegalStateException("Codex app-server exited with code $exit")
            pending.values.forEach { it.completeExceptionally(failure) }
            pending.clear()
            joinAll(stdoutJob, stderrJob)
            if (!closed.get()) onExit(exit)
        }
    }

    suspend fun initialize() {
        request(
            "initialize",
            buildJsonObject {
                put("clientInfo", buildJsonObject {
                    put("name", "trio_ops_cockpit")
                    put("title", "Trio Ops Cockpit")
                    put("version", "0.1.0")
                })
            },
        )
        notify("initialized")
    }

    suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = requestIds.incrementAndGet()
        val response = CompletableDeferred<JsonObject>()
        pending[id] = response
        send(buildJsonObject { put("method", method); put("id", id); put("params", params) })
        val message = try {
            withTimeout(20_000) { response.await() }
        } finally {
            pending.remove(id)
        }
        message["error"]?.jsonObject?.let { error(it.text("message") ?: "$method failed") }
        return message["result"] as? JsonObject ?: buildJsonObject {}
    }

    private suspend fun notify(method: String) = send(buildJsonObject { put("method", method); put("params", buildJsonObject {}) })

    suspend fun respond(id: JsonElement, result: JsonObject) = send(buildJsonObject { put("id", id); put("result", result) })

    suspend fun respondError(message: JsonObject, detail: String) {
        val id = message["id"] ?: return
        send(buildJsonObject {
            put("id", id)
            put("error", buildJsonObject {
                put("code", -32602)
                put("message", detail)
            })
        })
    }

    private suspend fun send(message: JsonObject) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            writer.write(message.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private suspend fun failProtocol(message: String) {
        if (!protocolFailed.compareAndSet(false, true)) return
        val failure = IllegalStateException(message)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        onProtocolFailure(message)
        runCatching { writer.close() }
        runCatching { process.destroy() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { writer.close() }
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

private suspend fun readCharacters(stream: InputStream, emit: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
    InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
        val buffer = CharArray(2_048)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            emit(String(buffer, 0, read))
        }
    }
}

internal suspend fun readArcanaStderr(
    stream: InputStream,
    token: String,
    output: suspend (OpsSessionChannelDto, String) -> Unit,
    structured: suspend (OpsStructuredEventDto) -> Unit,
) = withContext(Dispatchers.IO) {
    val prefix = "$arcanaEventPrefix:$token:"
    val decoder = ArcanaEventDecoder()
    InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
        val buffer = CharArray(2_048)
        val line = StringBuilder()
        suspend fun flush(complete: Boolean) {
            if (line.isEmpty()) return
            val exact = line.toString()
            val candidate = if (complete) exact.dropLast(1) else exact
            if (!candidate.startsWith(prefix)) {
                output(OpsSessionChannelDto.STDERR, exact)
            } else {
                runCatching { decoder.accept(candidate.removePrefix(prefix)) }
                    .onFailure { output(OpsSessionChannelDto.STDERR, exact) }
                    .getOrNull()
                    ?.let { structured(it) }
            }
            line.clear()
        }
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            repeat(read) { index ->
                line.append(buffer[index])
                if (buffer[index] == '\n') flush(complete = true)
            }
        }
        flush(complete = false)
        decoder.incomplete().forEach { output(OpsSessionChannelDto.STDERR, "$it\n") }
    }
}

internal class ArcanaEventDecoder {
    private data class Pending(val total: Int, val digest: String, val chunks: MutableMap<Int, String> = mutableMapOf())
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = LinkedHashMap<String, Pending>()

    fun accept(frame: String): OpsStructuredEventDto? {
        val parts = frame.split(':', limit = 4)
        require(parts.size == 4) { "invalid envelope" }
        val id = parts[0].takeIf { it.length == 32 && it.all(Char::isHexDigit) } ?: error("invalid event id")
        val position = parts[1].split('/', limit = 2)
        val index = position.getOrNull(0)?.toIntOrNull() ?: error("invalid chunk index")
        val total = position.getOrNull(1)?.toIntOrNull() ?: error("invalid chunk total")
        require(index in 1..total && total in 1..4_096) { "invalid chunk bounds" }
        val digest = parts[2].takeIf { it.length == 16 && it.all(Char::isHexDigit) } ?: error("invalid checksum")
        val event = pending.getOrPut(id) {
            require(pending.size < 64) { "too many pending events" }
            Pending(total, digest)
        }
        require(event.total == total && event.digest == digest) { "inconsistent chunks" }
        event.chunks[index]?.let { require(it == parts[3]) { "conflicting duplicate chunk" } }
        event.chunks[index] = parts[3]
        if (event.chunks.size != total) return null
        pending.remove(id)
        val raw = Base64.getDecoder().decode((1..total).joinToString("") { event.chunks.getValue(it) })
        val actual = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }.take(16)
        require(actual == digest) { "checksum mismatch" }
        return json.decodeFromString(raw.toString(StandardCharsets.UTF_8))
    }

    fun incomplete() = pending.map { (id, event) ->
        "Arcana event $id truncated (${event.chunks.size}/${event.total} chunks)"
    }.also { pending.clear() }
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.boolean(key: String) = this[key]?.jsonPrimitive?.booleanOrNull

internal fun arcanaSessionState(status: String?, active: Boolean) = when (status) {
    "awaiting_acceptance" -> OpsSessionStateDto.AWAITING_ACCEPTANCE
    "concluded" -> OpsSessionStateDto.CONCLUDED
    else -> if (active) OpsSessionStateDto.RUNNING else OpsSessionStateDto.ONGOING
}

private fun OpsSessionCommandDto.error(text: String) = OpsSessionEventDto(
    requestId = requestId,
    kind = OpsSessionEventKindDto.ERROR,
    runtimeId = runtimeId,
    sessionId = sessionId,
    workspaceId = workspaceId,
    agent = agent,
    channel = OpsSessionChannelDto.SYSTEM,
    text = text,
)

private fun Throwable.safeSessionMessage() = message?.lineSequence()?.firstOrNull()?.take(500)?.ifBlank { null } ?: "Session command failed"
