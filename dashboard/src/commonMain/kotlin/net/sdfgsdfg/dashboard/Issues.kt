package net.sdfgsdfg.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import net.sdfgsdfg.data.model.IssueEventDto
import net.sdfgsdfg.data.model.IssueItemDto
import net.sdfgsdfg.data.model.IssueMutationRequestDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.RepoHealthDto

@Composable
internal fun Issues(
    loadState: OpsLoadState,
    pageWidth: Dp,
    onSummary: (OpsSummaryDto) -> Unit,
    onEditorActiveChanged: (Boolean) -> Unit = {},
) {
    var editor by remember { mutableStateOf<IssueEditorState?>(null) }
    var archiveRepo by remember { mutableStateOf<RepoHealthDto?>(null) }
    var drag by remember { mutableStateOf<IssueDragState?>(null) }
    var dragTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dragRootBounds by remember { mutableStateOf<Rect?>(null) }
    val rootBounds = remember { arrayOf<Rect?>(null) }
    val laneBounds = remember { mutableMapOf<String, Rect>() }

    fun mutate(request: IssueMutationRequestDto) = mutateIssue(
        request = request,
        onLoaded = { summary ->
            onSummary(summary)
            archiveRepo = archiveRepo?.let { open -> summary.repos.firstOrNull { it.id == open.id } }
            error = null
        },
        onFailed = { error = it },
    )
    LaunchedEffect(editor != null) {
        onEditorActiveChanged(editor != null)
    }

    when (loadState) {
        OpsLoadState.Loading -> WorkSurface(
            title = "Issues",
            detail = "Waiting for local .arcana/issues.json summaries before shaping lanes.",
            items = issueLanes.map { it.label },
        )
        is OpsLoadState.Failed -> WorkSurface(
            title = "Issues Unavailable",
            detail = loadState.message,
            items = listOf("/api/ops/summary", "issue summary DTO", "repo lanes"),
        )
        is OpsLoadState.Ready -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { rootBounds[0] = it.boundsInRoot() },
        ) {
            val motion = rememberIssueBoardMotionState(loadState.summary)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                error?.let { Text(it, color = rose, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                IssuePanels(
                    repos = loadState.summary.repos,
                    generatedAtMs = loadState.summary.generatedAtMs,
                    pageWidth = pageWidth,
                    motion = motion,
                    dropTargetRepoId = dragTarget?.first,
                    dropTargetStatus = dragTarget?.second,
                    laneBounds = laneBounds,
                    onCreate = { repo, status -> editor = IssueEditorState(repo.id, status) },
                    onEdit = { repo, issue -> editor = IssueEditorState(repo.id, issue.status, issue.id, issue.issueEditorText()) },
                    onArchiveIssue = { repo, issue -> mutate(IssueMutationRequestDto("trash", repo.id, id = issue.id, status = "trash")) },
                    onDeleteIssue = { repo, issue -> mutate(IssueMutationRequestDto("delete", repo.id, id = issue.id)) },
                    onArchive = { archiveRepo = it },
                    onDragStart = { repo, lane, issue, issueCode, bounds, grabOffset ->
                        dragRootBounds = rootBounds[0]
                        drag = IssueDragState(
                            repo = repo,
                            lane = lane,
                            issue = issue,
                            issueCode = issueCode,
                            status = issue.status,
                            sourceBounds = bounds,
                            grabOffset = grabOffset,
                            pointer = bounds.topLeft + grabOffset,
                        )
                        dragTarget = laneBounds.targetAt(bounds.topLeft + grabOffset)
                    },
                    onDrag = { delta ->
                        drag = drag?.let {
                            val next = it.copy(pointer = it.pointer + delta)
                            val target = laneBounds.targetAt(next.pointer)
                            if (target != dragTarget) dragTarget = target
                            next
                        }
                    },
                    onDragEnd = {
                        val current = drag
                        val target = current?.let { laneBounds.targetAt(it.pointer) }
                        if (current != null && target != null) {
                            val (repoId, status) = target
                            if (repoId == current.repo.id && status != current.status) {
                                mutate(IssueMutationRequestDto("move", repoId, id = current.issue.id, status = status))
                            }
                        }
                        drag = null
                        dragTarget = null
                        dragRootBounds = null
                    },
                )
                IssueEventStrip(loadState.summary)
            }
            drag?.let { IssueDragOverlay(it, loadState.summary.generatedAtMs, dragRootBounds) }
        }
    }
    editor?.let { state ->
        IssueEditorDialog(
            state = state,
            onDismiss = { editor = null },
            onSave = { text ->
                val body = text.trim()
                if (body.isNotBlank()) {
                    mutate(IssueMutationRequestDto(if (state.id == null) "create" else "update", state.repoId, state.id, state.status, body))
                }
                editor = null
            },
        )
    }
    archiveRepo?.let { repo ->
        ArchiveDialog(
            repo = repo,
            onDelete = { issue -> mutate(IssueMutationRequestDto("delete", repo.id, id = issue.id)) },
            onDismiss = { archiveRepo = null },
        )
    }
}

