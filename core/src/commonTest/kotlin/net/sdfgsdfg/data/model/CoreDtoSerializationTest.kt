package net.sdfgsdfg.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

// Purpose: protect public JSON wire names shared by backend, server_py,
// GitHub Actions artifacts, and the dashboard clients. These are compatibility
// tests, not generic serialization coverage; keep them only for DTOs that cross
// repo/process boundaries.
class CoreDtoSerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun workspaceSocketDtosKeepTypedWireNames() {
        val command = json.parseToJsonElement(
            json.encodeToString(
                OpsSocketMessageDto(
                    type = "workspace_command",
                    workspaceCommand = OpsWorkspaceCommandDto(
                        requestId = "repo-1",
                        action = OpsWorkspaceActionDto.SELECT_REPOSITORY,
                        repositoryId = 42L,
                    ),
                ),
            ),
        ).jsonObject
        val commandPayload = command.getValue("workspace_command").jsonObject
        assertEquals("repo-1", commandPayload.getValue("request_id").jsonPrimitive.content)
        assertEquals("select_repository", commandPayload.getValue("action").jsonPrimitive.content)
        assertEquals(42L, commandPayload.getValue("repository_id").jsonPrimitive.long)
        assertFalse("workspaceCommand" in command)
        assertFalse("requestId" in commandPayload)
        assertFalse("repositoryId" in commandPayload)

