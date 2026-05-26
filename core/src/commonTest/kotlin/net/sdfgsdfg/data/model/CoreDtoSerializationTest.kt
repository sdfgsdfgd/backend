package net.sdfgsdfg.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// Purpose: protect public JSON wire names shared by backend, server_py,
// GitHub Actions artifacts, and the dashboard clients. These are compatibility
// tests, not generic serialization coverage; keep them only for DTOs that cross
// repo/process boundaries.
class CoreDtoSerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun askRequestKeepsPublicWireNames() {
        val encoded = json.encodeToString(
            AskRequestDto(
                prompt = "ping",
                requestId = "req-1",
                deepseekSearch = true,
                sessionId = "session-1",
            ),
        )

        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("req-1", obj.getValue("request_id").jsonPrimitive.content)
        assertEquals("session-1", obj.getValue("session_id").jsonPrimitive.content)
        assertEquals(true, obj.getValue("deepseek_search").jsonPrimitive.boolean)
        assertFalse("requestId" in obj)
        assertFalse("sessionId" in obj)
        assertFalse("deepseekSearch" in obj)
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
                        location = "/repo",
                        runtimeLabel = "local preview",
                        serviceName = "backend.service",
                        latestRun = TestRunSummaryDto(
                            label = "smoke",
                            status = OpsStatusDto.OK,
                            timestampMs = 9L,
                            durationMs = 12.0,
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
        val selfTest = repo.getValue("self_test").jsonObject
        val selfTestCase = selfTest.getValue("cases").jsonArray.first().jsonObject
        assertEquals(7L, obj.getValue("generated_at_ms").jsonPrimitive.long)
        assertEquals("local preview", repo.getValue("runtime_label").jsonPrimitive.content)
        assertEquals("backend.service", repo.getValue("service_name").jsonPrimitive.content)
        assertEquals(9L, run.getValue("timestamp_ms").jsonPrimitive.long)
        assertEquals(12.0, run.getValue("duration_ms").jsonPrimitive.double)
        assertEquals("public ingress", pyramidRun.getValue("label").jsonPrimitive.content)
        assertEquals("deploy abc1234", historyRun.getValue("label").jsonPrimitive.content)
        assertEquals(13L, historyRun.getValue("timestamp_ms").jsonPrimitive.long)
        assertEquals(44.0, historyRun.getValue("duration_ms").jsonPrimitive.double)
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
        assertFalse("serviceName" in repo)
        assertFalse("runtimeLabel" in repo)
        assertFalse("latestRun" in repo)
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
                    detail = "unit spine passed",
                    issues = IssueSummaryDto(
                        todo = 2,
                        wip = 1,
                        done = 3,
                        sources = listOf(IssueSourceSummaryDto("arcana", "Arcana issues", todo = 2, wip = 1, done = 3)),
                    ),
                    runs = listOf(TestRunSummaryDto("pytest unit", OpsStatusDto.OK)),
                ),
            ),
        ).jsonObject
        assertEquals(21L, ingest.getValue("timestamp_ms").jsonPrimitive.long)
        assertEquals(123.0, ingest.getValue("duration_ms").jsonPrimitive.double)
        assertEquals("arcana", ingest.getValue("issues").jsonObject.getValue("sources").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("pytest unit", ingest.getValue("runs").jsonArray.first().jsonObject.getValue("label").jsonPrimitive.content)
        assertFalse("timestampMs" in ingest)
        assertFalse("durationMs" in ingest)
    }
}