@Composable
private fun IssuePanels(
    repos: List<RepoHealthDto>,
    generatedAtMs: Long,
    pageWidth: Dp,
    motion: IssueBoardMotionState,
    dropTargetRepoId: String?,
    dropTargetStatus: String?,
    laneBounds: MutableMap<String, Rect>,
    onCreate: (RepoHealthDto, String) -> Unit,
    onEdit: (RepoHealthDto, IssueItemDto) -> Unit,
    onArchiveIssue: (RepoHealthDto, IssueItemDto) -> Unit,
    onDeleteIssue: (RepoHealthDto, IssueItemDto) -> Unit,
    onArchive: (RepoHealthDto) -> Unit,
    onDragStart: (RepoHealthDto, IssueLaneSpec, IssueItemDto, String, Rect, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val sortedRepos = remember(repos) { repos.sortedByDescending { it.issues.active } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sortedRepos.forEach { repo ->
            key(repo.id) {
                IssuePanel(repo, generatedAtMs, pageWidth, motion, dropTargetRepoId, dropTargetStatus, laneBounds, onCreate, onEdit, onArchiveIssue, onDeleteIssue, onArchive, onDragStart, onDrag, onDragEnd)
            }
        }
    }
}

@Composable
private fun IssuePanel(
    repo: RepoHealthDto,
    generatedAtMs: Long,
    pageWidth: Dp,
    motion: IssueBoardMotionState,
    dropTargetRepoId: String?,
    dropTargetStatus: String?,
    laneBounds: MutableMap<String, Rect>,
    onCreate: (RepoHealthDto, String) -> Unit,
    onEdit: (RepoHealthDto, IssueItemDto) -> Unit,
    onArchiveIssue: (RepoHealthDto, IssueItemDto) -> Unit,
    onDeleteIssue: (RepoHealthDto, IssueItemDto) -> Unit,
    onArchive: (RepoHealthDto) -> Unit,
    onDragStart: (RepoHealthDto, IssueLaneSpec, IssueItemDto, String, Rect, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val active = repo.issues.active
    val source = issueSourceBreakdown(listOf(repo))
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
                        IssueLane(lane, repo, generatedAtMs, motion, dropTargetRepoId == repo.id && dropTargetStatus == lane.status, laneBounds, onCreate, onEdit, onArchiveIssue, onDeleteIssue, onDragStart, onDrag, onDragEnd, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                issueLanes.forEach { lane ->
                    key(lane.status) {
                        IssueLane(lane, repo, generatedAtMs, motion, dropTargetRepoId == repo.id && dropTargetStatus == lane.status, laneBounds, onCreate, onEdit, onArchiveIssue, onDeleteIssue, onDragStart, onDrag, onDragEnd, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueLane(
    lane: IssueLaneSpec,
    repo: RepoHealthDto,
    generatedAtMs: Long,
    motion: IssueBoardMotionState,
    targeted: Boolean,
    laneBounds: MutableMap<String, Rect>,
    onCreate: (RepoHealthDto, String) -> Unit,
    onEdit: (RepoHealthDto, IssueItemDto) -> Unit,
    onArchiveIssue: (RepoHealthDto, IssueItemDto) -> Unit,
    onDeleteIssue: (RepoHealthDto, IssueItemDto) -> Unit,
    onDragStart: (RepoHealthDto, IssueLaneSpec, IssueItemDto, String, Rect, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(repo.issues.items, lane.status) { repo.issues.items.filter { it.status == lane.status } }
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
    val countBackfill = lane.count(repo) - items.size
    val empty = slots.isEmpty() && countBackfill <= 0
    val laneKey = "${repo.id}:${lane.status}"
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .onGloballyPositioned {
                val bounds = it.boundsInRoot()
                if (laneBounds[laneKey] != bounds) laneBounds[laneKey] = bounds
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
                    .background(lane.color.copy(alpha = if (targeted) 0.12f else 0f))
                    .border(BorderStroke(1.dp, lane.color.copy(alpha = if (targeted) 0.62f else 0f)), RoundedCornerShape(999.dp))
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(lane.label, color = lane.color.copy(alpha = if (empty && !targeted) 0.22f else 1f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.weight(1f)) {}
        }
        slots.forEach { slot ->
            key(slot.key) {
                val label = if (slot.exiting) null else motion.label(repo.id, slot.issue)
                val visibleState = remember(slot.key) {
                    MutableTransitionState(slot.exiting || !motion.entering(repo.id, slot.issue)).apply {
                        targetState = !slot.exiting
                    }
                }
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = fadeIn(tween(issueMotionEnterMs, easing = FastOutSlowInEasing)) +
                        expandVertically(tween(issueMotionEnterMs, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                        slideInVertically(tween(issueMotionEnterMs, easing = FastOutSlowInEasing)) { -it / 3 },
                    exit = fadeOut(tween(issueMotionExitMs, easing = FastOutSlowInEasing)) +
                        shrinkVertically(tween(issueMotionExitMs, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
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
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                    )
                }
            }
        }
        countBackfill.takeIf { it > 0 }?.let { IssueCountTicket(lane, repo, it) }
    }
}

@Composable
private fun IssueItemTicket(
    lane: IssueLaneSpec,
    repo: RepoHealthDto,
    issue: IssueItemDto,
    issueCode: String,
    generatedAtMs: Long,
    motionLabel: String? = null,
    exiting: Boolean = false,
    onEdit: (RepoHealthDto, IssueItemDto) -> Unit,
    onArchiveIssue: (RepoHealthDto, IssueItemDto) -> Unit,
    onDeleteIssue: (RepoHealthDto, IssueItemDto) -> Unit,
    onDragStart: (RepoHealthDto, IssueLaneSpec, IssueItemDto, String, Rect, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    var activeDrag by remember { mutableStateOf(false) }
    val boundsRef = remember { arrayOf<Rect?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val interactionModifier = if (exiting) {
        Modifier
    } else {
        Modifier
            .hoverable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = issue.source == "arcana",
            ) { onEdit(repo, issue) }
            .pointerInput(issue.id) {
                detectDragGestures(
                    onDragStart = { start ->
                        boundsRef[0]?.let {
                            activeDrag = true
                            onDragStart(repo, lane, issue, issueCode, it, start)
                        }
                    },
                    onDrag = { change, delta -> change.consume(); onDrag(delta) },
                    onDragEnd = { activeDrag = false; onDragEnd() },
                    onDragCancel = { activeDrag = false; onDragEnd() },
                )
            }
    }
    IssueTicketCard(
        lane = lane,
        repo = repo,
        issue = issue,
        issueCode = issueCode,
        generatedAtMs = generatedAtMs,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (activeDrag) 0.32f else 1f }
            .onGloballyPositioned { boundsRef[0] = it.boundsInRoot() }
            .then(interactionModifier),
        hovered = hovered && !activeDrag,
        motionLabel = motionLabel,
        onArchive = if (!exiting && issue.source == "arcana" && issue.status != "trash") { { onArchiveIssue(repo, issue) } } else null,
        onDelete = if (!exiting && issue.source == "arcana") { { onDeleteIssue(repo, issue) } } else null,
    )
}

@Composable
private fun IssueDragOverlay(drag: IssueDragState, generatedAtMs: Long, rootBounds: Rect?) {
    val root = rootBounds ?: return
    val width = with(LocalDensity.current) { drag.sourceBounds.width.toDp() }
    val topLeft = drag.pointer - drag.grabOffset - root.topLeft
    IssueTicketCard(
        lane = drag.lane,
        repo = drag.repo,
        issue = drag.issue,
        issueCode = drag.issueCode,
        generatedAtMs = generatedAtMs,
        modifier = Modifier
            .width(width)
            .zIndex(100f)
            .graphicsLayer {
                translationX = topLeft.x
                translationY = topLeft.y
                alpha = 0.96f
            },
    )
}

@Composable
private fun IssueTicketCard(
    lane: IssueLaneSpec,
    repo: RepoHealthDto,
    issue: IssueItemDto,
    issueCode: String,
    generatedAtMs: Long,
    modifier: Modifier = Modifier,
    hovered: Boolean = false,
    motionLabel: String? = null,
    onArchive: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val timestamp = issue.updatedAtMs ?: issue.createdAtMs ?: issue.completedAtMs
    val flash by animateFloatAsState(
        targetValue = if (motionLabel == null) 0f else 1f,
        animationSpec = tween(if (motionLabel == null) issueMotionFlashOutMs else issueMotionFlashInMs, easing = FastOutSlowInEasing),
        label = "issue-motion-flash",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(panelRaised)
            .background(lane.color.copy(alpha = if (hovered) 0.09f + flash * 0.10f else flash * 0.10f))
            .border(BorderStroke(1.dp, lane.color.copy(alpha = if (hovered) 0.58f else 0.28f + flash * 0.26f)), RoundedCornerShape(7.dp))
            .padding(9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FreshRail(timestamp, generatedAtMs, height = 52.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("$issueCode · ${issue.sourceLabel.issueSourceShort()}", color = muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(issue.title.ifBlank { issue.id }, color = text, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            issue.description.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color(0xFFB9C5D2), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            issue.notes.visibleNotes(issue.description)?.let {
                Text(it, color = muted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            timestamp?.let { AgePill(it, generatedAtMs) }
            motionLabel?.let { UpdatePill(lane.color, it) }
            if (onArchive != null || onDelete != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    onArchive?.let { ArchiveButton(color = amber, compact = true, onClick = it) }
                    onDelete?.let { DeleteButton(onClick = it) }
                }
            }
        }
    }
}

@Composable
private fun IssueCountTicket(lane: IssueLaneSpec, repo: RepoHealthDto, count: Int) {
    Column(
        modifier = Modifier
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

@Composable
private fun IssueEventStrip(summary: OpsSummaryDto) {
    val events = remember(summary.repos) {
        summary.repos.flatMap { repo -> repo.issues.events.map { repo to it } }
            .sortedByDescending { it.second.tsMs ?: 0L }
            .take(6)
    }
    if (events.isEmpty()) return
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, cyan, glowAlpha = 0.05f, borderAlpha = 0.22f)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Recent Issue Events", color = text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            StatusPill("${events.size} shown", cyan)
        }
        events.forEach { (repo, event) ->
            key("${repo.id}:${event.eventId}") {
                IssueEventRow(repo, event, summary.generatedAtMs)
            }
        }
    }
}

@Composable
private fun IssueEventRow(repo: RepoHealthDto, event: IssueEventDto, generatedAtMs: Long) {
    val color = issueStatusColor(event.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(panelRaised)
            .border(BorderStroke(1.dp, color.copy(alpha = 0.24f)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FreshRail(event.tsMs, generatedAtMs, height = 34.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${repo.name} · ${repo.issueCode(event.id)}", color = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(event.event.ifBlank { "event" }, color)
            }
            Text(event.title.ifBlank { event.sourceLabel }, color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            event.changes["status"]?.let {
                Text("${it.fromValue ?: "unknown"} to ${it.toValue ?: event.status}", color = Color(0xFFB9C5D2), fontSize = 10.sp)
            }
        }
        event.tsMs?.let { AgePill(it, generatedAtMs) }
    }
}

private fun issueStatusColor(status: String) = issueLanes.firstOrNull { it.status == status }?.color ?: muted

@Composable
private fun MiniActionPill(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.34f)), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ArchiveButton(
    color: Color = Color(0xFFE8B96B),
    compact: Boolean = false,
    count: Int? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        color.copy(alpha = 0.12f),
                        Color.Black.copy(alpha = 0.20f),
                    ),
                ),
            )
            .border(BorderStroke(1.dp, color.copy(alpha = 0.46f)), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 4.dp else 5.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(if (compact) 12.dp else 13.dp)) {
            val stroke = 1.dp.toPx()
            val bodyTop = size.height * 0.34f
            val body = Size(size.width * 0.78f, size.height * 0.52f)
            val left = size.width * 0.11f
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), color.copy(alpha = 0.10f))),
                topLeft = Offset(left, bodyTop),
                size = body,
                cornerRadius = CornerRadius(size.width * 0.10f, size.width * 0.10f),
            )
            drawRoundRect(
                color = color.copy(alpha = 0.88f),
                topLeft = Offset(left, bodyTop),
                size = body,
                cornerRadius = CornerRadius(size.width * 0.10f, size.width * 0.10f),
                style = Stroke(stroke),
            )
            drawRoundRect(
                color = color.copy(alpha = 0.72f),
                topLeft = Offset(size.width * 0.20f, size.height * 0.18f),
                size = Size(size.width * 0.60f, size.height * 0.18f),
                cornerRadius = CornerRadius(size.width * 0.07f, size.width * 0.07f),
                style = Stroke(stroke),
            )
            drawLine(color.copy(alpha = 0.90f), Offset(size.width * 0.24f, bodyTop), Offset(size.width * 0.76f, bodyTop), strokeWidth = stroke)
            drawLine(color.copy(alpha = 0.92f), Offset(size.width * 0.39f, size.height * 0.56f), Offset(size.width * 0.61f, size.height * 0.56f), strokeWidth = stroke)
        }
        Text(
            count?.let { "Archive $it" } ?: "Archive",
            color = Color(0xFFF4E6C6),
            fontSize = if (compact) 9.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeleteButton(onClick: () -> Unit) {
    val color = Color(0xFFE7A1A1)
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), color.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.24f))))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.46f)), shape)
            .clickable(onClick = onClick)
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val stroke = 1.35.dp.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawCircle(color.copy(alpha = 0.18f), radius = size.minDimension * 0.43f, center = Offset(cx, cy))
            drawCircle(color.copy(alpha = 0.72f), radius = size.minDimension * 0.35f, center = Offset(cx, cy), style = Stroke(stroke))
            drawLine(color.copy(alpha = 0.92f), Offset(cx, size.height * 0.14f), Offset(cx, size.height * 0.86f), strokeWidth = stroke * 1.25f)
            drawLine(color.copy(alpha = 0.92f), Offset(size.width * 0.18f, cy), Offset(size.width * 0.82f, cy), strokeWidth = stroke * 1.25f)
            drawLine(Color.White.copy(alpha = 0.26f), Offset(size.width * 0.36f, size.height * 0.32f), Offset(size.width * 0.64f, size.height * 0.68f), strokeWidth = stroke)
            drawLine(Color.White.copy(alpha = 0.26f), Offset(size.width * 0.64f, size.height * 0.32f), Offset(size.width * 0.36f, size.height * 0.68f), strokeWidth = stroke)
        }
    }
}

