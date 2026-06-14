package net.sdfgsdfg.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.data.model.IssueItemDto

@Composable
internal fun IssueTicketCard(
    lane: IssueLaneSpec,
    issue: IssueItemDto,
    issueCode: String,
    generatedAtMs: Long,
    modifier: Modifier = Modifier,
    hovered: Boolean = false,
    dragTone: Float = 0f,
    motionLabel: String? = null,
    motionFlash: Boolean = true,
    animatedFreshness: Boolean = true,
    onArchive: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val timestamp = issue.updatedAtMs ?: issue.createdAtMs ?: issue.completedAtMs
    val flash = if (motionFlash) {
        val animated by animateFloatAsState(
            targetValue = if (motionLabel == null) 0f else 1f,
            animationSpec = tween(if (motionLabel == null) issueMotionFlashOutMs else issueMotionFlashInMs, easing = FastOutSlowInEasing),
            label = "issue-motion-flash",
        )
        animated
    } else {
        0f
    }
    val washAlpha = (flash * 0.10f + (if (hovered) 0.09f else 0f) + dragTone * 0.11f).coerceAtMost(0.32f)
    val borderAlpha = (0.28f + flash * 0.26f + (if (hovered) 0.30f else 0f) + dragTone * 0.34f).coerceAtMost(0.92f)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(panelRaised)
            .background(lane.color.copy(alpha = washAlpha))
            .border(BorderStroke(1.dp, lane.color.copy(alpha = borderAlpha)), RoundedCornerShape(7.dp))
            .padding(9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FreshRail(timestamp, generatedAtMs, height = 52.dp, animated = animatedFreshness)
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
            motionLabel?.takeUnless { it == "new" }?.let { UpdatePill(lane.color, it) }
            if (onArchive != null || onDelete != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    onArchive?.let { ArchiveButton(color = amber, compact = true, onClick = it) }
                    onDelete?.let { DeleteButton(onClick = it) }
                }
            }
        }
    }
}
