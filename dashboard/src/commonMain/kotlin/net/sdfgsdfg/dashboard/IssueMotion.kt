/*
 * TODO(CMP-10241): delete this app-level issue motion workaround once Compose/Web
 * can animate ordinary item insertion/removal smoothly through the normal APIs.
 *
 * This file exists because normal Compose item insertion/removal animation was
 * not smooth enough on Compose/Web wasm in the Issues board. In practice,
 * AnimatedVisibility height expansion/shrink and column remeasurement were the
 * janky part, while card-level draw transforms were often smooth. The workaround
 * below keeps disappearing issue slots alive briefly and moves visible
 * enter/exit/move motion into graphicsLayer so each frame does less layout work.
 *
 * Current reality:
 *
 * normal Compose animation
 * -> item size/position changes
 * -> Compose/Web invalidates broadly
 * -> Skiko schedules full canvas redraw
 * -> wasm/browser main thread gets hammered
 *
 * Desired engine reality:
 *
 * normal Compose animation
 * -> one issue card / one lane region changes
 * -> Compose knows the dirty owned-layer bounds
 * -> Skiko/Web receives bounded damage
 * -> only damaged region/layer is redrawn/composited cheaply
 *
 * CMP-10241 quest-chain mapping:
 *
 * 1. Ivan PR 3012 split phases.
 *    This is the prerequisite, not the final fix. It separates performFrame,
 *    measureAndLayout, and draw, and exposes separate layout/draw invalidation
 *    concepts. That gives us the hook points to stop treating every visual
 *    change like the same broad "render the scene" event.
 *
 * 2. Dirty bounds propagation.
 *    This is the real IssueMotion killer. Compose already has concepts close to
 *    what we need: dirty owned layers / recorded draw bounds. The missing bridge
 *    is carrying those bounds through the web host path into Skiko as
 *    screen-space damage.
 *
 * 3. Skiko/Web partial redraw.
 *    This is where skiaLayer.needRender() needs to become more expressive than
 *    "schedule full canvas render." The future shape is something like
 *    needRender(bounds), damage accumulation, and partial clear/draw where
 *    possible. This is the part that would make normal AnimatedVisibility, item
 *    placement animation, and size animation viable again on wasm.
 *
 * 4. Worker + OffscreenCanvas.
 *    This is the second-stage weapon. It helps if the remaining dominant problem
 *    is main-thread render/compositor pressure. But it does not by itself prove
 *    normal Compose layout animation becomes cheap. If the full-canvas render
 *    path remains broad, moving it to a worker may reduce UI blocking but still
 *    waste a lot of work.
 *
 * 5. App revert.
 *    Once the above works, delete the retained-slot/layer-motion machinery and
 *    go back to boring Compose: AnimatedVisibility, animateItem/placement
 *    animation where available, and normal list item enter/exit motion.
 */

package net.sdfgsdfg.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.sdfgsdfg.data.model.IssueItemDto
import net.sdfgsdfg.dashboard.tools.issueFrameTrace
import net.sdfgsdfg.dashboard.tools.issueFrameTraceEnabled
import net.sdfgsdfg.dashboard.tools.profileFrameWindow

@Composable
internal fun rememberIssueBoardMotionState(board: IssueBoardModel): IssueBoardMotionState {
    val previous = remember { arrayOf<Map<String, IssueSnapshot>?>(null) }
    var retainedCues by remember { mutableStateOf<Map<String, IssueMotionCue>>(emptyMap()) }
    var retainedExits by remember { mutableStateOf<List<IssueTicketSlot>>(emptyList()) }
    val current = remember(board.repos) { board.issueSnapshots() }
    val detected = previous[0]?.takeIf { it != current }?.let { old ->
        val currentIds = current.keys
        val moved = current.values.mapNotNull { next ->
            old[next.key]
                ?.takeIf { it.repoId != next.repoId || it.status != next.status }
                ?.let { it to next }
        }
        val created = current.values.filter { it.key !in old }
        val removed = old.values.filter { it.key !in currentIds }
        val updated = current.values.filter { next ->
            old[next.key]?.let { it.repoId == next.repoId && it.status == next.status && it.issue != next.issue } == true
        }

        val nextCues = buildMap {
            updated.forEach { put(it.ticketKey, IssueMotionCue("updated")) }
            created.forEach { put(it.ticketKey, IssueMotionCue("new")) }
            moved.forEach { (old, next) ->
                put(next.ticketKey, IssueMotionCue("moved", old.moveDirectionTo(next)))
            }
        }
        val nextExits = removed.map { it.exitSlot(board.generatedAtMs) }
        IssueBoardMotionState(nextCues, nextExits)
    } ?: IssueBoardMotionState()
    val motion = IssueBoardMotionState(
        cues = retainedCues + detected.cues,
        exits = (retainedExits + detected.exits).distinctBy { it.key },
    )

    SideEffect {
        previous[0] = current
        if (detected.cues.isNotEmpty()) retainedCues = retainedCues + detected.cues
        if (detected.exits.isNotEmpty()) retainedExits = (retainedExits + detected.exits).distinctBy { it.key }
    }

    motion.cues.keys.forEach { key ->
        LaunchedEffect(key) {
            delay(issueMotionHoldMs)
            retainedCues = retainedCues - key
        }
    }

    motion.exits.forEach { exit ->
        LaunchedEffect(exit.key) {
            delay(issueMotionExitRetainMs)
            retainedExits = retainedExits.filterNot { it.key == exit.key }
        }
    }

    return motion
}

