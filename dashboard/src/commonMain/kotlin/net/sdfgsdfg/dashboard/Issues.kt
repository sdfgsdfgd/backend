package net.sdfgsdfg.dashboard

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    var drag by remember { mutableStateOf<IssueDragState?>(null) }
    var dragTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    val laneBounds = remember { mutableStateMapOf<String, Rect>() }

    fun mutate(request: IssueMutationRequestDto) = mutateIssue(
        request = request,
        onLoaded = { onSummary(it); error = null },
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
                .onGloballyPositioned { rootBounds = it.boundsInRoot() },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                error?.let { Text(it, color = rose, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                IssuePanels(
                    repos = loadState.summary.repos,
                    generatedAtMs = loadState.summary.generatedAtMs,
                    pageWidth = pageWidth,
                    dropTargetRepoId = dragTarget?.first,
                    dropTargetStatus = dragTarget?.second,
                    laneBounds = laneBounds,
                    onCreate = { repo, status -> editor = IssueEditorState(repo.id, status) },
                    onEdit = { repo, issue -> editor = IssueEditorState(repo.id, issue.status, issue.id, issue.issueEditorText()) },
                    onDelete = { repo, issue -> mutate(IssueMutationRequestDto("delete", repo.id, id = issue.id, status = issue.status)) },
                    onDragStart = { repo, lane, issue, issueCode, bounds, grabOffset ->
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
                    },
                )
                IssueEventStrip(loadState.summary)
            }
            drag?.let { IssueDragOverlay(it, loadState.summary.generatedAtMs, rootBounds) }
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
}

@Composable
private fun IssuePanels(
    repos: List<RepoHealthDto>,
    generatedAtMs: Long,
    pageWidth: Dp,
    dropTargetRepoId: String?,
    dropTargetStatus: String?,
    laneBounds: MutableMap<String, Rect>,
    onCreate: (RepoHealthDto, String) -> Unit,
    onEdit: (RepoHealthDto, IssueItemDto) -> Unit,
    onDelete: (RepoHealthDto, IssueItemDto) -> Unit,
    onDragStart: (RepoHealthDto, IssueLaneSpec, IssueItemDto, String, Rect, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val sortedRepos = remember(repos) { repos.sortedByDescending { it.issues.active } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sortedRepos.forEach { repo ->
            key(repo.id) {
                IssuePanel(repo, generatedAtMs, pageWidth, dropTargetRepoId, dropTargetStatus, laneBounds, onCreate, onEdit, onDelete, onDragStart, onDrag, onDragEnd)
            }
        }
    }
}

@Composable
private fun IssuePanel(
    repo: RepoHealthDto,
    generatedAtMs: Long,
    pageWidth: Dp,
    dropTargetRepoId: String?,
    dropTargetStatus: String?,
    laneBounds: MutableMap<String, Rect>,
    onCreate: (RepoHealthDto, String) -> Unit,
    onEdit: (RepoHealthDto, IssueItemDto) -> Unit,
    onDelete: (RepoHealthDto, IssueItemDto) -> Unit,
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
                StatusPill(if (active == 0) "clear" else "$active active", if (active == 0) green else amber)
            }
        }
        if (pageWidth < 1180.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                issueLanes.forEach { lane ->
                    key(lane.status) {
                        IssueLane(lane, repo, generatedAtMs, dropTargetRepoId == repo.id && dropTargetStatus == lane.status, laneBounds, onCreate, onEdit, onDelete, onDragStart, onDrag, onDragEnd, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                issueLanes.forEach { lane ->
                    key(lane.status) {
                        IssueLane(lane, repo, generatedAtMs, dropTargetRepoId == repo.id && dropTargetStatus == lane.status, laneBounds, onCreate, onEdit, onDelete, onDragStart, onDrag, onDragEnd, modifier = Modifier.weight(1f))
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
    targeted: Boolean,
    laneBounds: MutableMap<String, Rect>,
    onCreate: (RepoHealthDto, String) -> Unit,
    onEdit: (RepoHealthDto, IssueItemDto) -> Unit,
    onDelete: (RepoHealthDto, IssueItemDto) -> Unit,
    onDragStart: (RepoHealthDto, IssueLaneSpec, IssueItemDto, String, Rect, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(repo.issues.items, lane.status) { repo.issues.items.filter { it.status == lane.status } }
    val countBackfill = lane.count(repo) - items.size
    val empty = items.isEmpty() && countBackfill <= 0
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .onGloballyPositioned { laneBounds["${repo.id}:${lane.status}"] = it.boundsInRoot() }
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
        items.forEach { issue ->
            key(issue.id) {
                IssueItemTicket(lane, repo, issue, repo.issueCode(issue), generatedAtMs, onEdit, onDelete, onDragStart, onDrag, onDragEnd)
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
    onEdit: (RepoHealthDto, IssueItemDto) -> Unit,
    onDelete: (RepoHealthDto, IssueItemDto) -> Unit,
    onDragStart: (RepoHealthDto, IssueLaneSpec, IssueItemDto, String, Rect, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    var activeDrag by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    IssueTicketCard(
        lane = lane,
        repo = repo,
        issue = issue,
        issueCode = issueCode,
        generatedAtMs = generatedAtMs,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (activeDrag) 0.32f else 1f }
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .hoverable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = issue.source == "arcana",
            ) { onEdit(repo, issue) }
            .pointerInput(issue.id) {
                detectDragGestures(
                    onDragStart = { start ->
                        bounds?.let {
                            activeDrag = true
                            onDragStart(repo, lane, issue, issueCode, it, start)
                        }
                    },
                    onDrag = { change, delta -> change.consume(); onDrag(delta) },
                    onDragEnd = { activeDrag = false; onDragEnd() },
                    onDragCancel = { activeDrag = false; onDragEnd() },
                )
            },
        hovered = hovered && !activeDrag,
        onDelete = if (issue.source == "arcana") { { onDelete(repo, issue) } } else null,
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
    onDelete: (() -> Unit)? = null,
) {
    val timestamp = issue.updatedAtMs ?: issue.createdAtMs ?: issue.completedAtMs
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(panelRaised)
            .background(lane.color.copy(alpha = if (hovered) 0.09f else 0f))
            .border(BorderStroke(1.dp, lane.color.copy(alpha = if (hovered) 0.58f else 0.28f)), RoundedCornerShape(7.dp))
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
            onDelete?.let { MiniActionPill("-", rose, it) }
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

private fun String.issueRepoPrefixOrNull() = takeIf { length == 7 && this[3] == '-' && take(3).all(Char::isLetter) }
    ?.take(3)

private fun String.issueNumberOrNull(prefix: String) =
    takeIf { startsWith("$prefix-") }?.substringAfter('-')?.takeIf { it.length == 3 && it.all(Char::isDigit) }?.toIntOrNull()

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

private val issueLanes = listOf(
    IssueLaneSpec("BLOCKED", "blocked", rose) { it.issues.blocked },
    IssueLaneSpec("TODO", "todo", Color(0xFF8EA0B8)) { it.issues.todo },
    IssueLaneSpec("WIP", "wip", cyan) { it.issues.wip },
    IssueLaneSpec("REVIEW", "review", amber) { it.issues.review },
    IssueLaneSpec("DONE", "done", green) { it.issues.done },
)