@Composable
private fun ArchiveDialog(repo: RepoHealthDto, onDelete: (IssueItemDto) -> Unit, onDismiss: () -> Unit) {
    val archived = remember(repo.issues.items) { repo.issues.items.filter { it.status == "trash" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${repo.name} Archive", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                StatusPill("${archived.size}", if (archived.isEmpty()) muted else cyan)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (archived.isEmpty()) {
                    Text("No archived tickets", color = muted, fontSize = 12.sp)
                }
                archived.take(14).forEach { issue ->
                    key(issue.id) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(Brush.horizontalGradient(listOf(panelRaised, cyan.copy(alpha = 0.045f))))
                                .border(BorderStroke(1.dp, cyan.copy(alpha = 0.20f)), RoundedCornerShape(7.dp))
                                .padding(9.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${repo.issueCode(issue)} · ${issue.title.ifBlank { issue.id }}", color = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(issue.description.ifBlank { issue.notes }.ifBlank { " " }, color = Color(0xFFB9C5D2), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DeleteButton { onDelete(issue) }
                        }
                    }
                }
                if (archived.size > 14) {
                    Text("${archived.size - 14} more", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun IssueEditorDialog(state: IssueEditorState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember(state) { mutableStateOf(state.body) }
    val focusRequester = remember { FocusRequester() }
    fun save() = onSave(draft)
    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when {
            event.key == Key.Enter && event.isMetaPressed -> { save(); true }
            event.key == Key.Escape -> { onDismiss(); true }
            else -> false
        }
    }
    LaunchedEffect(state) {
        runCatching { focusRequester.requestFocus() }
    }
    AlertDialog(
        modifier = Modifier.onPreviewKeyEvent(::handleKey),
        onDismissRequest = onDismiss,
        title = { Text(if (state.id == null) "New ${state.status}" else "Edit issue", color = text) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent(::handleKey),
                minLines = 7,
                maxLines = 12,
            )
        },
        confirmButton = { TextButton(onClick = ::save) { Text("save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel") } },
    )
}

private data class IssueEditorState(
    val repoId: String,
    val status: String,
    val id: String? = null,
    val body: String = "",
)

private data class IssueDragState(
    val repo: RepoHealthDto,
    val lane: IssueLaneSpec,
    val issue: IssueItemDto,
    val issueCode: String,
    val status: String,
    val sourceBounds: Rect,
    val grabOffset: Offset,
    val pointer: Offset,
)

@Composable
private fun rememberIssueBoardMotionState(summary: OpsSummaryDto): IssueBoardMotionState {
    val previous = remember { arrayOf<Map<String, IssueSnapshot>?>(null) }
    var retained by remember { mutableStateOf(IssueBoardMotionState()) }
    val current = remember(summary.generatedAtMs, summary.repos) { summary.issueSnapshots() }
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
            old[next.key]?.let { it.repoId == next.repoId && it.status == next.status && it.fingerprint != next.fingerprint } == true
        }
        val reordered = current.values.filter { next ->
            old[next.key]?.let { it.repoId == next.repoId && it.status == next.status && it.index != next.index } == true
        }

        val nextLabels = buildMap {
            reordered.forEach { put(it.ticketKey, "shifted") }
            updated.forEach { put(it.ticketKey, "updated") }
            created.forEach { put(it.ticketKey, "new") }
            moved.forEach { put(it.second.ticketKey, "moved") }
        }
        val nextExits = (removed + moved.map { it.first }).map { it.exitSlot(summary.generatedAtMs) }
        IssueBoardMotionState(nextLabels, nextExits)
    } ?: IssueBoardMotionState()
    val motion = if (detected.active) detected else retained

    SideEffect {
        previous[0] = current
        if (detected.active) retained = detected
    }

    LaunchedEffect(retained) {
        if (!retained.active) return@LaunchedEffect
        delay(issueMotionHoldMs)
        retained = IssueBoardMotionState()
    }

    return motion
}

