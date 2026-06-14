package net.sdfgsdfg.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.sdfgsdfg.data.model.IssueItemDto

@Composable
internal fun IssuePanels(
    repos: List<IssueRepoModel>,
    generatedAtMs: Long,
    pageWidth: Dp,
    motion: IssueBoardMotionState,
    drag: IssueBoardDrag,
    onCreate: (IssueRepoModel, String) -> Unit,
    onEdit: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchiveIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onDeleteIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchive: (IssueRepoModel) -> Unit,
    onMoveIssue: (IssueRepoModel, IssueItemDto, String) -> Unit,
) {
    val sortedRepos = remember(repos) { repos.sortedByDescending { it.issues.active } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sortedRepos.forEach { repo ->
            key(repo.id) {
                IssuePanel(repo, generatedAtMs, pageWidth, motion, drag, onCreate, onEdit, onArchiveIssue, onDeleteIssue, onArchive, onMoveIssue)
            }
        }
    }
}

@Composable
private fun IssuePanel(
    repo: IssueRepoModel,
    generatedAtMs: Long,
    pageWidth: Dp,
    motion: IssueBoardMotionState,
    drag: IssueBoardDrag,
    onCreate: (IssueRepoModel, String) -> Unit,
    onEdit: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchiveIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onDeleteIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchive: (IssueRepoModel) -> Unit,
    onMoveIssue: (IssueRepoModel, IssueItemDto, String) -> Unit,
) {
    val active = repo.issues.active
    val source = issueSourceBreakdown(listOf(repo.issues))
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, if (active == 0) green else amber, glowAlpha = 0.05f, borderAlpha = 0.26f)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {}
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    repo.name,
                    color = text,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Cursive,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                source.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArchiveButton(color = if (repo.issues.trash > 0) cyan else muted, count = repo.issues.trash.takeIf { it > 0 }) { onArchive(repo) }
                    StatusPill(if (active == 0) "clear" else "$active active", if (active == 0) green else amber)
                }
            }
        }
        if (pageWidth < 1180.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                issueLanes.forEach { lane ->
                    key(lane.status) {
                        IssueLane(lane, repo, generatedAtMs, motion, drag, onCreate, onEdit, onArchiveIssue, onDeleteIssue, onMoveIssue, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                issueLanes.forEach { lane ->
                    key(lane.status) {
                        IssueLane(lane, repo, generatedAtMs, motion, drag, onCreate, onEdit, onArchiveIssue, onDeleteIssue, onMoveIssue, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueLane(
    lane: IssueLaneSpec,
    repo: IssueRepoModel,
    generatedAtMs: Long,
    motion: IssueBoardMotionState,
    drag: IssueBoardDrag,
    onCreate: (IssueRepoModel, String) -> Unit,
    onEdit: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchiveIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onDeleteIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onMoveIssue: (IssueRepoModel, IssueItemDto, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val optimisticStatuses = drag.optimisticStatuses
    val items = remember(repo.id, repo.issues.items, lane.status, optimisticStatuses) { drag.items(repo, lane) }
    val slots = remember(repo.id, lane.status, items, motion.exits) {
        items.mapIndexed { index, issue -> IssueTicketSlot(repo.id, lane.status, issue, issue.ticketKey(repo.id), index, exiting = false) }
            .toMutableList()
            .apply {
                motion.exits
                    .filter { it.repoId == repo.id && it.status == lane.status }
                    .sortedBy { it.index }
                    .forEach { add(it.index.coerceIn(0, size), it) }
            }
    }
    val slotKeys = remember(slots) { slots.mapTo(mutableSetOf()) { it.key } }
    val countBackfill = if (optimisticStatuses.isEmpty()) lane.count(repo.issues) - items.size else 0
    SideEffect {
        drag.pruneTickets(repo.id, lane.status, slotKeys)
    }
    val empty = slots.isEmpty() && countBackfill <= 0
    val laneKey = "${repo.id}:${lane.status}"
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .onGloballyPositioned {
                val bounds = it.boundsInRoot()
                drag.placeLane(laneKey, bounds)
            }
            .glassSurface(
                shape = shape,
                accent = lane.color,
                glowAlpha = if (empty) 0.025f else 0.07f,
                borderAlpha = if (empty) 0.06f else 0.26f,
                neutralBorderAlpha = if (empty) 0.04f else 0.17f,
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (lane.status == "todo") MiniActionPill("+", lane.color.copy(alpha = if (empty) 0.55f else 1f)) { onCreate(repo, lane.status) }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(lane.label, color = lane.color.copy(alpha = if (empty) 0.22f else 1f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.weight(1f)) {}
        }
        slots.forEach { slot ->
            key(slot.key) {
                val motionKey = slot.issue.motionKey(repo.id)
                val suppressMotion = slot.exiting || drag.suppressesMotion(motionKey)
                val cue = if (suppressMotion) null else motion.cues[slot.key]
                val label = cue?.label
                val moveDirection = cue?.moveDirection ?: 0
                IssueTicketPlacement(slot.key) {
                    IssueTicketMotion(
                        slotKey = slot.key,
                        issueId = slot.issue.id,
                        exiting = slot.exiting,
                        label = label,
                        moveDirection = moveDirection,
                    ) {
                        IssueItemTicket(
                            lane = lane,
                            repo = repo,
                            issue = slot.issue,
                            issueCode = repo.issueCode(slot.issue),
                            generatedAtMs = generatedAtMs,
                            motionLabel = label,
                            exiting = slot.exiting,
                            onEdit = onEdit,
                            onArchiveIssue = onArchiveIssue,
                            onDeleteIssue = onDeleteIssue,
                            onDragStart = { pointer -> drag.begin(repo, slot.issue, pointer) },
                            onDrag = drag::moveBy,
                            onDragEnd = { bounds -> drag.dropTarget(bounds, onMoveIssue) },
                            onPlaced = { if (!slot.exiting) drag.placeTicket(slot.key, it) },
                        )
                    }
                }
            }
        }
        countBackfill.takeIf { it > 0 }?.let { IssueCountTicket(lane, repo, it) }
    }
}

@Composable
private fun IssueItemTicket(
    lane: IssueLaneSpec,
    repo: IssueRepoModel,
    issue: IssueItemDto,
    issueCode: String,
    generatedAtMs: Long,
    motionLabel: String? = null,
    exiting: Boolean = false,
    onEdit: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchiveIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onDeleteIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Rect) -> IssueDropTarget?,
    onPlaced: (Rect) -> Unit = {},
) {
    val ticketKey = issue.ticketKey(repo.id)
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val motionJob = remember(ticketKey) { arrayOf<Job?>(null) }
    val pickupOffset = remember(ticketKey) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val releaseOffset = remember(ticketKey) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val visualOffset = dragOffset + pickupOffset.value + releaseOffset.value
    val settling = releaseOffset.value != Offset.Zero
    val activeMotion = dragging || settling
    val dragTone = when {
        dragging -> 1f
        settling -> 0.55f
        else -> 0f
    }
    val lift by animateFloatAsState(
        targetValue = if (activeMotion) 1.025f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "issue-card-drag-lift",
    )
    val boundsRef = remember { arrayOf<Rect?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val editable = !exiting && issue.source == "arcana"
    val interactionModifier = if (!editable) {
        Modifier
    } else {
        Modifier
            .hoverable(interactionSource = interaction)
            .pointerInput(repo.id, issue.source, issue.id, issue.status) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val bounds = boundsRef[0] ?: return@awaitEachGesture
                    val center = Offset(bounds.size.width / 2f, bounds.size.height / 2f)
                    val pickupTarget = down.position - center
                    var moved = false
                    dragging = true
                    dragOffset = Offset.Zero
                    motionJob[0]?.cancel()
                    motionJob[0] = scope.launch {
                        releaseOffset.snapTo(Offset.Zero)
                        pickupOffset.snapTo(Offset.Zero)
                        pickupOffset.animateTo(pickupTarget, spring(dampingRatio = 0.56f, stiffness = 540f))
                    }
                    onDragStart(bounds.topLeft + down.position)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                moved = true
                                dragOffset += delta
                                change.consume()
                                onDrag(delta)
                            }
                            if (change.changedToUpIgnoreConsumed()) {
                                if (!moved && issue.source == "arcana") onEdit(repo, issue)
                                break
                            }
                        }
                    } finally {
                        val settleFrom = dragOffset + pickupOffset.value
                        val dropTarget = onDragEnd(bounds.offsetBy(settleFrom))
                        motionJob[0]?.cancel()
                        motionJob[0] = scope.launch {
                            releaseOffset.snapTo(settleFrom)
                            pickupOffset.snapTo(Offset.Zero)
                            dragOffset = Offset.Zero
                            dragging = false
                            if (dropTarget == null) {
                                releaseOffset.animateTo(Offset.Zero, spring(dampingRatio = 0.62f, stiffness = 360f))
                            } else {
                                releaseOffset.animateTo(dropTarget.bounds.topLeft - bounds.topLeft, tween(issueDropReleaseMs, easing = FastOutSlowInEasing))
                                dropTarget.commit()
                            }
                        }
                    }
                }
            }
    }
    IssueTicketCard(
        lane = lane,
        issue = issue,
        issueCode = issueCode,
        generatedAtMs = generatedAtMs,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (activeMotion) 100f else 0f)
            .graphicsLayer {
                translationX = visualOffset.x
                translationY = visualOffset.y
                scaleX = lift
                scaleY = lift
                alpha = if (activeMotion) 0.98f else 1f
            }
            .onGloballyPositioned {
                val bounds = it.boundsInRoot()
                boundsRef[0] = bounds
                onPlaced(bounds)
            }
            .then(interactionModifier),
        hovered = editable && hovered && !dragging,
        dragTone = dragTone,
        motionLabel = motionLabel,
        onArchive = if (editable && issue.status != "trash") { { onArchiveIssue(repo, issue) } } else null,
        onDelete = if (editable) { { onDeleteIssue(repo, issue) } } else null,
    )
}

@Composable
internal fun IssueCountTicket(lane: IssueLaneSpec, repo: IssueRepoModel, count: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(panelRaised)
            .border(BorderStroke(1.dp, lane.color.copy(alpha = 0.26f)), RoundedCornerShape(7.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(repo.name, color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(count.toString(), color = lane.color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(repo.role, color = muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        repo.note?.let {
            Text(it, color = Color(0xFFB9C5D2), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
