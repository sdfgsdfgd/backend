package net.sdfgsdfg.dashboard

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import net.sdfgsdfg.data.model.OpsAgentDto
import net.sdfgsdfg.data.model.OpsSessionChannelDto
import net.sdfgsdfg.data.model.OpsSessionEventDto
import net.sdfgsdfg.data.model.OpsSessionEventKindDto
import net.sdfgsdfg.data.model.OpsSessionStateDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XTerminalAnsiTest {
    @Test
    fun preservesAnsiStateAcrossTransportChunksWithoutLeakingEscapes() {
        val rendered = listOf(
            event(1, "\u001B[38;2;82;"),
            event(2, "91;97m— ritual —\u001B[0m"),
        ).xRenderedEvents().single()

        assertEquals("— ritual —", rendered.text?.text)
        assertEquals(Color(82, 91, 97).copy(alpha = 0.92f), rendered.text?.spanStyles?.single()?.item?.color)
        assertFalse(rendered.text.orEmpty().text.contains('\u001B'))
    }

    @Test
    fun coalescedStreamKeepsRowIdentityWhileAdvancingItsRevision() {
        val first = event(1, "alpha", OpsAgentDto.ARCANA)
        val second = event(2, " beta", OpsAgentDto.ARCANA)

        val rendered = listOf(first, second).xRenderedEvents().single()

        assertEquals(first.xTransportKey(), rendered.key)
        assertEquals(second.xTransportKey(), rendered.revision)
    }

    @Test
    fun boundsAPlainArcanaStreamWithoutChangingItsVisibleText() {
        val first = event(1, "a".repeat(4_096), OpsAgentDto.ARCANA)
        val second = event(2, "b".repeat(97), OpsAgentDto.ARCANA)

        val rendered = listOf(first, second).xRenderedEvents()

        assertEquals((first.text + second.text).orEmpty(), rendered.joinToString("") { it.text?.text.orEmpty() })
        assertEquals(listOf(first.xTransportKey(), second.xTransportKey()), rendered.map(XRenderedEvent::key))
        assertEquals(first.xTransportKey(), rendered.first().revision)
        assertEquals(second.xTransportKey(), rendered.last().revision)
        assertTrue(rendered.last().streamContinuation)
    }

    @Test
    fun coalescesAdjacentStderrStreamsWithoutLosingTheirTransportRevision() {
        val first = event(1, "Cloning into 'repo'...\n", channel = OpsSessionChannelDto.STDERR)
        val second = event(2, "done.\n", channel = OpsSessionChannelDto.STDERR)

        val rendered = listOf(first, second).xRenderedEvents().single()

        assertEquals("Cloning into 'repo'...\ndone.", rendered.text?.text)
        assertEquals(first.xTransportKey(), rendered.key)
        assertEquals(second.xTransportKey(), rendered.revision)
    }

    @Test
    fun firstSandboxLineProjectsWithoutWaitingForAnotherEvent() {
        val first = event(1, "[sandbox] checking Docker image python-client against current Arcana dependencies\n")
        val ledger = XSessionLedger()

        ledger.append(first)

        assertEquals(1, ledger.presentationRevision("ritual"))
        assertEquals(first.text?.trimEnd(), ledger.snapshot().xRenderedEvents().single().text?.text)
    }

    @Test
    fun keepsStderrBlocksSeparateAcrossAStreamBoundary() {
        val first = event(1, "first\n", channel = OpsSessionChannelDto.STDERR)
        val stdout = event(2, "middle\n")
        val second = event(3, "last\n", channel = OpsSessionChannelDto.STDERR)

        assertEquals(listOf("first", "middle", "last"), listOf(first, stdout, second).xRenderedEvents().map { it.text?.text })
    }

    @Test
    fun projectsLegacyTuiWorkingAtEveryTransportBoundary() {
        val shimmer = "\u001B[1;38;2;165;171;173m"
        val elapsed = "\u001B[38;2;98;107;113m"
        val reset = "\u001B[0m"
        val rpc = " - [rpc] endpoint=https://example.test/api/ask request_id=opaque\n"
        val pcap = " - [rpc-pcap] capturing Leg A FIN/RST request_id=opaque\n"
        val working = "\r${shimmer}• Working            🌒$reset ${elapsed}(16s)$reset$rpc" +
            "\r${shimmer}• Working            🌔$reset ${elapsed}(18s)$reset$pcap"

        for (split in 1 until working.length) {
            val events = listOf(
                event(8, working.substring(0, split), OpsAgentDto.ARCANA),
                event(9, working.substring(split), OpsAgentDto.ARCANA),
            )

            assertEquals(XArcanaActivity("legacy:ritual", "Working"), events.xActiveArcanaActivity(), "active split=$split")
            assertEquals((rpc + pcap).trimEnd(), events.xRenderedEvents().joinToString("") { it.text?.text.orEmpty() }, "active split=$split")
        }

        val completed = working + "\r${" ".repeat(79)}\rWorked for 18s\ncomplete"
        for (split in 1 until completed.length) {
            val events = listOf(
                event(8, completed.substring(0, split), OpsAgentDto.ARCANA),
                event(9, completed.substring(split), OpsAgentDto.ARCANA),
            )

            assertNull(events.xActiveArcanaActivity(), "completed split=$split")
            assertEquals(
                (rpc + pcap + "Worked for 18s\ncomplete").trimEnd(),
                events.xRenderedEvents().joinToString("") { it.text?.text.orEmpty() },
                "completed split=$split",
            )
        }
    }

    @Test
    fun retainsOneUnsequencedReplayGapInArrivalOrder() {
        val gap = OpsSessionEventDto(
            kind = OpsSessionEventKindDto.ERROR,
            runtimeId = "ritual",
            state = OpsSessionStateDto.RUNNING,
            channel = OpsSessionChannelDto.SYSTEM,
            text = "Replay starts at sequence 42; older output expired",
            replay = true,
        )
        val tail = event(42, "tail")

        val ledger = XSessionLedger()
        ledger.append(gap)
        ledger.append(tail)
        ledger.append(gap)
        val events = ledger.snapshot().xRuntimeEvents("ritual")

        assertEquals(2, events.size)
        assertEquals(gap, events.first())
        assertEquals(tail, events.last())
    }

    @Test
    fun boundedClientLedgerBudgetsReplayGapOutsideOrderedTail() {
        val gap = OpsSessionEventDto(
            kind = OpsSessionEventKindDto.ERROR,
            runtimeId = "ritual",
            state = OpsSessionStateDto.RUNNING,
            channel = OpsSessionChannelDto.SYSTEM,
            text = "Replay starts at sequence 42; older output expired",
            replay = true,
        )
        val ledger = XSessionLedger(limit = 4)

        ledger.append(gap)
        (42L..46L).forEach { ledger.append(event(it, "tail $it")) }
        val events = ledger.snapshot()

        assertEquals(5, events.size)
        assertEquals(gap, events.first())
        assertEquals(listOf(43L, 44L, 45L, 46L), events.mapNotNull(OpsSessionEventDto::sequence))

        val newerGap = gap.copy(text = "Replay starts at sequence 43; older output expired")
        ledger.append(newerGap)
        assertEquals(listOf(newerGap), ledger.snapshot().filter { it.replay && it.sequence == null })
    }

    @Test
    fun unsequencedTransportIdentityIsTheWholeEvent() {
        val event = OpsSessionEventDto(
            kind = OpsSessionEventKindDto.ERROR,
            runtimeId = "ritual",
            channel = OpsSessionChannelDto.SYSTEM,
            text = "boundary",
        )

        assertEquals(event, event.xTransportKey())
        assertFalse(event.xTransportKey() == event.copy(replay = true).xTransportKey())
    }

    @Test
    fun boundedLedgerSeparatesControlDeliveryFromStreamRetention() {
        val ledger = XSessionLedger(limit = 2)
        val lifecycle = event(2, "running").copy(
            kind = OpsSessionEventKindDto.LIFECYCLE,
            state = OpsSessionStateDto.RUNNING,
            channel = OpsSessionChannelDto.SYSTEM,
        )
        val failure = event(4, "failed").copy(
            kind = OpsSessionEventKindDto.ERROR,
            state = OpsSessionStateDto.FAILED,
            channel = OpsSessionChannelDto.STDERR,
        )
        val accepted = listOf(
            event(1, "one"),
            lifecycle,
            event(3, "three").copy(state = OpsSessionStateDto.RUNNING),
            failure,
        )

        accepted.forEach(ledger::append)

        assertEquals(4, ledger.presentationRevision("ritual"))
        assertEquals(2, ledger.runtimeViewRevision("ritual"))
        assertEquals(0, ledger.presentationRevision("other-runtime"))
        assertEquals(listOf(lifecycle, failure), List(2) { ledger.controlEvents.tryReceive().getOrNull() })
        assertNull(ledger.controlEvents.tryReceive().getOrNull())
        assertEquals(listOf(3L, 4L), ledger.snapshot().mapNotNull(OpsSessionEventDto::sequence))

        ledger.append(failure)
        assertEquals(4, ledger.presentationRevision("ritual"))
        assertEquals(2, ledger.runtimeViewRevision("ritual"))
        assertNull(ledger.controlEvents.tryReceive().getOrNull())
    }

    @Test
    fun correlatedControlReceiptWakesWithoutObservingStreamTraffic() = runTest {
        val ledger = XSessionLedger()
        val receipt = OpsSessionEventDto(
            kind = OpsSessionEventKindDto.SESSIONS,
            requestId = "sessions-request",
        )
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            ledger.awaitControl { it.requestId == receipt.requestId }
        }

        ledger.append(event(1, "ordinary stream").copy(state = OpsSessionStateDto.RUNNING))
        assertFalse(pending.isCompleted)
        ledger.append(receipt)

        assertEquals(receipt, withTimeout(1_000) { pending.await() })
    }

    private fun event(
        sequence: Long,
        text: String,
        agent: OpsAgentDto? = null,
        channel: OpsSessionChannelDto = OpsSessionChannelDto.STDOUT,
    ) = OpsSessionEventDto(
        kind = OpsSessionEventKindDto.STREAM,
        runtimeId = "ritual",
        agent = agent,
        sequence = sequence,
        channel = channel,
        text = text,
    )

    private fun androidx.compose.ui.text.AnnotatedString?.orEmpty() = this ?: androidx.compose.ui.text.AnnotatedString("")
}
