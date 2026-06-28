package net.sdfgsdfg.dashboard

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.sdfgsdfg.data.model.IssueItemDto

private val issueLaneStackBreakpoint = 1180.dp
private const val issueDragLiftScale = 1.7f

private data class IssueBoardActions(
    val create: (IssueRepoModel, String) -> Unit,
    val edit: (IssueRepoModel, IssueItemDto) -> Unit,
    val archiveIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    val archive: (IssueRepoModel) -> Unit,
    val moveIssue: (IssueRepoModel, IssueItemDto, String) -> Unit,
)

@Composable
internal fun IssuePanels(
    repos: List<IssueRepoModel>,
    generatedAtMs: Long,
    pageWidth: Dp,
    pageHeight: Dp,
    drag: IssueBoardDrag,
    canWriteIssues: Boolean,
    onCreate: (IssueRepoModel, String) -> Unit,
    onEdit: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchiveIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchive: (IssueRepoModel) -> Unit,
    onMoveIssue: (IssueRepoModel, IssueItemDto, String) -> Unit,
) {
    val sortedRepos = remember(repos) { repos.sortedByDescending { it.issues.active } }
    val actions = remember(onCreate, onEdit, onArchiveIssue, onArchive, onMoveIssue) {
        IssueBoardActions(onCreate, onEdit, onArchiveIssue, onArchive, onMoveIssue)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sortedRepos.forEach { repo ->
            key(repo.id) {
                IssuePanel(repo, generatedAtMs, pageWidth, pageHeight, drag, canWriteIssues, actions)
            }
        }
    }
}