        val event = json.parseToJsonElement(
            json.encodeToString(
                OpsSocketMessageDto(
                    type = "workspace_event",
                    workspaceEvent = OpsWorkspaceEventDto(
                        requestId = "repo-1",
                        kind = OpsWorkspaceEventKindDto.REPOSITORIES,
                        status = OpsWorkspaceEventStatusDto.READY,
                        repositories = listOf(
                            OpsRepositoryDto(
                                id = 42L,
                                name = "backend",
                                owner = "sdfgsdfgd",
                                fullName = "sdfgsdfgd/backend",
                                language = "Kotlin",
                                stars = 7,
                                updatedAt = "2026-07-14T00:00:00Z",
                                defaultBranch = "main",
                                isPrivate = true,
                            ),
                        ),
                    ),
                ),
            ),
        ).jsonObject
        val eventPayload = event.getValue("workspace_event").jsonObject
        val repository = eventPayload.getValue("repositories").jsonArray.single().jsonObject
        assertEquals("repositories", eventPayload.getValue("kind").jsonPrimitive.content)
        assertEquals("ready", eventPayload.getValue("status").jsonPrimitive.content)
        assertEquals("sdfgsdfgd/backend", repository.getValue("full_name").jsonPrimitive.content)
        assertEquals("2026-07-14T00:00:00Z", repository.getValue("updated_at").jsonPrimitive.content)
        assertEquals("main", repository.getValue("default_branch").jsonPrimitive.content)
        assertEquals(true, repository.getValue("private").jsonPrimitive.boolean)
        assertFalse("workspaceEvent" in event)
        assertFalse("fullName" in repository)
        assertFalse("updatedAt" in repository)
        assertFalse("defaultBranch" in repository)
        assertFalse("isPrivate" in repository)
    }

    @Test
    fun sessionSocketDtosKeepOrderedWireNames() {
        val command = json.parseToJsonElement(
            json.encodeToString(
                OpsSocketMessageDto(
                    type = "session_command",
                    sessionCommand = OpsSessionCommandDto(
                        requestId = "turn-1",
                        action = OpsSessionActionDto.RESUME_SESSION,
                        workspaceId = "workspace-1",
                        agent = OpsAgentDto.ARCANA,
                        sessionId = "arcana-7",
                        afterSequence = 41,
                        model = "deepseek-expert",
                        noPace = false,
                        auto = true,
                        indexSync = true,
                        arcanaMode = OpsArcanaModeDto.ISSUES,
                    ),
                ),
            ),
        ).jsonObject
        val commandPayload = command.getValue("session_command").jsonObject
        assertEquals("resume_session", commandPayload.getValue("action").jsonPrimitive.content)
        assertEquals("workspace-1", commandPayload.getValue("workspace_id").jsonPrimitive.content)
        assertEquals("arcana", commandPayload.getValue("agent").jsonPrimitive.content)
        assertEquals("arcana-7", commandPayload.getValue("session_id").jsonPrimitive.content)
        assertEquals(41L, commandPayload.getValue("after_sequence").jsonPrimitive.long)
        assertEquals("deepseek-expert", commandPayload.getValue("model").jsonPrimitive.content)
        assertFalse(commandPayload.getValue("no_pace").jsonPrimitive.boolean)
        assertEquals(true, commandPayload.getValue("auto").jsonPrimitive.boolean)
        assertEquals(true, commandPayload.getValue("index_sync").jsonPrimitive.boolean)
        assertEquals("issues", commandPayload.getValue("arcana_mode").jsonPrimitive.content)
        assertFalse("sessionCommand" in command)
        assertFalse("workspaceId" in commandPayload)
        assertFalse("sessionId" in commandPayload)
        assertFalse("afterSequence" in commandPayload)
        assertFalse("indexSync" in commandPayload)
        assertFalse("arcanaMode" in commandPayload)

        val event = json.parseToJsonElement(
            json.encodeToString(
                OpsSocketMessageDto(
                    type = "session_event",
                    sessionEvent = OpsSessionEventDto(
                        requestId = "turn-1",
                        kind = OpsSessionEventKindDto.STRUCTURED,
                        runtimeId = "runtime-1",
                        workspaceId = "workspace-1",
                        agent = OpsAgentDto.ARCANA,
                        sequence = 42,
                        timestampMs = 43,
                        state = OpsSessionStateDto.RUNNING,
                        channel = OpsSessionChannelDto.SYSTEM,
                        structured = OpsStructuredEventDto(
                            type = "agent_response",
                            phase = "3_x",
                            schema = "3_x",
                            round = 2,
                            payload = buildJsonObject { put("text", "folder summary") },
                        ),
                        replay = true,
                    ),
                ),
            ),
        ).jsonObject
        val eventPayload = event.getValue("session_event").jsonObject
        assertEquals("structured", eventPayload.getValue("kind").jsonPrimitive.content)
        assertEquals("runtime-1", eventPayload.getValue("runtime_id").jsonPrimitive.content)
        assertEquals(42L, eventPayload.getValue("sequence").jsonPrimitive.long)
        assertEquals(43L, eventPayload.getValue("timestamp_ms").jsonPrimitive.long)
        assertEquals("running", eventPayload.getValue("state").jsonPrimitive.content)
        assertEquals("system", eventPayload.getValue("channel").jsonPrimitive.content)
        val structured = eventPayload.getValue("structured").jsonObject
        assertEquals("agent_response", structured.getValue("type").jsonPrimitive.content)
        assertEquals("3_x", structured.getValue("phase").jsonPrimitive.content)
        assertEquals("folder summary", structured.getValue("payload").jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(true, eventPayload.getValue("replay").jsonPrimitive.boolean)
        assertFalse("sessionEvent" in event)
        assertFalse("runtimeId" in eventPayload)
        assertFalse("workspaceId" in eventPayload)
        assertFalse("exitCode" in eventPayload)

        val summary = json.parseToJsonElement(json.encodeToString(OpsSessionSummaryDto(
            sessionId = "arcana-7",
            agent = OpsAgentDto.ARCANA,
            title = "Finish the fixture",
            updatedAtMs = 44,
            workspaceId = "workspace-1",
            repositoryId = 42,
            workspaceName = "backend",
            state = OpsSessionStateDto.AWAITING_ACCEPTANCE,
            changesKnown = true,
            hasChanges = true,
        ))).jsonObject
        assertEquals("workspace-1", summary.getValue("workspace_id").jsonPrimitive.content)
        assertEquals(42L, summary.getValue("repository_id").jsonPrimitive.long)
        assertEquals("backend", summary.getValue("workspace_name").jsonPrimitive.content)
        assertEquals("awaiting_acceptance", summary.getValue("state").jsonPrimitive.content)
        assertEquals(true, summary.getValue("changes_known").jsonPrimitive.boolean)
        assertEquals(true, summary.getValue("has_changes").jsonPrimitive.boolean)
        assertFalse("changesKnown" in summary)
        assertFalse("hasChanges" in summary)
    }

    @Test
    fun askRequestKeepsPublicWireNames() {
        val encoded = json.encodeToString(
            AskRequestDto(
                prompt = "ping",
                requestId = "req-1",
                deepseekSearch = true,
                noPace = true,
                sessionId = "session-1",
            ),
        )

        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("req-1", obj.getValue("request_id").jsonPrimitive.content)
        assertEquals("session-1", obj.getValue("session_id").jsonPrimitive.content)
        assertEquals(true, obj.getValue("deepseek_search").jsonPrimitive.boolean)
        assertEquals(true, obj.getValue("no_pace").jsonPrimitive.boolean)
        assertFalse("new_tab" in obj)
        assertFalse("requestId" in obj)
        assertFalse("sessionId" in obj)
        assertFalse("deepseekSearch" in obj)
        assertFalse("noPace" in obj)
    }

    @Test
    fun selfTestDtosKeepPublicWireNames() {
        val headSha = "0123456789abcdef0123456789abcdef01234567"
        val request = json.parseToJsonElement(
            json.encodeToString(
                SelfTestRequestDto(
                    expectSubstr = "OK",
                    newChat = true,
                    workflowUrl = "https://github.com/x/backend/actions/runs/1",
                    headSha = headSha,
                ),
            ),
        ).jsonObject
        assertEquals("OK", request.getValue("expect_substr").jsonPrimitive.content)
        assertEquals(true, request.getValue("new_chat").jsonPrimitive.boolean)
        assertEquals("https://github.com/x/backend/actions/runs/1", request.getValue("workflow_url").jsonPrimitive.content)
        assertEquals(headSha, request.getValue("head_sha").jsonPrimitive.content)
        assertFalse("expectSubstr" in request)
        assertFalse("newChat" in request)
        assertFalse("workflowUrl" in request)
        assertFalse("headSha" in request)

        val result = json.parseToJsonElement(
            json.encodeToString(
                SelfTestResultDto(
                    ok = true,
                    textExcerpt = "healthy",
                    rawError = null,
                    askLatencyMs = 12.0,
                    auditLatencyMs = 3.0,
                    satisfiedExpectation = true,
                    workflowUrl = "https://github.com/x/backend/actions/runs/1",
                    headSha = headSha,
                    timestampMs = 42L,
                ),
            ),
        ).jsonObject
        assertEquals("healthy", result.getValue("text_excerpt").jsonPrimitive.content)
        assertEquals(true, result.getValue("satisfied_expectation").jsonPrimitive.boolean)
        assertEquals("https://github.com/x/backend/actions/runs/1", result.getValue("workflow_url").jsonPrimitive.content)
        assertEquals(headSha, result.getValue("head_sha").jsonPrimitive.content)
        assertFalse("textExcerpt" in result)
        assertFalse("satisfiedExpectation" in result)
        assertFalse("workflowUrl" in result)
        assertFalse("headSha" in result)
    }

    @Test
    fun opsDtosKeepPublicWireNames() {
        val encoded = json.encodeToString(
            OpsSummaryDto(
                generatedAtMs = 7L,
                repos = listOf(
                    RepoHealthDto(
                        id = "backend",
                        name = "backend",
                        role = "control plane",
                        status = OpsStatusDto.OK,
                        runtimeLabel = "local preview",
                        runtimeLabels = listOf("remote q", "local"),
                        latestRun = TestRunSummaryDto(
                            label = "smoke",
                            status = OpsStatusDto.OK,
                            timestampMs = 9L,
                            durationMs = 12.0,
                            artifactUrl = "/api/ops/artifacts/backend-deploy.json",
                            coveragePct = 88.8,
                        ),
                        runs = listOf(
                            TestRunSummaryDto(
                                label = "public ingress",
                                status = OpsStatusDto.WIP,
                            ),
                        ),
                        history = listOf(
                            TestRunSummaryDto(
                                label = "deploy abc1234",
                                status = OpsStatusDto.OK,
                                timestampMs = 13L,
                                durationMs = 44.0,
                            ),
                        ),
                        signals = listOf(
                            OpsSignalDto(
                                label = "runtime",
                                status = OpsStatusDto.OK,
                                timestampMs = 15L,
                                detail = "systemd runtime",
                                meta = "remote q",
                            ),
                        ),
                        selfTest = SelfTestSummaryDto(
                            status = OpsStatusDto.OK,
                            ok = true,
                            satisfiedExpectation = true,
                            timestampMs = 11L,
                            timestampLabel = "20 May, 12:24 AM AEST",
                            latencyMs = 44.0,
                            askLatencyMs = 33.0,
                            auditLatencyMs = 7.0,
                            textExcerpt = "healthy",
                            caseCount = 1,
                            casePassCount = 1,
                            zenPresent = true,
                            zenState = "push_failed",
                            zenReason = "model selector drift",
                            zenSeverity = "error",
                            zenArtifactPath = "/tmp/zen",
                            workflowUrl = "https://github.com/x/backend/actions/runs/1",
                            artifacts = listOf(
                                OpsArtifactDto(
                                    name = "server-py-selftest.json",
                                    path = "/var/log/backend/server-py-selftest.json",
                                ),
                            ),
                            cases = listOf(
                                SelfTestCaseSummaryDto(
                                    name = "5.5-thinking-heavy",
                                    status = OpsStatusDto.OK,
                                    latencyMs = 22.0,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val obj = json.parseToJsonElement(encoded).jsonObject
        val repo = obj.getValue("repos").jsonArray.first().jsonObject
        val run = repo.getValue("latest_run").jsonObject
        val pyramidRun = repo.getValue("runs").jsonArray.first().jsonObject
        val historyRun = repo.getValue("history").jsonArray.first().jsonObject
        val signal = repo.getValue("signals").jsonArray.first().jsonObject
        val selfTest = repo.getValue("self_test").jsonObject
        val selfTestCase = selfTest.getValue("cases").jsonArray.first().jsonObject
        assertEquals(7L, obj.getValue("generated_at_ms").jsonPrimitive.long)
        assertEquals("local preview", repo.getValue("runtime_label").jsonPrimitive.content)
        assertEquals(listOf("remote q", "local"), repo.getValue("runtime_labels").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(9L, run.getValue("timestamp_ms").jsonPrimitive.long)
        assertEquals(12.0, run.getValue("duration_ms").jsonPrimitive.double)
        assertEquals("/api/ops/artifacts/backend-deploy.json", run.getValue("artifact_url").jsonPrimitive.content)
        assertEquals(88.8, run.getValue("coverage_pct").jsonPrimitive.double)
        assertEquals("public ingress", pyramidRun.getValue("label").jsonPrimitive.content)
        assertEquals("deploy abc1234", historyRun.getValue("label").jsonPrimitive.content)
        assertEquals(13L, historyRun.getValue("timestamp_ms").jsonPrimitive.long)
        assertEquals(44.0, historyRun.getValue("duration_ms").jsonPrimitive.double)
        assertEquals("runtime", signal.getValue("label").jsonPrimitive.content)
        assertEquals(15L, signal.getValue("timestamp_ms").jsonPrimitive.long)
        assertEquals("remote q", signal.getValue("meta").jsonPrimitive.content)
        assertEquals(true, selfTest.getValue("satisfied_expectation").jsonPrimitive.boolean)
        assertEquals("20 May, 12:24 AM AEST", selfTest.getValue("timestamp_label").jsonPrimitive.content)
        assertEquals(33.0, selfTest.getValue("ask_latency_ms").jsonPrimitive.double)
        assertEquals(7.0, selfTest.getValue("audit_latency_ms").jsonPrimitive.double)
        assertEquals("healthy", selfTest.getValue("text_excerpt").jsonPrimitive.content)
        assertEquals(1, selfTest.getValue("case_count").jsonPrimitive.content.toInt())
        assertEquals(1, selfTest.getValue("case_pass_count").jsonPrimitive.content.toInt())
        assertEquals(true, selfTest.getValue("zen_present").jsonPrimitive.boolean)
        assertEquals("push_failed", selfTest.getValue("zen_state").jsonPrimitive.content)
        assertEquals("model selector drift", selfTest.getValue("zen_reason").jsonPrimitive.content)
        assertEquals("error", selfTest.getValue("zen_severity").jsonPrimitive.content)
        assertEquals("/tmp/zen", selfTest.getValue("zen_artifact_path").jsonPrimitive.content)
        assertEquals("https://github.com/x/backend/actions/runs/1", selfTest.getValue("workflow_url").jsonPrimitive.content)
        assertEquals("server-py-selftest.json", selfTest.getValue("artifacts").jsonArray.first().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("5.5-thinking-heavy", selfTestCase.getValue("name").jsonPrimitive.content)
        assertFalse("generatedAtMs" in obj)
        assertFalse("service_name" in repo)
        assertFalse("serviceName" in repo)
        assertFalse("runtimeLabel" in repo)
        assertFalse("runtimeLabels" in repo)
        assertFalse("latestRun" in repo)
        assertFalse("artifactUrl" in run)
        assertFalse("coveragePct" in run)
        assertFalse("selfTest" in repo)
        assertFalse("timestampLabel" in selfTest)
        assertFalse("zenState" in selfTest)
        assertFalse("zenReason" in selfTest)
        assertFalse("zenSeverity" in selfTest)
        assertFalse("zenArtifactPath" in selfTest)

        val ingest = json.parseToJsonElement(
            json.encodeToString(
                ArcanaIngestDto(
                    status = OpsStatusDto.OK,
                    label = "pytest local publisher",
                    timestampMs = 21L,
                    durationMs = 123.0,
                    coveragePct = 77.7,
                    detail = "unit spine passed",
                    issues = IssueSummaryDto(
                        todo = 2,
                        wip = 1,
                        done = 3,
                        sources = listOf(IssueSourceSummaryDto("arcana", "Arcana issues", todo = 2, wip = 1, done = 3)),
                        items = listOf(
                            IssueItemDto(
                                id = "ISS-1",
                                title = "stabilize issue DTO",
                                status = "wip",
                                source = "arcana",
                                createdAtMs = 31L,
                                updatedAtMs = 32L,
                            ),
                        ),
                        events = listOf(
                            IssueEventDto(
                                eventId = "EVT-1",
                                tsMs = 33L,
                                event = "updated",
                                id = "ISS-1",
                                title = "stabilize issue DTO",
                                status = "wip",
                                changes = mapOf("status" to IssueEventChangeDto("todo", "wip")),
                            ),
                        ),
                    ),
                    runs = listOf(TestRunSummaryDto("pytest unit", OpsStatusDto.OK)),
                ),
            ),
        ).jsonObject
        assertEquals(21L, ingest.getValue("timestamp_ms").jsonPrimitive.long)
        assertEquals(123.0, ingest.getValue("duration_ms").jsonPrimitive.double)
        assertEquals(77.7, ingest.getValue("coverage_pct").jsonPrimitive.double)
        val ingestIssues = ingest.getValue("issues").jsonObject
        val ingestIssue = ingestIssues.getValue("items").jsonArray.first().jsonObject
        val ingestEvent = ingestIssues.getValue("events").jsonArray.first().jsonObject
        val ingestChange = ingestEvent.getValue("changes").jsonObject.getValue("status").jsonObject
        assertEquals("arcana", ingestIssues.getValue("sources").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(31L, ingestIssue.getValue("created_at_ms").jsonPrimitive.long)
        assertEquals(32L, ingestIssue.getValue("updated_at_ms").jsonPrimitive.long)
        assertEquals("EVT-1", ingestEvent.getValue("event_id").jsonPrimitive.content)
        assertEquals(33L, ingestEvent.getValue("ts_ms").jsonPrimitive.long)
        assertEquals("todo", ingestChange.getValue("from").jsonPrimitive.content)
        assertEquals("wip", ingestChange.getValue("to").jsonPrimitive.content)
        assertEquals("pytest unit", ingest.getValue("runs").jsonArray.first().jsonObject.getValue("label").jsonPrimitive.content)
        assertFalse("timestampMs" in ingest)
        assertFalse("durationMs" in ingest)
        assertFalse("coveragePct" in ingest)

        val socket = json.parseToJsonElement(
            json.encodeToString(
                OpsSocketMessageDto(
                    type = "run_started",
                    runEvent = OpsRunEventDto(
                        repoId = "backend",
                        run = TestRunSummaryDto("deploy abc1234", OpsStatusDto.WIP),
                    ),
                ),
            ),
        ).jsonObject
        val runEvent = socket.getValue("run_event").jsonObject
        assertEquals("backend", runEvent.getValue("repo_id").jsonPrimitive.content)
        assertEquals("deploy abc1234", runEvent.getValue("run").jsonObject.getValue("label").jsonPrimitive.content)
        assertFalse("runEvent" in socket)
        assertFalse("repoId" in runEvent)

        val hostSnapshot = json.parseToJsonElement(
            json.encodeToString(
                OpsHostSnapshotDto(
                    generatedAtMs = 31L,
                    host = "local",
                    backendRuntimeLabel = "local",
                    serverPyRuntimeLabel = "local",
                    serverPyReady = true,
                    serverPyTransport = "TCP 1453",
                    serverPySelfTest = SelfTestSummaryDto(
                        status = OpsStatusDto.OK,
                        ok = true,
                        satisfiedExpectation = true,
                        textExcerpt = "healthy",
                    ),
                    arcanaSignals = listOf(OpsSignalDto("visible processes", OpsStatusDto.OK, detail = "1 codex live", meta = "local")),
                ),
            ),
        ).jsonObject
        assertEquals(31L, hostSnapshot.getValue("generated_at_ms").jsonPrimitive.long)
        assertEquals("local", hostSnapshot.getValue("backend_runtime_label").jsonPrimitive.content)
        assertEquals("local", hostSnapshot.getValue("server_py_runtime_label").jsonPrimitive.content)
        assertEquals(true, hostSnapshot.getValue("server_py_ready").jsonPrimitive.boolean)
        assertEquals("TCP 1453", hostSnapshot.getValue("server_py_transport").jsonPrimitive.content)
        assertEquals("healthy", hostSnapshot.getValue("server_py_self_test").jsonObject.getValue("text_excerpt").jsonPrimitive.content)
        assertEquals("visible processes", hostSnapshot.getValue("arcana_signals").jsonArray.first().jsonObject.getValue("label").jsonPrimitive.content)
        assertFalse("generatedAtMs" in hostSnapshot)
        assertFalse("backendRuntimeLabel" in hostSnapshot)
        assertFalse("serverPyRuntimeLabel" in hostSnapshot)
        assertFalse("serverPyReady" in hostSnapshot)
        assertFalse("serverPyTransport" in hostSnapshot)
        assertFalse("serverPySelfTest" in hostSnapshot)
        assertFalse("arcanaSignals" in hostSnapshot)
    }

    @Test
    fun testArtifactsKeepRepositoryNeutralWireNames() {
        val artifact = json.parseToJsonElement(json.encodeToString(
            TestArtifactDto(
                label = "integration",
                status = OpsStatusDto.OK,
                timestampMs = 41L,
                durationMs = 512.0,
                coveragePct = 71.0,
                kind = TestArtifactKindDto.MODEL_SELECTORS,
                sourceRevision = "abc1234",
                ledgerSha = "deadbeef",
                cases = listOf(
                    TestCaseDto(
                        name = "test_model_selector[sol-pro]",
                        scope = "tests.integration.provider",
                        status = OpsStatusDto.OK,
                        durationMs = 18.0,
                        contracts = listOf(
                            TestContractRefDto(
                                id = "integration.provider.rpc-model-selectors",
                                subsystem = "command-provider-surface",
                                subsystemName = "Command, Provider, And Transport Surface",
                                subsystemPurpose = "Protect provider boundaries.",
                                capability = "Model routing remains exact.",
                            ),
                        ),
                    ),
                ),
            ),
        )).jsonObject
        val case = artifact.getValue("cases").jsonArray.single().jsonObject
        val contract = case.getValue("contracts").jsonArray.single().jsonObject

        assertEquals("MODEL_SELECTORS", artifact.getValue("kind").jsonPrimitive.content)
        assertEquals(41L, artifact.getValue("timestamp_ms").jsonPrimitive.long)
        assertEquals("abc1234", artifact.getValue("source_revision").jsonPrimitive.content)
        assertEquals("deadbeef", artifact.getValue("ledger_sha").jsonPrimitive.content)
        assertEquals("tests.integration.provider", case.getValue("scope").jsonPrimitive.content)
        assertEquals("Command, Provider, And Transport Surface", contract.getValue("subsystem_name").jsonPrimitive.content)
        assertEquals("Protect provider boundaries.", contract.getValue("subsystem_purpose").jsonPrimitive.content)
        assertFalse("sourceRevision" in artifact)
        assertFalse("ledgerSha" in artifact)
        assertFalse("subsystemName" in contract)
        assertFalse("subsystemPurpose" in contract)
    }
}
