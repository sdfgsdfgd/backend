package net.sdfgsdfg.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
internal fun MiniActionPill(label: String, color: Color, onClick: () -> Unit) {
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
internal fun ArchiveButton(
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
internal fun DeleteButton(onClick: () -> Unit) {
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