@Composable
private fun IssuePanel(
    repo: IssueRepoModel,
    generatedAtMs: Long,
    pageWidth: Dp,
    pageHeight: Dp,
    drag: IssueBoardDrag,
    canWriteIssues: Boolean,
    actions: IssueBoardActions,
) {
    val active = repo.issues.active
    val source = issueSourceBreakdown(listOf(repo.issues))
    val shape = RoundedCornerShape(8.dp)
    var panelBounds by remember { mutableStateOf<Rect?>(null) }
    val expandedPrefKey = remember(repo.id) { "ops.issues.repoExpanded.${repo.id}" }
    var expanded by remember(expandedPrefKey) { mutableStateOf(readDashboardPref(expandedPrefKey)?.toBooleanStrictOrNull() ?: true) }
    val currentPanelBounds = rememberUpdatedState(panelBounds)
    val motionScope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .motionSurface(shape, if (active == 0) green else amber, borderAlpha = 0.26f)
            .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing))
            .onGloballyPositioned { panelBounds = it.boundsInRoot() },
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    MiniActionPill(if (expanded) "hide" else "show", if (active == 0) green else amber) {
                        expanded = !expanded
                        writeDashboardPref(expandedPrefKey, expanded.toString())
                    }
                }
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
                        if (canWriteIssues || repo.issues.archive > 0) {
                            ArchiveButton(color = if (repo.issues.archive > 0) cyan else muted, count = repo.issues.archive.takeIf { it > 0 }) { actions.archive(repo) }
                        }
                        StatusPill(if (active == 0) "clear" else "$active active", if (active == 0) green else amber)
                    }
                }
            }
            val stacked = pageWidth < issueLaneStackBreakpoint
            val laneBodyMaxHeight = pageHeight * 2f
            if (!expanded) {
                SideEffect {
                    drag.pruneLanes(repo.id, emptySet())
                    issueLanes.forEach { drag.pruneTickets(repo.id, it.status, emptySet()) }
                }
            } else if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    issueLanes.forEach { lane ->
                        key(lane.status) {
                            IssueLane(
                                lane,
                                repo,
                                generatedAtMs,
                                laneBodyMaxHeight,
                                drag,
                                canWriteIssues,
                                motionScope,
                                currentPanelBounds,
                                actions,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    issueLanes.forEach { lane ->
                        key(lane.status) {
                            IssueLane(
                                lane,
                                repo,
                                generatedAtMs,
                                laneBodyMaxHeight,
                                drag,
                                canWriteIssues,
                                motionScope,
                                currentPanelBounds,
                                actions,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
        IssueDragOverlay(drag.preview?.takeIf { it.repo.id == repo.id }, generatedAtMs, panelBounds)
    }
}

@Composable
private fun IssueDragOverlay(preview: IssueDragPreview?, generatedAtMs: Long, panelBounds: Rect?) {
    if (preview == null || panelBounds == null) return
    val density = LocalDensity.current
    val lane = issueLanes.firstOrNull { it.status == preview.issue.status } ?: return
    val editable = preview.issue.source == "arcana"
    IssueTicketCard(
        lane = lane,
        issue = preview.issue,
        issueCode = preview.repo.issueCode(preview.issue),
        generatedAtMs = generatedAtMs,
        modifier = Modifier
            .width(with(density) { preview.bounds.width.toDp() })
            .zIndex(1_000f)
            .graphicsLayer {
                translationX = preview.bounds.left + preview.offset.x - if (preview.panelAnchored) 0f else panelBounds.left
                translationY = preview.bounds.top + preview.offset.y - if (preview.panelAnchored) 0f else panelBounds.top
                scaleX = preview.scale
                scaleY = preview.scale
                alpha = 0.98f
            },
        dragTone = 1f,
        animatedFreshness = false,
        onArchive = if (editable && preview.issue.status != "archive") ({}) else null,
    )
}

@Composable
private fun IssueLane(
    lane: IssueLaneSpec,
    repo: IssueRepoModel,
    generatedAtMs: Long,
    maxBodyHeight: Dp,
    drag: IssueBoardDrag,
    canWriteIssues: Boolean,
    motionScope: CoroutineScope,
    currentPanelBounds: State<Rect?>,
    actions: IssueBoardActions,
    modifier: Modifier = Modifier,
) {
    val optimisticStatuses = drag.optimisticStatuses
    val tickets = remember(repo.id, repo.issues.items, lane.status, optimisticStatuses) { drag.items(repo, lane) }
    val ticketKeys = remember(tickets, repo.id) { tickets.map { it.ticketKey(repo.id) } }
    val listState = rememberLazyListState()
    val headKey = ticketKeys.firstOrNull()
    val countBackfill = if (optimisticStatuses.isEmpty()) lane.count(repo.issues) - tickets.size else 0
    val empty = tickets.isEmpty() && countBackfill <= 0
    val shape = RoundedCornerShape(8.dp)
    val laneKey = "${repo.id}:${lane.status}"
    val diagnostics = rememberIssueLaneDiagnostics(
        repoId = repo.id,
        laneStatus = lane.status,
        ticketKeys = ticketKeys,
        countBackfill = countBackfill,
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
    )
    LaunchedEffect(headKey) {
        if (headKey != null) listState.animateScrollToItem(0)
    }
    SideEffect {
        drag.pruneTickets(repo.id, lane.status, ticketKeys.toSet())
    }
    Column(
        modifier = modifier
            .onGloballyPositioned { drag.placeLane(laneKey, it.boundsInRoot()) }
            .motionSurface(
                shape = shape,
                accent = lane.color,
                borderAlpha = if (empty) 0.06f else 0.26f,
                neutralBorderAlpha = if (empty) 0.04f else 0.17f,
            )
            .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (canWriteIssues && lane.status == "todo") MiniActionPill("+", lane.color.copy(alpha = if (empty) 0.55f else 1f)) { actions.create(repo, lane.status) }
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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxBodyHeight),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(tickets, key = { it.ticketKey(repo.id) }) { issue ->
                IssueTicket(
                    lane = lane,
                    repo = repo,
                    issue = issue,
                    issueCode = repo.issueCode(issue),
                    generatedAtMs = generatedAtMs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .issueLayoutTrace(diagnostics, issue.ticketKey(repo.id))
                        .animateItem(),
                    drag = drag,
                    canWriteIssues = canWriteIssues,
                    motionScope = motionScope,
                    currentPanelBounds = currentPanelBounds,
                    actions = actions,
                )
            }
            countBackfill.takeIf { it > 0 }?.let { count ->
                item(key = "${repo.id}:${lane.status}:count") {
                    IssueCountTicket(lane, repo, count, Modifier.animateItem())
                }
            }
        }
    }
}

@Composable
private fun IssueTicket(
    lane: IssueLaneSpec,
    repo: IssueRepoModel,
    issue: IssueItemDto,
    issueCode: String,
    generatedAtMs: Long,
    modifier: Modifier,
    drag: IssueBoardDrag,
    canWriteIssues: Boolean,
    motionScope: CoroutineScope,
    currentPanelBounds: State<Rect?>,
    actions: IssueBoardActions,
) {
    val ticketKey = issue.ticketKey(repo.id)
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val pickupOffset = remember(ticketKey) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val releaseOffset = remember(ticketKey) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val previewScale = remember(ticketKey) { Animatable(1f) }
    val motionJob = remember(ticketKey) { arrayOf<Job?>(null) }
    val boundsRef = remember { arrayOf<Rect?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val moving = dragging || releaseOffset.value != Offset.Zero
    val previewing = drag.preview?.issue?.motionKey(repo.id) == issue.motionKey(repo.id)
    val lift by animateFloatAsState(
        targetValue = if (moving) 1.025f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "issue-new-card-drag-lift",
    )
    val editable = canWriteIssues && issue.source == "arcana"
    val interactionModifier = if (!editable) {
        Modifier
    } else {
        Modifier
            .hoverable(interactionSource = interaction)
            .pointerInput(repo.id, issue.source, issue.id, issue.status) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val bounds = boundsRef[0] ?: return@awaitEachGesture
                    var moved = false
                    dragging = true
                    dragOffset = Offset.Zero
                    motionJob[0]?.cancel()
                    val center = Offset(bounds.size.width / 2f, bounds.size.height / 2f)
                    val pickupTarget = down.position - center
                    val dragSession = drag.begin(repo, issue, bounds.topLeft + down.position, bounds)
                    motionJob[0] = motionScope.launch {
                        releaseOffset.snapTo(Offset.Zero)
                        pickupOffset.snapTo(Offset.Zero)
                        previewScale.snapTo(1f)
                        launch {
                            previewScale.animateTo(issueDragLiftScale, spring(dampingRatio = 0.48f, stiffness = 260f)) {
                                drag.scalePreviewTo(dragSession, value)
                            }
                        }
                        pickupOffset.animateTo(pickupTarget, spring(dampingRatio = 0.42f, stiffness = 220f)) {
                            drag.movePreviewTo(dragSession, dragOffset + value)
                        }
                    }
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                moved = true
                                dragOffset += delta
                                drag.moveBy(dragSession, delta)
                                drag.movePreviewTo(dragSession, dragOffset + pickupOffset.value)
                                change.consume()
                            }
                            if (change.changedToUpIgnoreConsumed()) {
                                if (!moved) actions.edit(repo, issue)
                                break
                            }
                        }
                    } finally {
                        val settleFrom = dragOffset + pickupOffset.value
                        if (!moved) {
                            motionJob[0]?.cancel()
                            dragging = false
                            dragOffset = Offset.Zero
                            drag.cancel(dragSession)
                            motionJob[0] = motionScope.launch {
                                pickupOffset.snapTo(Offset.Zero)
                                releaseOffset.snapTo(Offset.Zero)
                                previewScale.snapTo(1f)
                            }
                            return@awaitEachGesture
                        }
                        val dropTarget = drag.dropTarget(dragSession, bounds.offsetBy(settleFrom), actions.moveIssue, keepPreview = true)
                        motionJob[0]?.cancel()
                        val releasePanelBounds = currentPanelBounds.value
                        val releaseStartBounds = releasePanelBounds?.let { drag.anchorPreviewToPanel(dragSession, it) }
                        motionJob[0] = motionScope.launch {
                            releaseOffset.snapTo(if (releaseStartBounds == null) settleFrom else Offset.Zero)
                            pickupOffset.snapTo(Offset.Zero)
                            dragOffset = Offset.Zero
                            dragging = false
                            fun panelTargetOffset(target: Rect) = releaseStartBounds?.let { start ->
                                val panel = currentPanelBounds.value ?: releasePanelBounds
                                panel.let { Offset(target.left - it.left - start.left, target.top - it.top - start.top) }
                            }
                            launch {
                                previewScale.animateTo(1f, spring(dampingRatio = 0.28f, stiffness = 115f)) {
                                    drag.scalePreviewTo(dragSession, value)
                                }
                            }
                            if (dropTarget == null) {
                                val targetBounds = drag.placedTicket(ticketKey) ?: bounds
                                val targetOffset = panelTargetOffset(targetBounds) ?: Offset.Zero
                                releaseOffset.animateTo(targetOffset, spring(dampingRatio = 0.26f, stiffness = 44f)) {
                                    drag.movePreviewTo(dragSession, value)
                                }
                            } else {
                                drag.retargetPreview(dragSession, dropTarget.status)
                                dropTarget.commit()
                                var targetBounds = drag.placedTicket(dropTarget.ticketKey)
                                repeat(3) {
                                    if (targetBounds == null) {
                                        withFrameNanos { }
                                        targetBounds = drag.placedTicket(dropTarget.ticketKey)
                                    }
                                }
                                val target = targetBounds ?: dropTarget.bounds
                                val targetOffset = panelTargetOffset(target) ?: (target.topLeft - bounds.topLeft)
                                releaseOffset.animateTo(
                                    targetOffset,
                                    spring(dampingRatio = 0.26f, stiffness = 44f)
                                ) {
                                    drag.movePreviewTo(dragSession, value)
                                }
                            }
                            releaseOffset.snapTo(Offset.Zero)
                            drag.clearPreview(dragSession)
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
        modifier = modifier
            .zIndex(if (moving) 100f else 0f)
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
                alpha = if (previewing) 0f else 1f
            }
            .onGloballyPositioned {
                val bounds = it.boundsInRoot()
                boundsRef[0] = bounds
                drag.placeTicket(ticketKey, bounds)
            }
            .then(interactionModifier),
        hovered = editable && hovered && !dragging,
        dragTone = when {
            dragging -> 1f
            moving -> 0.55f
            else -> 0f
        },
        animatedFreshness = false,
        onEdit = if (editable) {
            { actions.edit(repo, issue) }
        } else null,
        onArchive = if (editable && issue.status != "archive") {
            { actions.archiveIssue(repo, issue) }
        } else null,
    )
}

@Composable
private fun IssueCountTicket(lane: IssueLaneSpec, repo: IssueRepoModel, count: Int, modifier: Modifier = Modifier) {
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
