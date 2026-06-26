package net.sdfgsdfg.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.data.model.IssueEventDto

@Composable
internal fun IssueEventStrip(
    board: IssueBoardModel,
    animatedFreshness: Boolean = true,
    motionSafeSurface: Boolean = false,
) {
    val events = remember(board.events) {
        board.events
            .sortedByDescending { it.event.tsMs ?: 0L }
            .take(6)
    }
    if (events.isEmpty()) return
    val shape = RoundedCornerShape(8.dp)
    val expandedPrefKey = "ops.issues.eventsExpanded"
    var expanded by remember { mutableStateOf(readDashboardPref(expandedPrefKey)?.toBooleanStrictOrNull() ?: true) }
    val surface = Modifier.fillMaxWidth().let {
        if (motionSafeSurface) it.motionSurface(shape, cyan, borderAlpha = 0.22f)
        else it.glassSurface(shape, cyan, glowAlpha = 0.05f, borderAlpha = 0.22f)
    }
    Column(
        modifier = surface
            .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent Issue Events", color = text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                MiniActionPill(if (expanded) "hide" else "show", cyan) {
                    expanded = !expanded
                    writeDashboardPref(expandedPrefKey, expanded.toString())
                }
            }
            StatusPill("${events.size} shown", cyan)
        }
        if (expanded) {
            events.forEach { item ->
                key("${item.repo.id}:${item.event.eventId}") {
                    IssueEventRow(item.repo, item.event, board.generatedAtMs, animatedFreshness)
                }
            }
        }
    }
}

@Composable
private fun IssueEventRow(repo: IssueRepoModel, event: IssueEventDto, generatedAtMs: Long, animatedFreshness: Boolean) {
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
        FreshRail(event.tsMs, generatedAtMs, height = 34.dp, animated = animatedFreshness)
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
