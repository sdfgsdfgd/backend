package net.sdfgsdfg.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.RepoHealthDto

@Composable
internal fun Issues(loadState: OpsLoadState) {
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
        is OpsLoadState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            IssuesHeader(loadState.summary)
            IssueRepoStrip(loadState.summary.repos)
            IssueBoard(loadState.summary.repos)
            WorkSurface(
                title = "Issue Detail Contract",
                detail = "The board reads repo-local Arcana issue summaries now; detail events, artifacts, diffs, and feedback attach after the issue DTO grows.",
                items = listOf(".arcana/issues.json", "events", "artifacts", "feedback"),
            )
        }
    }
}

@Composable
private fun IssueRepoStrip(repos: List<RepoHealthDto>) {
    BoxWithConstraints {
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repos.forEach { IssueRepoTile(it, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repos.forEach { repo ->
                    IssueRepoTile(repo, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun IssueRepoTile(repo: RepoHealthDto, modifier: Modifier = Modifier) {
    val active = repo.issues.active
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .glassSurface(shape, repo.status.color(), glowAlpha = 0.05f, borderAlpha = 0.28f)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(repo.name, color = text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            StatusPill(if (active == 0) "clear" else "$active active", if (active == 0) green else amber)
        }
        Text(issueSourceBreakdown(listOf(repo)).ifBlank { "Arcana 0" }, color = muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("todo ${repo.issues.todo}", color = Color(0xFF8EA0B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("wip ${repo.issues.wip}", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("blocked ${repo.issues.blocked}", color = rose, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("review ${repo.issues.review}", color = amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun IssuesHeader(summary: OpsSummaryDto) {
    val active = summary.repos.sumOf { it.issues.active }
    val done = summary.repos.sumOf { it.issues.done }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, amber, glowAlpha = 0.08f, borderAlpha = 0.26f)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Issue Command Board", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Arcana local issue files and GitHub Issues are counted together, with source splits kept visible.", color = muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("$active active", if (active > 0) amber else green)
            StatusPill("$done done", muted)
        }
    }
}

@Composable
private fun IssueBoard(repos: List<RepoHealthDto>) {
    BoxWithConstraints {
        if (maxWidth < 1180.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                issueLanes.forEach { lane -> IssueLane(lane, repos, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                issueLanes.forEach { lane ->
                    IssueLane(lane, repos, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun IssueLane(lane: IssueLaneSpec, repos: List<RepoHealthDto>, modifier: Modifier = Modifier) {
    val tickets = repos.mapNotNull { repo -> lane.count(repo).takeIf { it > 0 }?.let { repo to it } }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .glassSurface(shape, lane.color, glowAlpha = 0.07f, borderAlpha = 0.26f)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(lane.label, color = lane.color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            StatusPill(tickets.sumOf { it.second }.toString(), lane.color)
        }
        if (tickets.isEmpty()) {
            EmptyLane(lane)
        } else {
            tickets.forEach { (repo, count) -> IssueTicket(lane, repo, count) }
        }
    }
}

@Composable
private fun EmptyLane(lane: IssueLaneSpec) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 82.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0B1117))
            .border(BorderStroke(1.dp, Color(0xFF1C2632)), RoundedCornerShape(7.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("No ${lane.label.lowercase()} items", color = Color(0xFF697789), fontSize = 12.sp)
    }
}

@Composable
private fun IssueTicket(lane: IssueLaneSpec, repo: RepoHealthDto, count: Int) {
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

private val issueLanes = listOf(
    IssueLaneSpec("TODO", Color(0xFF8EA0B8)) { it.issues.todo },
    IssueLaneSpec("WIP", cyan) { it.issues.wip },
    IssueLaneSpec("BLOCKED", rose) { it.issues.blocked },
    IssueLaneSpec("REVIEW", amber) { it.issues.review },
    IssueLaneSpec("DONE", green) { it.issues.done },
)