@Composable
internal fun IssueTicketPlacement(
    slotKey: String,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val positionRef = remember(slotKey) { arrayOf<Offset?>(null) }
    val motionJob = remember(slotKey) { arrayOf<Job?>(null) }
    val offset = remember(slotKey) { Animatable(Offset.Zero, Offset.VectorConverter) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                val position = it.positionInParent()
                positionRef[0]?.let { previous ->
                    val layoutDelta = previous - position
                    val rawDelta = layoutDelta + offset.value
                    when {
                        layoutDelta.x < -issuePlacementCrossAxisTolerancePx ||
                            layoutDelta.x > issuePlacementCrossAxisTolerancePx ||
                            rawDelta.y < -issuePlacementMaxDeltaPx ||
                            rawDelta.y > issuePlacementMaxDeltaPx -> {
                            motionJob[0]?.cancel()
                            motionJob[0] = scope.launch { offset.snapTo(Offset.Zero) }
                        }
                        rawDelta.x > 0.75f || rawDelta.x < -0.75f || rawDelta.y > 0.75f || rawDelta.y < -0.75f -> {
                            motionJob[0]?.cancel()
                            motionJob[0] = scope.launch {
                                offset.snapTo(Offset(0f, rawDelta.y))
                                offset.animateTo(Offset.Zero, spring(dampingRatio = 0.82f, stiffness = 360f))
                            }
                        }
                    }
                }
                positionRef[0] = position
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = offset.value.y },
        ) {
            content()
        }
    }
}

@Composable
internal fun IssueTicketMotion(
    slotKey: String,
    issueId: String,
    exiting: Boolean,
    label: String?,
    moveDirection: Int,
    content: @Composable () -> Unit,
) {
    val phase = when {
        exiting -> "exit"
        label in issueEnterLabels -> label
        else -> null
    }
    val durationMs = when {
        exiting -> issueMotionExitMs
        label == "moved" -> issueMotionMoveSlideMs
        else -> issueMotionEnterMs
    }
    val traceModifier = phase?.let {
        Modifier.profileFrameWindow(
            enabled = issueFrameTraceEnabled(),
            key = "$slotKey:$it:$durationMs",
            windowMs = durationMs + 220L,
            onSevereFrame = { sample ->
                issueFrameTrace("ticket-frame-skip") {
                    "key=$slotKey issue=$issueId phase=$it delta=${sample.deltaMs}ms frame=${sample.frame}"
                }
            },
            onSummary = { summary ->
                issueFrameTrace("ticket-frame-summary") {
                    "key=$slotKey issue=$issueId phase=$it duration=${durationMs}ms elapsed=${summary.elapsedMs}ms frames=${summary.frames} slowOver34=${summary.slowFrames} severeOver80=${summary.severeFrames} worst=${summary.worstFrameMs}ms"
                }
            },
        )
    } ?: Modifier
    var visible by remember(slotKey) { mutableStateOf(exiting || label !in issueEnterLabels) }
    LaunchedEffect(exiting) {
        visible = !exiting
    }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        label = "issue-ticket-layer-progress",
    )
    val density = LocalDensity.current
    val enterOffset = with(density) { 28.dp.toPx() }
    val moveOffset = with(density) { 34.dp.toPx() }
    Box(
        modifier = traceModifier.graphicsLayer {
            alpha = when {
                exiting -> progress
                label == "moved" -> 0.12f + progress * 0.88f
                label in issueEnterLabels -> progress
                else -> 1f
            }
            translationX = if (label == "moved") moveDirection * moveOffset * (1f - progress) else 0f
            translationY = when {
                exiting -> -enterOffset * 0.35f * (1f - progress)
                label == "moved" -> -enterOffset * 0.45f * (1f - progress)
                label in issueEnterLabels -> -enterOffset * (1f - progress)
                else -> 0f
            }
            val scale = if (label in issueEnterLabels || exiting) 0.96f + progress * 0.04f else 1f
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.5f, 0f)
        },
    ) {
        content()
    }
}

@Immutable
internal data class IssueBoardMotionState(
    val cues: Map<String, IssueMotionCue> = emptyMap(),
    val exits: List<IssueTicketSlot> = emptyList(),
)

internal val IssueBoardMotionState.active get() = cues.isNotEmpty() || exits.isNotEmpty()

@Immutable
internal data class IssueMotionCue(
    val label: String,
    val moveDirection: Int = 0,
)

private data class IssueSnapshot(
    val repoId: String,
    val issue: IssueItemDto,
    val status: String,
    val index: Int,
) {
    val key = issue.motionKey(repoId)
    val ticketKey = issue.ticketKey(repoId)
}

@Immutable
internal data class IssueTicketSlot(
    val repoId: String,
    val status: String,
    val issue: IssueItemDto,
    val key: String,
    val index: Int,
    val exiting: Boolean,
)

private fun IssueBoardModel.issueSnapshots(): Map<String, IssueSnapshot> =
    repos.flatMap { repo ->
        issueLanes.flatMap { lane ->
            lane.items(repo.issues)
                .mapIndexed { index, issue -> IssueSnapshot(repo.id, issue, issue.status, index) }
        }
    }.associateBy { it.key }

private fun IssueSnapshot.exitSlot(generatedAtMs: Long) = IssueTicketSlot(
    repoId = repoId,
    status = status,
    issue = issue,
    key = "exit:$ticketKey:$generatedAtMs",
    index = index,
    exiting = true,
)

private fun IssueSnapshot.moveDirectionTo(next: IssueSnapshot): Int {
    val from = issueLanes.indexOfFirst { it.status == status }
    val to = issueLanes.indexOfFirst { it.status == next.status }
    return when {
        from < 0 || to < 0 || from == to -> 0
        from < to -> -1
        else -> 1
    }
}
