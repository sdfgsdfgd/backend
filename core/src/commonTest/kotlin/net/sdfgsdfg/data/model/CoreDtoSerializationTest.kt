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
        val request = json.parseToJsonElement(
            json.encodeToString(SelfTestRequestDto(expectSubstr = "OK", newChat = true)),
        ).jsonObject
        assertEquals("OK", request.getValue("expect_substr").jsonPrimitive.content)
        assertEquals(true, request.getValue("new_chat").jsonPrimitive.boolean)
        assertFalse("expectSubstr" in request)
        assertFalse("newChat" in request)

        val result = json.parseToJsonElement(
            json.encodeToString(
                SelfTestResultDto(
                    ok = true,
                    textExcerpt = "healthy",
                    rawError = null,
                    askLatencyMs = 12.0,
                    auditLatencyMs = 3.0,
                    satisfiedExpectation = true,
                    timestampMs = 42L,
                ),
            ),
        ).jsonObject
        assertEquals("healthy", result.getValue("text_excerpt").jsonPrimitive.content)
        assertEquals(true, result.getValue("satisfied_expectation").jsonPrimitive.boolean)
        assertFalse("textExcerpt" in result)
        assertFalse("satisfiedExpectation" in result)
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
                        selfTest = SelfTestSummaryDto(
                            status = OpsStatusDto.OK,
                            ok = true,
                            satisfiedExpectation = true,
                            timestampMs = 11L,
                            latencyMs = 44.0,
                            askLatencyMs = 33.0,
                            auditLatencyMs = 7.0,
                            textExcerpt = "healthy",
                            caseCount = 1,
                            casePassCount = 1,
                            zenPresent = true,
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
        val selfTest = repo.getValue("self_test").jsonObject
        val selfTestCase = selfTest.getValue("cases").jsonArray.first().jsonObject
        assertEquals(7L, obj.getValue("generated_at_ms").jsonPrimitive.long)
        assertEquals("backend.service", repo.getValue("service_name").jsonPrimitive.content)
        assertEquals(9L, run.getValue("timestamp_ms").jsonPrimitive.long)
        assertEquals(12.0, run.getValue("duration_ms").jsonPrimitive.double)
        assertEquals("public ingress", pyramidRun.getValue("label").jsonPrimitive.content)
        assertEquals(true, selfTest.getValue("satisfied_expectation").jsonPrimitive.boolean)
        assertEquals(33.0, selfTest.getValue("ask_latency_ms").jsonPrimitive.double)
        assertEquals(7.0, selfTest.getValue("audit_latency_ms").jsonPrimitive.double)
        assertEquals("healthy", selfTest.getValue("text_excerpt").jsonPrimitive.content)
        assertEquals(1, selfTest.getValue("case_count").jsonPrimitive.content.toInt())
        assertEquals(1, selfTest.getValue("case_pass_count").jsonPrimitive.content.toInt())
        assertEquals(true, selfTest.getValue("zen_present").jsonPrimitive.boolean)
        assertEquals("https://github.com/x/backend/actions/runs/1", selfTest.getValue("workflow_url").jsonPrimitive.content)
        assertEquals("server-py-selftest.json", selfTest.getValue("artifacts").jsonArray.first().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("5.5-thinking-heavy", selfTestCase.getValue("name").jsonPrimitive.content)
        assertFalse("generatedAtMs" in obj)
        assertFalse("serviceName" in repo)
        assertFalse("latestRun" in repo)
        assertFalse("selfTest" in repo)
    }
}