private data class IssueBoardMotionState(
    val labels: Map<String, String> = emptyMap(),
    val exits: List<IssueTicketSlot> = emptyList(),
)

private val IssueBoardMotionState.active get() = labels.isNotEmpty() || exits.isNotEmpty()

private data class IssueSnapshot(
    val repoId: String,
    val issue: IssueItemDto,
    val status: String,
    val index: Int,
    val fingerprint: String,
) {
    val key = issue.motionKey(repoId)
    val ticketKey = issue.ticketKey(repoId)
}

private data class IssueTicketSlot(
    val repoId: String,
    val status: String,
    val issue: IssueItemDto,
    val key: String,
    val index: Int,
    val exiting: Boolean,
)

private fun IssueBoardMotionState.entering(repoId: String, issue: IssueItemDto) = labels[issue.ticketKey(repoId)] in issueEnterLabels

private fun IssueBoardMotionState.label(repoId: String, issue: IssueItemDto) = labels[issue.ticketKey(repoId)]

private fun OpsSummaryDto.issueSnapshots(): Map<String, IssueSnapshot> =
    repos.flatMap { repo ->
        repo.issues.items
            .filter { it.status in visibleIssueStatuses }
            .mapIndexed { index, issue -> IssueSnapshot(repo.id, issue, issue.status, index, issue.motionFingerprint()) }
    }.associateBy { it.key }

