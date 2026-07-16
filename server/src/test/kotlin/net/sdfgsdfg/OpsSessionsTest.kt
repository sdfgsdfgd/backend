package net.sdfgsdfg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.sdfgsdfg.data.model.OpsSessionChannelDto
import net.sdfgsdfg.data.model.OpsAgentDto
import net.sdfgsdfg.data.model.OpsPacingProfileDto
import net.sdfgsdfg.data.model.OpsPacingRangeDto
import net.sdfgsdfg.data.model.OpsSessionActionDto
import net.sdfgsdfg.data.model.OpsSessionCommandDto
import net.sdfgsdfg.data.model.OpsSessionEventDto
import net.sdfgsdfg.data.model.OpsSessionEventKindDto
import net.sdfgsdfg.data.model.OpsSessionStateDto
import net.sdfgsdfg.data.model.OpsStructuredEventDto
import net.sdfgsdfg.data.model.OpsViewerDto
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpsSessionsTest {
    private val event = OpsStructuredEventDto(
        type = "analysis",
        phase = "3_x",
        schema = "3_x",
        round = 4,
        payload = buildJsonObject {
            put("text", "cv is a compact HTML résumé generator")
            put("completed", true)
        },
    )

    @Test
    fun arcanaStatusDistinguishesLiveFromDurableContinuity() {
        assertEquals(OpsSessionStateDto.RUNNING, arcanaSessionState("running", active = true))
        assertEquals(OpsSessionStateDto.ONGOING, arcanaSessionState("running", active = false))
        assertEquals(OpsSessionStateDto.AWAITING_ACCEPTANCE, arcanaSessionState("awaiting_acceptance", active = false))
        assertEquals(OpsSessionStateDto.CONCLUDED, arcanaSessionState("concluded", active = false))
    }

    @Test
    fun pacingProfileRelaysTheInjectedServerAuthority() = runBlocking {
        val service = OpsSessionService(
            pacing = {
                OpsPacingProfileDto(
                    listOf(
                        OpsPacingRangeDto("chatgpt", 300.0, 720.0),
                        OpsPacingRangeDto("deepseek", 60.0, 120.0),
                    ),
                )
            },
        )
        val events = mutableListOf<OpsSessionEventDto>()
        try {
            service.handle(
                OpsSocketPrincipal(OpsViewerDto(userId = "pacing-viewer"), null),
                OpsSessionCommandDto("pacing", OpsSessionActionDto.PACING_PROFILE),
                events::add,
            )

            assertEquals(OpsSessionEventKindDto.PACING_PROFILE, events.single().kind)
            assertEquals(listOf("chatgpt", "deepseek"), events.single().pacing?.ranges?.map(OpsPacingRangeDto::provider))
        } finally {
            service.close()
        }
    }

    @Test
    fun interruptAndStopTargetTheExactRuntime() = runBlocking {
        val previousHome = System.getProperty("user.home")
        val home = createTempDirectory("ops-session-home").toFile()
        val launched = AtomicInteger()
        val interrupted = AtomicInteger()
        val stopped = AtomicInteger()
        val events = mutableListOf<OpsSessionEventDto>()
        val adapter = object : OpsAgentAdapter {
            override suspend fun list(workspace: java.io.File) = emptyList<OpsNativeSession>()

            override suspend fun launch(
                request: OpsAgentLaunch,
                output: suspend (OpsSessionChannelDto, String) -> Unit,
                structured: suspend (OpsStructuredEventDto) -> Unit,
                lifecycle: suspend (OpsSessionStateDto, String?, String?, Int?) -> Unit,
            ): OpsAgentProcess {
                lifecycle(OpsSessionStateDto.RUNNING, null, "attached", null)
                launched.incrementAndGet()
                return object : OpsAgentProcess {
                    override suspend fun input(text: String) = Unit
                    override suspend fun interrupt() {
                        interrupted.incrementAndGet()
                        lifecycle(OpsSessionStateDto.INTERRUPTED, null, "interrupted", null)
                        lifecycle(OpsSessionStateDto.FAILED, null, "process exited after interrupt", 143)
                    }
                    override suspend fun stop() {
                        stopped.incrementAndGet()
                        lifecycle(OpsSessionStateDto.STOPPED, null, "stopped", null)
                        lifecycle(OpsSessionStateDto.FAILED, null, "process exited after stop", 137)
                    }
                }
            }
        }
        val service = OpsSessionService(adapters = mapOf(OpsAgentDto.ARCANA to adapter))
        val viewer = OpsViewerDto(userId = "control-viewer")
        val principal = OpsSocketPrincipal(viewer, null)
        var interruptedRuntimeId: String? = null
        var stoppedRuntimeId: String? = null
        try {
            System.setProperty("user.home", home.path)
            java.io.File(home, "Desktop/server_repos/owner_repo").mkdirs()
            WorkspaceTracker.trackWorkspace(viewer.userId, "owner", "repo", "workspace", 42)
            service.handle(
                principal,
                OpsSessionCommandDto("create", OpsSessionActionDto.CREATE_SESSION, "workspace", OpsAgentDto.ARCANA, text = "hello"),
            ) {
                interruptedRuntimeId = it.runtimeId
                events += it
            }
            withTimeout(2_000) { while (launched.get() != 1) delay(10) }

            service.handle(
                principal,
                OpsSessionCommandDto("create-stop", OpsSessionActionDto.CREATE_SESSION, "workspace", OpsAgentDto.ARCANA, text = "stop fixture"),
            ) {
                stoppedRuntimeId = it.runtimeId
                events += it
            }
            withTimeout(2_000) { while (launched.get() != 2) delay(10) }

            service.handle(principal, OpsSessionCommandDto("interrupt", OpsSessionActionDto.INTERRUPT, runtimeId = interruptedRuntimeId)) {}
            assertEquals(OpsSessionStateDto.INTERRUPTED, events.last().state)
            service.handle(principal, OpsSessionCommandDto("stop", OpsSessionActionDto.STOP, runtimeId = stoppedRuntimeId)) {}

            assertEquals(1, interrupted.get())
            assertEquals(1, stopped.get())
            assertEquals(OpsSessionStateDto.STOPPED, events.last().state)
        } finally {
            service.close()
            WorkspaceTracker.removeClient(viewer.userId)
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun lostCreateAcknowledgementReplaysOneRuntimeWithoutLaunchingTwice() = runBlocking {
        val previousHome = System.getProperty("user.home")
        val home = createTempDirectory("ops-session-home").toFile()
        val launches = AtomicInteger()
        lateinit var stream: suspend (OpsSessionChannelDto, String) -> Unit
        val adapter = object : OpsAgentAdapter {
            override suspend fun list(workspace: java.io.File) = emptyList<OpsNativeSession>()
            override suspend fun launch(
                request: OpsAgentLaunch,
                output: suspend (OpsSessionChannelDto, String) -> Unit,
                structured: suspend (OpsStructuredEventDto) -> Unit,
                lifecycle: suspend (OpsSessionStateDto, String?, String?, Int?) -> Unit,
            ): OpsAgentProcess {
                launches.incrementAndGet()
                stream = output
                lifecycle(OpsSessionStateDto.RUNNING, null, "attached", null)
                return object : OpsAgentProcess {
                    override suspend fun input(text: String) = Unit
                    override suspend fun interrupt() = Unit
                    override suspend fun stop() = Unit
                }
            }
        }
        val service = OpsSessionService(adapters = mapOf(OpsAgentDto.ARCANA to adapter))
        val viewer = OpsViewerDto(userId = "dedupe-viewer")
        val principal = OpsSocketPrincipal(viewer, null)
        val command = OpsSessionCommandDto("same-request", OpsSessionActionDto.CREATE_SESSION, "workspace", OpsAgentDto.ARCANA, text = "hello")
        var droppedRuntimeId: String? = null
        val replay = mutableListOf<OpsSessionEventDto>()
        try {
            System.setProperty("user.home", home.path)
            java.io.File(home, "Desktop/server_repos/owner_repo").mkdirs()
            WorkspaceTracker.trackWorkspace(viewer.userId, "owner", "repo", "workspace", 42)
            service.handle(principal, command) { droppedRuntimeId = it.runtimeId }
            withTimeout(2_000) { while (launches.get() != 1) delay(10) }
            service.handle(principal, command, replay::add)
            val active = mutableListOf<OpsSessionEventDto>()
            service.handle(principal, OpsSessionCommandDto("active", OpsSessionActionDto.LIST_SESSIONS), active::add)

            assertEquals(1, launches.get())
            assertEquals(droppedRuntimeId, replay.first().runtimeId)
            assertTrue(replay.all(OpsSessionEventDto::replay))
            assertEquals(droppedRuntimeId, active.single().sessions.single().runtimeId)
            assertEquals("workspace", active.single().sessions.single().workspaceId)
            assertEquals(42, active.single().sessions.single().repositoryId)
            assertEquals("repo", active.single().sessions.single().workspaceName)

            coroutineScope {
                repeat(4) { worker ->
                    launch {
                        repeat(1_001) { index -> stream(OpsSessionChannelDto.STDOUT, "$worker:$index\n") }
                    }
                }
            }
            val staleReplay = mutableListOf<OpsSessionEventDto>()
            service.handle(
                principal,
                OpsSessionCommandDto(
                    "stale-attach",
                    OpsSessionActionDto.ATTACH_SESSION,
                    runtimeId = droppedRuntimeId,
                    afterSequence = 0,
                ),
                staleReplay::add,
            )
            val retainedSequences = staleReplay.drop(1).mapNotNull(OpsSessionEventDto::sequence)

            assertTrue(staleReplay.first().replay)
            assertEquals(null, staleReplay.first().sequence)
            assertTrue(staleReplay.first().text?.startsWith("Replay starts at sequence ") == true)
            assertEquals(1, staleReplay.count { it.replay && it.sequence == null })
            assertEquals(4_000, retainedSequences.size)
            assertEquals(retainedSequences.distinct(), retainedSequences)
            assertTrue(retainedSequences.zipWithNext().all { (first, second) -> second == first + 1 })

            java.io.File(home, "Desktop/server_repos/owner_other").mkdirs()
            WorkspaceTracker.trackWorkspace(viewer.userId, "owner", "other", "other-workspace", 84)
            var otherRuntimeId: String? = null
            service.handle(
                principal,
                OpsSessionCommandDto("other-request", OpsSessionActionDto.CREATE_SESSION, "other-workspace", OpsAgentDto.ARCANA, text = "other"),
            ) { otherRuntimeId = it.runtimeId }
            withTimeout(2_000) { while (launches.get() != 2) delay(10) }

            val global = mutableListOf<OpsSessionEventDto>()
            service.handle(principal, OpsSessionCommandDto("global", OpsSessionActionDto.LIST_SESSIONS), global::add)
            val attached = mutableListOf<OpsSessionEventDto>()
            service.handle(
                principal,
                OpsSessionCommandDto("attach", OpsSessionActionDto.ATTACH_SESSION, runtimeId = droppedRuntimeId),
                attached::add,
            )
            WorkspaceTracker.trackWorkspace(viewer.userId, "owner", "repo", "workspace", 42)
            val scoped = mutableListOf<OpsSessionEventDto>()
            service.handle(
                principal,
                OpsSessionCommandDto("scoped", OpsSessionActionDto.LIST_SESSIONS, "workspace", OpsAgentDto.ARCANA),
                scoped::add,
            )

            assertEquals(setOf(droppedRuntimeId, otherRuntimeId), global.single().sessions.map { it.runtimeId }.toSet())
            assertTrue(attached.isNotEmpty() && attached.all { it.runtimeId == droppedRuntimeId })
            assertEquals(listOf(droppedRuntimeId), scoped.single().sessions.map { it.runtimeId })
        } finally {
            service.close()
            WorkspaceTracker.removeClient(viewer.userId)
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun terminalRuntimeYieldsToItsDurableSessionAndResumeReusesItsTitle() = runBlocking {
        val previousHome = System.getProperty("user.home")
        val home = createTempDirectory("ops-session-home").toFile()
        val launched = AtomicInteger()
        lateinit var finish: suspend () -> Unit
        val adapter = object : OpsAgentAdapter {
            override suspend fun list(workspace: java.io.File) = listOf(
                OpsNativeSession("native-session", "durable", 123, state = OpsSessionStateDto.ONGOING),
            )

            override suspend fun launch(
                request: OpsAgentLaunch,
                output: suspend (OpsSessionChannelDto, String) -> Unit,
                structured: suspend (OpsStructuredEventDto) -> Unit,
                lifecycle: suspend (OpsSessionStateDto, String?, String?, Int?) -> Unit,
            ): OpsAgentProcess {
                lifecycle(OpsSessionStateDto.RUNNING, "native-session", "attached", null)
                finish = { lifecycle(OpsSessionStateDto.EXITED, "native-session", "done", 0) }
                launched.incrementAndGet()
                return object : OpsAgentProcess {
                    override suspend fun input(text: String) = Unit
                    override suspend fun interrupt() = Unit
                    override suspend fun stop() = Unit
                }
            }
        }
        val service = OpsSessionService(
            adapters = mapOf(OpsAgentDto.ARCANA to adapter),
            terminalReplayGraceMs = 200,
        )
        val viewer = OpsViewerDto(userId = "continuum-viewer")
        val principal = OpsSocketPrincipal(viewer, null)
        var terminalRuntimeId: String? = null
        try {
            System.setProperty("user.home", home.path)
            java.io.File(home, "Desktop/server_repos/owner_repo").mkdirs()
            WorkspaceTracker.trackWorkspace(viewer.userId, "owner", "repo", "workspace", 42)
            service.handle(
                principal,
                OpsSessionCommandDto("create", OpsSessionActionDto.CREATE_SESSION, "workspace", OpsAgentDto.ARCANA, text = "hello"),
            ) { terminalRuntimeId = it.runtimeId }
            withTimeout(2_000) { while (launched.get() != 1) delay(10) }
            finish()

            val live = mutableListOf<OpsSessionEventDto>()
            service.handle(principal, OpsSessionCommandDto("live", OpsSessionActionDto.LIST_SESSIONS), live::add)
            assertTrue(live.single().sessions.isEmpty())

            val retained = mutableListOf<OpsSessionEventDto>()
            service.handle(
                principal,
                OpsSessionCommandDto("attach-retained", OpsSessionActionDto.ATTACH_SESSION, runtimeId = terminalRuntimeId),
                retained::add,
            )
            assertTrue(retained.any { it.state == OpsSessionStateDto.EXITED })

            suspend fun durableSessions() = mutableListOf<OpsSessionEventDto>().also { listed ->
                service.handle(
                    principal,
                    OpsSessionCommandDto("list", OpsSessionActionDto.LIST_SESSIONS, "workspace", OpsAgentDto.ARCANA),
                    listed::add,
                )
            }.single().sessions

            assertEquals("native-session", durableSessions().single().sessionId)

            val expired = withTimeout(2_000) {
                var attempt = 0
                while (true) {
                    val events = mutableListOf<OpsSessionEventDto>()
                    service.handle(
                        principal,
                        OpsSessionCommandDto("attach-expired-${attempt++}", OpsSessionActionDto.ATTACH_SESSION, runtimeId = terminalRuntimeId),
                        events::add,
                    )
                    events.singleOrNull()
                        ?.takeIf { it.kind == OpsSessionEventKindDto.ERROR && it.text?.contains("not available") == true }
                        ?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            assertEquals(OpsSessionEventKindDto.ERROR, expired.kind)

            val listed = durableSessions().single()
            assertEquals("native-session", listed.sessionId)
            assertEquals(null, listed.runtimeId)
            assertEquals(OpsSessionStateDto.ONGOING, listed.state)

            service.handle(
                principal,
                OpsSessionCommandDto(
                    "resume",
                    OpsSessionActionDto.RESUME_SESSION,
                    "workspace",
                    OpsAgentDto.ARCANA,
                    sessionId = "native-session",
                ),
            ) {}
            withTimeout(2_000) { while (launched.get() != 2) delay(10) }
            val active = mutableListOf<OpsSessionEventDto>()
            service.handle(principal, OpsSessionCommandDto("active", OpsSessionActionDto.LIST_SESSIONS), active::add)

            assertEquals("durable", active.single().sessions.single().title)
        } finally {
            service.close()
            WorkspaceTracker.removeClient(viewer.userId)
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun arcanaDecoderReassemblesOutOfOrderAndIdempotentChunks() {
        val frames = frames(event)
        val decoder = ArcanaEventDecoder()

        assertEquals(null, decoder.accept(frames[1]))
        assertEquals(null, decoder.accept(frames[0]))
        assertEquals(null, decoder.accept(frames[0]))
        frames.drop(2).dropLast(1).forEach { assertEquals(null, decoder.accept(it)) }
        assertEquals(event, decoder.accept(frames.last()))
        assertTrue(decoder.incomplete().isEmpty())
    }

    @Test
    fun arcanaDecoderRejectsCorruptionAndConflictingDuplicates() {
        val frames = frames(event)
        val duplicate = frames.first().let { it.dropLast(1) + if (it.last() == 'A') 'B' else 'A' }
        ArcanaEventDecoder().also { decoder ->
            decoder.accept(frames.first())
            assertFailsWith<IllegalArgumentException> { decoder.accept(duplicate) }
        }

        val digest = frames.first().split(':', limit = 4)[2]
        val corrupt = frames.map { it.replace(":$digest:", ":0000000000000000:") }
        ArcanaEventDecoder().also { decoder ->
            corrupt.dropLast(1).forEach(decoder::accept)
            assertFailsWith<IllegalArgumentException> { decoder.accept(corrupt.last()) }
        }
    }

    @Test
    fun arcanaStderrSeparatesValidSidebandWithoutChangingTerminalText() = runBlocking {
        val token = "a".repeat(32)
        val frames = frames(event)
        val forged = "@@ARCANA_EVENT_V1@@:${"b".repeat(32)}:${frames.first()}\n"
        val input = buildString {
            append("ordinary α\n")
            append("@@ARCANA_EVENT_V1@@:$token:${frames[1]}\n")
            append("diagnostic between chunks\n")
            append(forged)
            frames.filterIndexed { index, _ -> index != 1 }.forEach {
                append("@@ARCANA_EVENT_V1@@:$token:$it\n")
            }
            append("tail without newline")
        }
        val stderr = StringBuilder()
        val structured = mutableListOf<OpsStructuredEventDto>()

        readArcanaStderr(
            ByteArrayInputStream(input.toByteArray(StandardCharsets.UTF_8)),
            token,
            { channel, text ->
                assertEquals(OpsSessionChannelDto.STDERR, channel)
                stderr.append(text)
            },
            structured::add,
        )

        assertEquals("ordinary α\ndiagnostic between chunks\n${forged}tail without newline", stderr.toString())
        assertEquals(listOf(event), structured)
    }

    @Test
    fun arcanaStderrReportsTruncatedSideband() = runBlocking {
        val token = "a".repeat(32)
        val frames = frames(event)
        val stderr = StringBuilder()
        val structured = mutableListOf<OpsStructuredEventDto>()

        readArcanaStderr(
            ByteArrayInputStream("@@ARCANA_EVENT_V1@@:$token:${frames.first()}\n".toByteArray()),
            token,
            { _, text -> stderr.append(text) },
            structured::add,
        )

        assertEquals("Arcana event ${frames.first().substringBefore(':')} truncated (1/${frames.size} chunks)\n", stderr.toString())
        assertTrue(structured.isEmpty())
    }

    @Test
    fun codexApprovalIsOneCorrelatedInputGateAndResponse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fake = FakeCodexProcess { server, message ->
            when (message["method"]?.jsonPrimitive?.content) {
                "initialize" -> server.result(message, buildJsonObject {})
                "turn/start" -> {
                    server.result(message, buildJsonObject { put("turn", buildJsonObject { put("id", "turn-1") }) })
                    server.notification("turn/started", buildJsonObject {
                        put("threadId", "thread-1")
                        put("turn", buildJsonObject { put("id", "turn-1") })
                    })
                    server.request(
                        "approval-1",
                        "item/commandExecution/requestApproval",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            put("turnId", "turn-1")
                            put("itemId", "command-1")
                            put("startedAtMs", 1)
                            put("command", "git status")
                        },
                    )
                }
                null -> if (message["id"]?.jsonPrimitive?.content == "approval-1") {
                    server.notification("item/agentMessage/delta", buildJsonObject {
                        put("threadId", "thread-1")
                        put("turnId", "turn-1")
                        put("itemId", "message-1")
                        put("delta", "approval accepted")
                    })
                    server.notification("turn/completed", buildJsonObject {
                        put("threadId", "thread-1")
                        put("turn", buildJsonObject { put("id", "turn-1"); put("status", "completed") })
                    })
                }
            }
        }
        val output = Collections.synchronizedList(mutableListOf<String>())
        val structured = Collections.synchronizedList(mutableListOf<OpsStructuredEventDto>())
        val lifecycles = Collections.synchronizedList(mutableListOf<OpsSessionStateDto>())
        lateinit var process: CodexAgentProcess
        val client = CodexAppClient(
            scope,
            process = fake,
            onNotification = { process.notification(it) },
            onRequest = { process.request(it) },
        )
        try {
            client.initialize()
            process = CodexAgentProcess(
                scope,
                client,
                "thread-1",
                { _, text -> output += text },
                structured::add,
                { state, _, _, _ -> lifecycles += state },
            )

            process.input("run the check")
            withTimeout(2_000) { while (structured.none { it.type == "input_request" }) delay(10) }
            val gate = structured.first { it.type == "input_request" }
            assertEquals("codex_approval", gate.payload["kind"]?.jsonPrimitive?.content)
            assertEquals("approval-1", gate.payload["request_id"]?.jsonPrimitive?.content)
            assertEquals("item/commandExecution/requestApproval", gate.payload["method"]?.jsonPrimitive?.content)

            process.input("accept")
            withTimeout(2_000) { while (output.none { it.contains("approval accepted") }) delay(10) }

            val reply = fake.messages.first { it["id"]?.jsonPrimitive?.content == "approval-1" }
            assertEquals("accept", reply["result"]?.jsonObject?.get("decision")?.jsonPrimitive?.content)
            assertTrue(structured.any { it.type == "input_resolved" })
            assertEquals(OpsSessionStateDto.READY, lifecycles.last())
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun codexAdapterUsesTheSingleStableClientAcrossListStartResumeReadSteerInterruptAndExit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val workspace = createTempDirectory("codex-workspace").toFile()
        val adapter = CodexAgentAdapter(
            scope,
            codexTestCommand(),
        )
        val output = Collections.synchronizedList(mutableListOf<String>())
        val structured = Collections.synchronizedList(mutableListOf<OpsStructuredEventDto>())
        val lifecycles = Collections.synchronizedList(mutableListOf<OpsSessionStateDto>())
        val launch = OpsAgentLaunch(
            workspace = workspace,
            resumeSessionId = null,
            initialInput = "start",
            model = null,
            noPace = null,
            paceMinSeconds = null,
            paceMaxSeconds = null,
            auto = false,
            indexSync = false,
            mode = net.sdfgsdfg.data.model.OpsArcanaModeDto.WORKSPACE,
        )
        var created: OpsAgentProcess? = null
        try {
            assertEquals(listOf("thread-resume"), adapter.list(workspace).map(OpsNativeSession::sessionId))

            created = adapter.launch(
                launch,
                { _, text -> output += text },
                structured::add,
                { state, _, _, _ -> lifecycles += state },
            )
            withTimeout(2_000) { while (output.none { it == "started" }) delay(10) }
            created.input("steer")
            withTimeout(2_000) { while (output.none { it == "steered" }) delay(10) }
            created.interrupt()
            withTimeout(2_000) { while (lifecycles.lastOrNull() != OpsSessionStateDto.READY) delay(10) }
            val starts = output.count { it == "started" }
            created.input("continue after interrupt")
            withTimeout(2_000) { while (output.count { it == "started" } == starts) delay(10) }
            assertEquals(
                "thread-created",
                structured.last { it.type == "item/agentMessage/delta" }.payload["threadId"]?.jsonPrimitive?.content,
            )

            val resumed = adapter.launch(
                launch.copy(resumeSessionId = "thread-resume", initialInput = ""),
                { _, text -> output += text },
                structured::add,
                { state, _, _, _ -> lifecycles += state },
            )
            withTimeout(2_000) { while (structured.none { it.type == "thread/read-proved" }) delay(10) }
            val history = structured.firstOrNull { it.type == "item/completed" && it.payload["history"]?.jsonPrimitive?.boolean == true }
            assertEquals("Known prior response", history?.payload?.get("item")?.jsonObject?.get("text")?.jsonPrimitive?.content)
            resumed.input("continue durable thread")
            withTimeout(2_000) { while (output.none { it == "resumed" }) delay(10) }
            assertEquals(
                "thread-resume",
                structured.last { it.type == "item/agentMessage/delta" }.payload["threadId"]?.jsonPrimitive?.content,
            )
            resumed.input("exit-app-server")
            val terminalProcessExitTimeoutMs = 5_000L
            withTimeout(terminalProcessExitTimeoutMs) { while (lifecycles.lastOrNull() != OpsSessionStateDto.FAILED) delay(10) }
            assertTrue(lifecycles.contains(OpsSessionStateDto.RUNNING))
        } finally {
            created?.stop()
            scope.cancel()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun codexRuntimeReplaysItsOrderedLedgerAfterDetach() = runBlocking {
        val previousHome = System.getProperty("user.home")
        val home = createTempDirectory("ops-codex-home").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = OpsSessionService(adapters = mapOf(OpsAgentDto.CODEX to CodexAgentAdapter(scope, codexTestCommand())))
        val viewer = OpsViewerDto(userId = "codex-replay-viewer")
        val principal = OpsSocketPrincipal(viewer, null)
        val events = Collections.synchronizedList(mutableListOf<OpsSessionEventDto>())
        var runtimeId: String? = null
        try {
            System.setProperty("user.home", home.path)
            java.io.File(home, "Desktop/server_repos/owner_repo").mkdirs()
            WorkspaceTracker.trackWorkspace(viewer.userId, "owner", "repo", "workspace", 42)
            service.handle(
                principal,
                OpsSessionCommandDto("codex-create", OpsSessionActionDto.CREATE_SESSION, "workspace", OpsAgentDto.CODEX, text = "start"),
            ) {
                runtimeId = it.runtimeId
                events += it
            }
            withTimeout(2_000) { while (events.none { it.text == "started" }) delay(10) }

            val replay = mutableListOf<OpsSessionEventDto>()
            service.handle(
                principal,
                OpsSessionCommandDto("codex-attach", OpsSessionActionDto.ATTACH_SESSION, runtimeId = runtimeId, afterSequence = 0),
                replay::add,
            )

            assertTrue(replay.isNotEmpty() && replay.all { it.replay && it.runtimeId == runtimeId })
            assertTrue(replay.any { it.text == "started" && it.channel == OpsSessionChannelDto.STDOUT })
            val sequences = replay.mapNotNull(OpsSessionEventDto::sequence)
            assertTrue(sequences.zipWithNext().all { (first, second) -> second == first + 1 })
        } finally {
            service.close()
            scope.cancel()
            WorkspaceTracker.removeClient(viewer.userId)
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun malformedCodexProtocolFailsWithoutBecomingTranscript() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fake = FakeCodexProcess { server, message ->
            if (message["method"]?.jsonPrimitive?.content == "initialize") server.raw("not json")
        }
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val stderr = Collections.synchronizedList(mutableListOf<String>())
        val client = CodexAppClient(
            scope,
            process = fake,
            onStderr = stderr::add,
            onProtocolFailure = failures::add,
        )
        try {
            assertFailsWith<IllegalStateException> { client.initialize() }
            withTimeout(2_000) { while (failures.isEmpty()) delay(10) }
            assertEquals(listOf("Invalid Codex protocol output"), failures)
            assertTrue(stderr.isEmpty())
        } finally {
            client.close()
            scope.cancel()
        }
    }

    private fun frames(value: OpsStructuredEventDto): List<String> {
        val raw = Json.encodeToString(value).toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }.take(16)
        val chunks = Base64.getEncoder().encodeToString(raw).chunked(19)
        val id = "0123456789abcdef0123456789abcdef"
        return chunks.mapIndexed { index, chunk -> "$id:${index + 1}/${chunks.size}:$digest:$chunk" }
    }

    private fun codexTestCommand() = listOf(
        java.io.File(System.getProperty("java.home"), "bin/java").path,
        "-cp",
        System.getProperty("java.class.path"),
        "net.sdfgsdfg.CodexTestAppServer",
    )
}

private class FakeCodexProcess(
    private val onMessage: (FakeCodexProcess, JsonObject) -> Unit,
) : Process() {
    private val serverOutput = PipedOutputStream()
    private val clientInput = PipedInputStream(serverOutput)
    private val clientOutput = PipedOutputStream()
    private val serverInput = PipedInputStream(clientOutput)
    private val stopped = CountDownLatch(1)
    private val alive = AtomicBoolean(true)
    val messages = Collections.synchronizedList(mutableListOf<JsonObject>())

    init {
        thread(name = "fake-codex-app-server", isDaemon = true) {
            serverInput.bufferedReader().forEachLine { line ->
                val message = Json.parseToJsonElement(line).jsonObject
                messages += message
                onMessage(this, message)
            }
        }
    }

    fun result(request: JsonObject, result: JsonObject) = message(buildJsonObject {
        put("id", request["id"] ?: JsonPrimitive(0))
        put("result", result)
    })

    fun notification(method: String, params: JsonObject) = message(buildJsonObject {
        put("method", method)
        put("params", params)
    })

    fun request(id: String, method: String, params: JsonObject) = message(buildJsonObject {
        put("id", id)
        put("method", method)
        put("params", params)
    })

    fun raw(value: String) {
        serverOutput.write((value + "\n").toByteArray(StandardCharsets.UTF_8))
        serverOutput.flush()
    }

    private fun message(value: JsonObject) = raw(value.toString())

    override fun getOutputStream(): OutputStream = clientOutput
    override fun getInputStream() = clientInput
    override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int {
        stopped.await()
        return 0
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = stopped.await(timeout, unit)
    override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else 0
    override fun isAlive(): Boolean = alive.get()
    override fun destroy() {
        if (!alive.compareAndSet(true, false)) return
        runCatching { serverOutput.close() }
        runCatching { serverInput.close() }
        stopped.countDown()
    }
    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
}

object CodexTestAppServer {
    @JvmStatic
    fun main(args: Array<String>) {
        System.`in`.bufferedReader().forEachLine { line ->
            val message = Json.parseToJsonElement(line).jsonObject
            val method = message["method"]?.jsonPrimitive?.content
            val params = message["params"] as? JsonObject ?: buildJsonObject {}
            fun emit(value: JsonObject) {
                println(value)
                System.out.flush()
            }
            fun result(value: JsonObject) = emit(buildJsonObject {
                put("id", message["id"] ?: JsonPrimitive(0))
                put("result", value)
            })
            fun notification(name: String, value: JsonObject) = emit(buildJsonObject {
                put("method", name)
                put("params", value)
            })
            when (method) {
                "initialize" -> result(buildJsonObject {})
                "thread/list" -> result(buildJsonObject {
                    put("data", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("id", "thread-resume")
                            put("name", "Resumable thread")
                            put("updatedAt", 1)
                            put("modelProvider", "openai")
                        })
                    })
                })
                "thread/start", "thread/resume" -> result(buildJsonObject {
                    put("thread", buildJsonObject { put("id", if (method == "thread/resume") "thread-resume" else "thread-created") })
                })
                "thread/read" -> {
                    result(buildJsonObject {
                        put("thread", buildJsonObject {
                            put("id", "thread-resume")
                            put("turns", kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject {
                                    put("id", "prior-turn")
                                    put("status", "completed")
                                    put("items", kotlinx.serialization.json.buildJsonArray {
                                        add(buildJsonObject {
                                            put("id", "history-message")
                                            put("type", "agentMessage")
                                            put("text", "Known prior response")
                                        })
                                    })
                                })
                            })
                        })
                    })
                    notification("thread/read-proved", buildJsonObject { put("threadId", "thread-resume") })
                }
                "turn/start" -> {
                    val text = params["input"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                    result(buildJsonObject { put("turn", buildJsonObject { put("id", "turn-1") }) })
                    if (text == "exit-app-server") {
                        System.exit(17)
                    }
                    notification("turn/started", buildJsonObject {
                        put("threadId", params["threadId"] ?: JsonPrimitive("thread-created"))
                        put("turn", buildJsonObject { put("id", "turn-1") })
                    })
                    notification("item/agentMessage/delta", buildJsonObject {
                        put("threadId", params["threadId"] ?: JsonPrimitive("thread-created"))
                        put("turnId", "turn-1")
                        put("itemId", "message-1")
                        put("delta", if (text == "continue durable thread") "resumed" else "started")
                    })
                    if (text == "continue durable thread") notification("turn/completed", buildJsonObject {
                        put("threadId", params["threadId"] ?: JsonPrimitive("thread-created"))
                        put("turn", buildJsonObject { put("id", "turn-1"); put("status", "completed") })
                    })
                }
                "turn/steer" -> {
                    result(buildJsonObject {})
                    notification("item/agentMessage/delta", buildJsonObject {
                        put("threadId", params["threadId"] ?: JsonPrimitive("thread-created"))
                        put("turnId", "turn-1")
                        put("itemId", "message-1")
                        put("delta", "steered")
                    })
                }
                "turn/interrupt" -> {
                    result(buildJsonObject {})
                    notification("turn/completed", buildJsonObject {
                        put("threadId", params["threadId"] ?: JsonPrimitive("thread-created"))
                        put("turn", buildJsonObject { put("id", "turn-1"); put("status", "interrupted") })
                    })
                }
            }
        }
    }
}
