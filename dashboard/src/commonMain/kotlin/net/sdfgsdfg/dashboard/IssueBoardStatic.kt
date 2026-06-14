package net.sdfgsdfg.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.data.model.IssueItemDto
import net.sdfgsdfg.dashboard.tools.FrameWindowProfiler
import net.sdfgsdfg.dashboard.tools.issueFrameTrace
import net.sdfgsdfg.dashboard.tools.issueFrameTraceEnabled
import net.sdfgsdfg.dashboard.tools.issueJfrProfile
import net.sdfgsdfg.dashboard.tools.issueProfileEnabled

@Composable
internal fun IssueStaticPanels(
    repos: List<IssueRepoModel>,
    generatedAtMs: Long,
    pageWidth: Dp,
    onCreate: (IssueRepoModel, String) -> Unit,
    onEdit: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchiveIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onDeleteIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchive: (IssueRepoModel) -> Unit,
) {
    val sortedRepos = remember(repos) { repos.sortedByDescending { it.issues.active } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sortedRepos.forEach { repo ->
            key(repo.id) {
                IssueStaticPanel(repo, generatedAtMs, pageWidth, onCreate, onEdit, onArchiveIssue, onDeleteIssue, onArchive)
            }
        }
    }
}

@Composable
private fun IssueStaticPanel(
    repo: IssueRepoModel,
    generatedAtMs: Long,
    pageWidth: Dp,
    onCreate: (IssueRepoModel, String) -> Unit,
    onEdit: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchiveIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onDeleteIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchive: (IssueRepoModel) -> Unit,
) {
    val active = repo.issues.active
    val source = issueSourceBreakdown(listOf(repo.issues))
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .motionSurface(shape, if (active == 0) green else amber, borderAlpha = 0.26f)
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
        val laneBodyHeight = if (pageWidth < 1180.dp) 420.dp else 560.dp
        if (pageWidth < 1180.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                issueLanes.forEach { lane ->
                    key(lane.status) {
                        IssueStaticLane(lane, repo, generatedAtMs, laneBodyHeight, onCreate, onEdit, onArchiveIssue, onDeleteIssue, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                issueLanes.forEach { lane ->
                    key(lane.status) {
                        IssueStaticLane(lane, repo, generatedAtMs, laneBodyHeight, onCreate, onEdit, onArchiveIssue, onDeleteIssue, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueStaticLane(
    lane: IssueLaneSpec,
    repo: IssueRepoModel,
    generatedAtMs: Long,
    maxBodyHeight: Dp,
    onCreate: (IssueRepoModel, String) -> Unit,
    onEdit: (IssueRepoModel, IssueItemDto) -> Unit,
    onArchiveIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    onDeleteIssue: (IssueRepoModel, IssueItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tickets = remember(repo.issues.items, lane.status) { lane.items(repo.issues) }
    val ticketKeys = remember(tickets, repo.id) { tickets.map { it.ticketKey(repo.id) } }
    val countBackfill = lane.count(repo.issues) - tickets.size
    val empty = tickets.isEmpty() && countBackfill <= 0
    val listState = rememberLazyListState()
    val headKey = ticketKeys.firstOrNull()
    val shape = RoundedCornerShape(8.dp)
    val previousTraceKeys = remember { arrayOf<List<String>?>(null) }
    val traceMeasureCounts = remember { mutableMapOf<String, Int>() }
    val tracePlaceCounts = remember { mutableMapOf<String, Int>() }
    var removalTraceSeq by remember { mutableStateOf(0) }
    var removalTraceKey by remember { mutableStateOf<String?>(null) }
    var removalTraceDetail by remember { mutableStateOf("") }
    var removalTraceMovedKeys by remember { mutableStateOf(emptySet<String>()) }
    val traceEnabled = issueFrameTraceEnabled()
    val profileEnabled = issueProfileEnabled()
    LaunchedEffect(headKey) {
        if (headKey != null) listState.animateScrollToItem(0)
    }
    LaunchedEffect(ticketKeys, countBackfill, profileEnabled) {
        val previous = previousTraceKeys[0]
        previousTraceKeys[0] = ticketKeys
        if (!profileEnabled || previous == null) return@LaunchedEffect

        val previousSet = previous.toSet()
        val currentSet = ticketKeys.toSet()
        val created = ticketKeys.filter { it !in previousSet }
        val removed = previous.filter { it !in currentSet }
        val moved = ticketKeys.filter { it in previousSet && previous.indexOf(it) != ticketKeys.indexOf(it) }
        if (created.isEmpty() && removed.isEmpty() && moved.isEmpty()) return@LaunchedEffect

        val laneId = "${repo.id}:${lane.status}"
        val removedIndex = removed.firstOrNull()?.let(previous::indexOf) ?: -1
        val shifted = if (removedIndex >= 0) previous.drop(removedIndex + 1).filter { it in currentSet } else moved
        val detail = "lane=$laneId keys=${previous.size}->${ticketKeys.size} backfill=$countBackfill first=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset} removedIndex=$removedIndex shifted=${shifted.size} created=${created.traceKeys()} removed=${removed.traceKeys()} moved=${moved.traceKeys()}"
        issueFrameTrace("lane-change") { detail }
        if (removed.isNotEmpty()) {
            traceMeasureCounts.clear()
            tracePlaceCounts.clear()
            removalTraceMovedKeys = shifted.toSet()
            removalTraceSeq += 1
            removalTraceDetail = detail
            removalTraceKey = "$laneId:$removalTraceSeq:${removed.joinToString("|")}"
        }
    }
    removalTraceKey?.let { key ->
        FrameWindowProfiler(
            enabled = issueProfileEnabled(),
            key = key,
            windowMs = 2_200L,
            jfr = issueJfrProfile("remove", removalTraceDetail),
            onSevereFrame = { sample ->
                issueFrameTrace("remove-frame-skip") {
                    "$removalTraceDetail frame=${sample.frame} delta=${sample.deltaMs}ms"
                }
            },
            onSummary = { summary ->
                issueFrameTrace("remove-frame-summary") {
                    "$removalTraceDetail complete=${summary.complete} elapsed=${summary.elapsedMs}ms frames=${summary.frames} slowOver34=${summary.slowFrames} severeOver80=${summary.severeFrames} worst=${summary.worstFrameMs}ms measured=${traceMeasureCounts.traceCounts()} placed=${tracePlaceCounts.traceCounts()}"
                }
            },
        )
    }
    Column(
        modifier = modifier
            .motionSurface(
                shape = shape,
                accent = lane.color,
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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxBodyHeight),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(tickets, key = { it.ticketKey(repo.id) }) { issue ->
                val editable = issue.source == "arcana"
                IssueTicketCard(
                    lane = lane,
                    issue = issue,
                    issueCode = repo.issueCode(issue),
                    generatedAtMs = generatedAtMs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .issueLayoutTrace(traceEnabled, issue.ticketKey(repo.id), removalTraceMovedKeys, traceMeasureCounts, tracePlaceCounts)
                        .animateItem()
                        .then(if (editable) Modifier.clickable { onEdit(repo, issue) } else Modifier),
                    motionFlash = false,
                    animatedFreshness = false,
                    onArchive = if (editable && issue.status != "trash") { { onArchiveIssue(repo, issue) } } else null,
                    onDelete = if (editable) { { onDeleteIssue(repo, issue) } } else null,
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

private fun List<String>.traceKeys(limit: Int = 5) =
    take(limit).joinToString(prefix = "[", postfix = if (size > limit) ",+${size - limit}]" else "]")

private fun Modifier.issueLayoutTrace(
    enabled: Boolean,
    key: String,
    trackedKeys: Set<String>,
    measures: MutableMap<String, Int>,
    places: MutableMap<String, Int>,
) = if (!enabled || key !in trackedKeys) {
    this
} else {
    layout { measurable, constraints ->
        measures[key] = (measures[key] ?: 0) + 1
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            places[key] = (places[key] ?: 0) + 1
            placeable.place(0, 0)
        }
    }
}

private fun Map<String, Int>.traceCounts(limit: Int = 5) =
    entries.sortedByDescending { it.value }
        .take(limit)
        .joinToString(prefix = "[", postfix = if (size > limit) ",+${size - limit}]" else "]") {
            "${it.key.substringAfterLast(':')}=${it.value}"
        }