private fun IssueSnapshot.exitSlot(generatedAtMs: Long) = IssueTicketSlot(
    repoId = repoId,
    status = status,
    issue = issue,
    key = "exit:$ticketKey:$generatedAtMs",
    index = index,
    exiting = true,
)

private fun IssueItemDto.motionKey(repoId: String) = "$repoId:$source:$id"

private fun IssueItemDto.ticketKey(repoId: String) = "${motionKey(repoId)}:$status"

private fun IssueItemDto.motionFingerprint() = listOf(
    title,
    status,
    source,
    sourceLabel,
    url.orEmpty(),
    description,
    notes,
    createdAtMs?.toString().orEmpty(),
    updatedAtMs?.toString().orEmpty(),
    completedAtMs?.toString().orEmpty(),
).joinToString("|")

private fun IssueItemDto.issueEditorText() = buildString {
    append(title.ifBlank { id })
    if (description.isNotBlank()) append("\n").append(description)
}

private fun String.visibleNotes(description: String) = trim().takeIf { it.isNotBlank() && !description.contains(it) }

private fun RepoHealthDto.issueCode(issue: IssueItemDto) = issueCode(issue.id)

private fun RepoHealthDto.issueCode(id: String): String {
    val prefix = id.issueRepoPrefixOrNull() ?: issueRepoPrefix()
    id.issueNumberOrNull(prefix)?.let { return "$prefix-${it.toString().padStart(3, '0')}" }
    val index = issues.items.indexOfFirst { it.id == id }.coerceAtLeast(0)
    return "$prefix-${index.toString().padStart(3, '0')}"
}

