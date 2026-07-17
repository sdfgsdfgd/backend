package net.sdfgsdfg.dashboard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XArcanaProjectionTest {
    @Test
    fun structuredRoundOwnsItsTerminalMirrorWhileRealOutputSurvives() {
        val semantic = listOf(
            response(7, command("alpha", "value" to "v")),
            state(8, "running"),
            state(9, "awaiting_acceptance", "changes_known" to true, "has_changes" to false),
            lifecycle(10, OpsSessionStateDto.STOPPED, "normal exit"),
        )
        val mirror = listOf(
            stream(1, "╭ Round Cockpit ─╮\n│ root: r         │\n"),
            stream(2, "╰────────────────╯\n╭ MeMoRia ────────╮\n│ memory: m       │\n╰────────────────╯\n"),
            stream(3, "╭ Command Preview ╮\n│ {\"name\":\"alpha\""),
            stream(4, ",\"args\":{}} │\n╰─────────────────╯\nresult\n"),
        )
        fun unowned(sequence: Long) = stream(sequence, "╭ MeMoRia ─╮\n│ unowned  │\n╰──────────╯\n")
        fun unownedCommand(sequence: Long) = stream(
            sequence,
            "╭ Command Preview ╮\n│ {\"name\":\"alphabet\",\"args\":{\"note\":\"alpha\"}} │\n╰─────────────────╯\n",
        )

        listOf(
            listOf(unowned(-2), unownedCommand(-1)) + mirror,
            mirror + listOf(unowned(5), unownedCommand(6)),
        ).forEach { terminal ->
            listOf(semantic + terminal, terminal + semantic).forEach { events ->
                val rendered = events.xRenderedEvents()
                val output = rendered.mapNotNull { it.text?.text }.joinToString("\n")

                assertEquals(1, rendered.count { it.event.structured?.type == "agent_response" })
                assertEquals(1, rendered.count { it.event.structured?.type == "session_state" })
                assertEquals("awaiting_acceptance", rendered.single { it.event.structured?.type == "session_state" }.event.structured?.payload?.text("status"))
                assertTrue(output.contains("result"))
                assertTrue(output.contains("unowned"))
                assertFalse(output.contains("Round Cockpit"))
                assertEquals(1, Regex("Command Preview").findAll(output).count())
                assertTrue(output.contains("\"name\":\"alphabet\""))
                assertFalse(output.contains("\"name\":\"alpha\""))
                assertFalse(rendered.any { it.event.text == "normal exit" })
                val response = rendered.single { it.event.structured?.type == "agent_response" }
                assertEquals("arcana-round:runtime-1:3_x:4", response.key)
                assertTrue(response.latestArcanaResponse)
            }
        }
    }

    @Test
    fun typedGateOwnsAskUserPresentationWithoutEatingAnswerOrCommandResult() {
        val prompt = "Choose the intentionally long option whose visible terminal copy wraps across several lines"
        val events = listOf(
            response(1, command("ask_user_input", "question" to prompt)),
            state(2, "running"),
            stream(3, "╭ ✦ ASK USER ✦ ─╮\n│ ? question · Choose the intentionally long │\n"),
            stream(4, "│   option whose visible terminal copy wraps │\n╰ answer required · Enter submits —————————╯\n"),
            input(5, "ask_user_input", prompt),
            stdin(6, "A"),
            stream(7, "answer: A\n"),
        )

        val rendered = events.xRenderedEvents()

        assertEquals(listOf("agent_response", "input_request", null, null), rendered.map { it.event.structured?.type })
        assertEquals("A", rendered[2].text?.text)
        assertEquals("answer: A", rendered.last().text?.text)
        assertFalse(rendered.any { it.text?.text?.contains("ASK USER") == true })
        assertFalse(rendered.any { it.event.structured?.payload?.text("status") == "running" })
    }

    @Test
    fun foldsStateBySemanticIdentity() {
        val awaiting = listOf(
            state(1, "awaiting_acceptance", "changes_known" to true, "has_changes" to false),
            input(2, "objective_acceptance", "Finish?"),
        )
        val concluded = awaiting + state(3, "concluded", "changes_known" to true, "has_changes" to false)

        assertEquals(listOf("input_request"), awaiting.xRenderedEvents().map { it.event.structured?.type })

        val state = concluded.xRenderedEvents().single { it.event.structured?.type == "session_state" }
        assertEquals("concluded", state.event.structured?.payload?.text("status"))
        assertEquals("arcana-state:runtime-1", state.key)
    }

    @Test
    fun errorOwnsFollowingLifecycleFailure() {
        val rendered = listOf(
            error(1, "Provider request failed"),
            lifecycle(2, OpsSessionStateDto.FAILED, "Arcana exited with code 1"),
        ).xRenderedEvents()

        assertEquals(1, rendered.size)
        assertEquals("Provider request failed", rendered.single().event.text)
        assertEquals(OpsSessionEventKindDto.ERROR, rendered.single().event.kind)
    }

    @Test
    fun activityEdgesOwnOneEphemeralStateOutsideTheTranscript() {
        val first = activity(1, "first", "started", "Inspecting")
        val second = activity(2, "second", "started", "Reasoning")
        val output = stream(3, "semantic output")
        val active = listOf(first, second, output)

        assertEquals(XArcanaActivity("second", "Reasoning"), active.xActiveArcanaActivity())
        assertEquals(listOf("semantic output"), active.xRenderedEvents().mapNotNull { it.text?.text })
        assertFalse(active.xRenderedEvents().any { it.event.structured?.type == "activity" })
        assertEquals(XArcanaActivity("first", "Inspecting"), (active + activity(4, "second", "completed", "Reasoning")).xActiveArcanaActivity())
        assertEquals(XArcanaActivity("second", "Reasoning"), (active + activity(4, "first", "completed", "Inspecting")).xActiveArcanaActivity())
        assertNull((active + activity(4, "first", "completed", "Inspecting") + activity(5, "second", "completed", "Reasoning")).xActiveArcanaActivity())
        assertNull((active + lifecycle(5, OpsSessionStateDto.STOPPED, "stopped")).xActiveArcanaActivity())
        assertNull((active + state(5, "concluded").copy(state = OpsSessionStateDto.CONCLUDED)).xActiveArcanaActivity())
    }

    @Test
    fun phaseTransitionsRenderOneBeaconPerStageEntry() {
        val rendered = listOf(
            phase(1, "phase1", "started"),
            phase(2, "phase1", "completed"),
            phase(3, "phase2", "started"),
            phase(4, "phase2", "completed"),
            phase(5, "3_x", "started"),
        ).xRenderedEvents()

        assertEquals(listOf("phase1", "phase2", "3_x"), rendered.map { it.event.structured?.phase })
        assertTrue(rendered.all { it.event.structured?.payload?.text("state") == "started" })
    }

    private fun response(sequence: Long, command: JsonObject) = structured(
        sequence,
        "agent_response",
        buildJsonObject {
            put("root_objective", JsonPrimitive("r"))
            put("current_objective", JsonPrimitive("c"))
            put("text", JsonPrimitive("t"))
            put("memory", JsonPrimitive("m"))
            put("command", command)
        },
        round = 4,
    )

    private fun input(sequence: Long, kind: String, prompt: String) = structured(
        sequence,
        "input_request",
        buildJsonObject {
            put("kind", JsonPrimitive(kind))
            put("prompt", JsonPrimitive(prompt))
            put("allow_empty", JsonPrimitive(false))
        },
    )

    private fun state(sequence: Long, status: String, vararg fields: Pair<String, Boolean>) = structured(
        sequence,
        "session_state",
        buildJsonObject {
            put("status", JsonPrimitive(status))
            fields.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
        },
    )

    private fun activity(sequence: Long, id: String, status: String, label: String) = structured(
        sequence,
        "activity",
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("status", JsonPrimitive(status))
            put("label", JsonPrimitive(label))
        },
        schema = "arcana.activity.v1",
    )

    private fun phase(sequence: Long, phase: String, state: String) = structured(
        sequence,
        "phase",
        buildJsonObject { put("state", JsonPrimitive(state)) },
        schema = "arcana.phase.v1",
        phase = phase,
    )

    private fun structured(
        sequence: Long,
        type: String,
        payload: JsonObject,
        round: Int? = null,
        schema: String = "3_x",
        phase: String = "3_x",
    ) = OpsSessionEventDto(
        kind = OpsSessionEventKindDto.STRUCTURED,
        runtimeId = "runtime-1",
        agent = OpsAgentDto.ARCANA,
        sequence = sequence,
        channel = OpsSessionChannelDto.SYSTEM,
        structured = OpsStructuredEventDto(type = type, phase = phase, schema = schema, round = round, payload = payload),
    )

    private fun command(name: String, vararg args: Pair<String, String>) = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("args", buildJsonObject { args.forEach { (key, value) -> put(key, JsonPrimitive(value)) } })
    }

    private fun stream(sequence: Long, text: String) = OpsSessionEventDto(
        kind = OpsSessionEventKindDto.STREAM,
        runtimeId = "runtime-1",
        agent = OpsAgentDto.ARCANA,
        sequence = sequence,
        channel = OpsSessionChannelDto.STDOUT,
        text = text,
    )

    private fun stdin(sequence: Long, text: String) = OpsSessionEventDto(
        kind = OpsSessionEventKindDto.STREAM,
        runtimeId = "runtime-1",
        agent = OpsAgentDto.ARCANA,
        sequence = sequence,
        channel = OpsSessionChannelDto.STDIN,
        text = text,
    )

    private fun lifecycle(sequence: Long, state: OpsSessionStateDto, text: String) = OpsSessionEventDto(
        kind = OpsSessionEventKindDto.LIFECYCLE,
        runtimeId = "runtime-1",
        agent = OpsAgentDto.ARCANA,
        sequence = sequence,
        state = state,
        channel = OpsSessionChannelDto.SYSTEM,
        text = text,
    )

    private fun error(sequence: Long, text: String) = OpsSessionEventDto(
        kind = OpsSessionEventKindDto.ERROR,
        runtimeId = "runtime-1",
        agent = OpsAgentDto.ARCANA,
        sequence = sequence,
        state = OpsSessionStateDto.FAILED,
        channel = OpsSessionChannelDto.STDERR,
        text = text,
    )

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.content.orEmpty()
}
