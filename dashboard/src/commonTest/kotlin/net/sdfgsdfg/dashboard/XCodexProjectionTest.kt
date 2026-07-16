package net.sdfgsdfg.dashboard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import net.sdfgsdfg.data.model.OpsAgentDto
import net.sdfgsdfg.data.model.OpsSessionChannelDto
import net.sdfgsdfg.data.model.OpsSessionEventDto
import net.sdfgsdfg.data.model.OpsSessionEventKindDto
import net.sdfgsdfg.data.model.OpsSessionStateDto
import net.sdfgsdfg.data.model.OpsStructuredEventDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class XCodexProjectionTest {
    @Test
    fun projectsLosslessCodexLedgerIntoOneHumanRowPerItem() {
        val user = item("user-1", "userMessage", "content" to buildJsonArray {
            add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("run pwd - thats all i want from u")) })
        })
        val commentary = item("message-1", "agentMessage", "text" to JsonPrimitive(""), "phase" to JsonPrimitive("commentary"))
        val command = item(
            "command-1",
            "commandExecution",
            "command" to JsonPrimitive("/bin/zsh -lc pwd"),
            "status" to JsonPrimitive("inProgress"),
        )
        val answer = item("message-2", "agentMessage", "text" to JsonPrimitive(""), "phase" to JsonPrimitive("final_answer"))
        val events = listOf(
            stream(1, OpsSessionChannelDto.STDIN, "run pwd - thats all i want from u "),
            stream(1, OpsSessionChannelDto.STDERR, "2026-07-15T11:31:33.407436Z ERROR codex_memories_write::phase2: Phase 2 no changes\n"),
            codex(2, "item/started", user),
            codex(3, "item/completed", user),
            codex(4, "item/started", item("reasoning-1", "reasoning", "summary" to JsonArray(emptyList()))),
            codex(5, "item/completed", item("reasoning-1", "reasoning", "summary" to JsonArray(emptyList()))),
            codex(6, "item/started", commentary),
            delta(7, "item/agentMessage/delta", "message-1", "Running "),
            stream(8, OpsSessionChannelDto.STDOUT, "Running "),
            delta(9, "item/agentMessage/delta", "message-1", "`pwd` only."),
            stream(10, OpsSessionChannelDto.STDOUT, "`pwd` only."),
            codex(11, "item/completed", item("message-1", "agentMessage", "text" to JsonPrimitive("Running `pwd` only."), "phase" to JsonPrimitive("commentary"))),
            codex(12, "item/started", command),
            delta(13, "item/commandExecution/outputDelta", "command-1", "/Users/x/project\n"),
            stream(14, OpsSessionChannelDto.STDOUT, "/Users/x/project\n"),
            codex(15, "item/completed", item(
                "command-1",
                "commandExecution",
                "command" to JsonPrimitive("/bin/zsh -lc pwd"),
                "status" to JsonPrimitive("completed"),
                "exitCode" to JsonPrimitive(0),
                "aggregatedOutput" to JsonPrimitive("/Users/x/project\n"),
            )),
            structured(16, "thread/tokenUsage/updated", buildJsonObject { put("totalTokens", JsonPrimitive(42)) }),
            structured(17, "account/rateLimits/updated", buildJsonObject { put("remaining", JsonPrimitive(99)) }),
            structured(18, "turn/completed", buildJsonObject { put("turn", buildJsonObject { put("status", JsonPrimitive("completed")) }) }),
            codex(19, "item/started", answer),
            delta(20, "item/agentMessage/delta", "message-2", "`/Users/x/project`"),
            stream(21, OpsSessionChannelDto.STDOUT, "`/Users/x/project`"),
            codex(22, "item/completed", item("message-2", "agentMessage", "text" to JsonPrimitive("`/Users/x/project`"), "phase" to JsonPrimitive("final_answer"))),
        )

        val rendered = events.xRenderedEvents()
        val items = rendered.mapNotNull { it.event.structured?.payload?.get("item") as? JsonObject }

        assertEquals(listOf("userMessage", "agentMessage", "commandExecution", "agentMessage"), items.map { it.text("type") })
        assertEquals(4, rendered.size)
        assertEquals("Running `pwd` only.", items[1].text("text"))
        assertEquals("completed", items[2].text("status"))
        assertEquals("/Users/x/project\n", items[2].text("aggregatedOutput"))
        assertEquals("`/Users/x/project`", items[3].text("text"))
        assertEquals(rendered.size, rendered.map(XRenderedEvent::key).distinct().size)
        assertFalse(rendered.any { it.event.channel == OpsSessionChannelDto.STDOUT })
        assertFalse(rendered.any { it.event.channel == OpsSessionChannelDto.STDERR })
        assertFalse(rendered.any { it.event.structured?.payload?.get("item")?.let { value -> (value as? JsonObject)?.text("type") } == "reasoning" })
    }

    @Test
    fun historyUsesTheSameStableItemProjectionAsLiveCompletion() {
        val item = item("message-1", "agentMessage", "text" to JsonPrimitive("durable answer"), "phase" to JsonPrimitive("final_answer"))
        val live = listOf(codex(1, "item/completed", item)).xRenderedEvents().single()
        val history = listOf(codex(8, "item/completed", item, history = true)).xRenderedEvents().single()

        assertEquals(live.key, history.key)
        assertEquals("durable answer", (history.event.structured?.payload?.get("item") as JsonObject).text("text"))
    }

    @Test
    fun streamedItemKeepsRowIdentityWhileAdvancingItsRevision() {
        val item = item("message-1", "agentMessage", "text" to JsonPrimitive(""), "phase" to JsonPrimitive("commentary"))
        val started = listOf(codex(1, "item/started", item)).xRenderedEvents().single()
        val streamed = listOf(
            codex(1, "item/started", item),
            delta(2, "item/agentMessage/delta", "message-1", "delta"),
        ).xRenderedEvents().single()

        assertEquals(started.key, streamed.key)
        assertFalse(started.revision == streamed.revision)
    }

    @Test
    fun foldsApprovalRequestResponseAndRawInputIntoOneAuditRow() {
        val request = structured(1, "input_request", buildJsonObject {
            put("request_id", JsonPrimitive("approval-1"))
            put("method", JsonPrimitive("item/commandExecution/requestApproval"))
            put("prompt", JsonPrimitive("Approve command · pwd"))
            put("options", buildJsonArray { add(JsonPrimitive("accept")); add(JsonPrimitive("decline")) })
        })
        val resolved = structured(3, "input_resolved", buildJsonObject {
            put("request_id", JsonPrimitive("approval-1"))
            put("method", JsonPrimitive("item/commandExecution/requestApproval"))
            put("response", buildJsonObject { put("decision", JsonPrimitive("accept")) })
        })

        val rendered = listOf(request, stream(2, OpsSessionChannelDto.STDIN, "accept"), resolved).xRenderedEvents()

        assertEquals(1, rendered.size)
        assertEquals("input_resolved", rendered.single().event.structured?.type)
        assertEquals("codex-input:approval-1", rendered.single().key)
        assertFalse(rendered.any { it.event.channel == OpsSessionChannelDto.STDIN })
    }

    @Test
    fun hidesRoutineLifecycleButRetainsOneRootCauseFailure() {
        val rendered = listOf(
            lifecycle(1, OpsSessionStateDto.READY, "Codex thread attached"),
            lifecycle(2, OpsSessionStateDto.FAILED, "Invalid Codex protocol output"),
            lifecycle(3, OpsSessionStateDto.FAILED, "Codex app-server exited with code 143"),
        ).xRenderedEvents()

        assertEquals(1, rendered.size)
        assertEquals("Invalid Codex protocol output", rendered.single().event.text)
        assertEquals("codex-failure:runtime-1", rendered.single().key)
    }

    private fun item(id: String, type: String, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) =
        JsonObject(mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type), *fields))

    private fun codex(sequence: Long, method: String, item: JsonObject, history: Boolean = false) = structured(
        sequence,
        method,
        buildJsonObject {
            put("threadId", JsonPrimitive("thread-1"))
            put("turnId", JsonPrimitive("turn-1"))
            put("item", item)
            if (history) put("history", JsonPrimitive(true))
        },
    )

    private fun delta(sequence: Long, method: String, itemId: String, delta: String) = structured(
        sequence,
        method,
        buildJsonObject {
            put("threadId", JsonPrimitive("thread-1"))
            put("turnId", JsonPrimitive("turn-1"))
            put("itemId", JsonPrimitive(itemId))
            put("delta", JsonPrimitive(delta))
        },
    )

    private fun structured(sequence: Long, type: String, payload: JsonObject) = OpsSessionEventDto(
        kind = OpsSessionEventKindDto.STRUCTURED,
        runtimeId = "runtime-1",
        agent = OpsAgentDto.CODEX,
        sequence = sequence,
        channel = OpsSessionChannelDto.SYSTEM,
        structured = OpsStructuredEventDto(type = type, phase = "codex", schema = "codex.app-server.v2", payload = payload),
    )

    private fun stream(sequence: Long, channel: OpsSessionChannelDto, text: String) = OpsSessionEventDto(
        kind = OpsSessionEventKindDto.STREAM,
        runtimeId = "runtime-1",
        agent = OpsAgentDto.CODEX,
        sequence = sequence,
        channel = channel,
        text = text,
    )

    private fun lifecycle(sequence: Long, state: OpsSessionStateDto, text: String) = OpsSessionEventDto(
        kind = OpsSessionEventKindDto.LIFECYCLE,
        runtimeId = "runtime-1",
        agent = OpsAgentDto.CODEX,
        sequence = sequence,
        state = state,
        channel = OpsSessionChannelDto.SYSTEM,
        text = text,
    )

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.content.orEmpty()
}