private fun String.issueSourceShort() = removeSuffix(" issues").removeSuffix(" Issues")

private fun RepoHealthDto.issueRepoPrefix() = when (id) {
    "arcana" -> "ARC"
    "backend" -> "BCK"
    "server_py" -> "SPY"
    else -> name.take(3).uppercase().padEnd(3, 'X')
}

private fun String.issueRepoPrefixOrNull() = takeIf { length > 4 && this[3] == '-' && take(3).all(Char::isLetter) && drop(4).all(Char::isDigit) }
    ?.take(3)

private fun String.issueNumberOrNull(prefix: String) =
    takeIf { startsWith("$prefix-") }?.substringAfter('-')?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.toIntOrNull()

private fun Map<String, Rect>.targetAt(pointer: Offset): Pair<String, String>? {
    entries.firstOrNull { (_, bounds) -> bounds.contains(pointer) }?.key?.laneTargetOrNull()?.let { return it }
    entries.forEach { (key, bounds) ->
        if (pointer.x < bounds.left || pointer.x > bounds.right) return@forEach
        val repoId = key.substringBefore(':')
        var aligned = 0
        var top = bounds.top
        var bottom = bounds.bottom
        entries.forEach { (otherKey, otherBounds) ->
            if (otherKey.substringBefore(':') == repoId && kotlin.math.abs(otherBounds.top - bounds.top) < 24f) {
                aligned += 1
                top = minOf(top, otherBounds.top)
                bottom = maxOf(bottom, otherBounds.bottom)
            }
        }
        if (aligned > 1 && pointer.y >= top && pointer.y <= bottom) return key.laneTargetOrNull()
    }
    return null
}

private fun String.laneTargetOrNull() = split(':', limit = 2)
    .takeIf { it.size == 2 }
    ?.let { it[0] to it[1] }

private const val issueMotionEnterMs = 10_800
private const val issueMotionExitMs = 10_800
private const val issueMotionHoldMs = 12_500L
private const val issueMotionFlashInMs = 1_200
private const val issueMotionFlashOutMs = 10_800
private val issueEnterLabels = setOf("new", "moved")
private val visibleIssueStatuses = setOf("blocked", "todo", "wip", "review", "done")

private val issueLanes = listOf(
    IssueLaneSpec("BLOCKED", "blocked", rose) { it.issues.blocked },
    IssueLaneSpec("TODO", "todo", Color(0xFF8EA0B8)) { it.issues.todo },
    IssueLaneSpec("WIP", "wip", cyan) { it.issues.wip },
    IssueLaneSpec("REVIEW", "review", amber) { it.issues.review },
    IssueLaneSpec("DONE", "done", green) { it.issues.done },
)
