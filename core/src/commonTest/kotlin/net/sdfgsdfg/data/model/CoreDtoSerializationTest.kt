package net.sdfgsdfg.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
}
