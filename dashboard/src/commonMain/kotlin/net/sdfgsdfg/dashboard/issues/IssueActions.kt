package net.sdfgsdfg.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
internal fun EditButton(color: Color = cyan, compact: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.055f),
                        color.copy(alpha = 0.050f),
                        Color.Black.copy(alpha = 0.14f),
                    ),
                ),
            )
            .border(BorderStroke(1.dp, color.copy(alpha = 0.28f)), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 9.dp, vertical = if (compact) 3.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(if (compact) 11.dp else 12.dp)) {
            val stroke = 1.dp.toPx()
            val shaftStart = Offset(size.width * 0.28f, size.height * 0.72f)
            val shaftEnd = Offset(size.width * 0.72f, size.height * 0.28f)
            drawLine(color.copy(alpha = 0.68f), shaftStart, shaftEnd, strokeWidth = stroke * 1.55f, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.12f), Offset(size.width * 0.34f, size.height * 0.66f), Offset(size.width * 0.66f, size.height * 0.34f), strokeWidth = stroke * 0.65f, cap = StrokeCap.Round)
            drawLine(color.copy(alpha = 0.42f), Offset(size.width * 0.23f, size.height * 0.78f), Offset(size.width * 0.40f, size.height * 0.74f), strokeWidth = stroke * 0.85f, cap = StrokeCap.Round)
            drawLine(color.copy(alpha = 0.46f), Offset(size.width * 0.64f, size.height * 0.22f), Offset(size.width * 0.78f, size.height * 0.36f), strokeWidth = stroke * 0.90f, cap = StrokeCap.Round)
        }
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
                        Color.White.copy(alpha = 0.06f),
                        color.copy(alpha = 0.055f),
                        Color.Black.copy(alpha = 0.14f),
                    ),
                ),
            )
            .border(BorderStroke(1.dp, color.copy(alpha = 0.30f)), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact && count == null) 6.dp else if (compact) 7.dp else 9.dp, vertical = if (compact) 3.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(if (compact) 11.dp else 12.dp)) {
            val stroke = 1.dp.toPx()
            val bodyTop = size.height * 0.34f
            val body = Size(size.width * 0.78f, size.height * 0.52f)
            val left = size.width * 0.11f
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), color.copy(alpha = 0.045f))),
                topLeft = Offset(left, bodyTop),
                size = body,
                cornerRadius = CornerRadius(size.width * 0.10f, size.width * 0.10f),
            )
            drawRoundRect(
                color = color.copy(alpha = 0.62f),
                topLeft = Offset(left, bodyTop),
                size = body,
                cornerRadius = CornerRadius(size.width * 0.10f, size.width * 0.10f),
                style = Stroke(stroke),
            )
            drawRoundRect(
                color = color.copy(alpha = 0.48f),
                topLeft = Offset(size.width * 0.20f, size.height * 0.18f),
                size = Size(size.width * 0.60f, size.height * 0.18f),
                cornerRadius = CornerRadius(size.width * 0.07f, size.width * 0.07f),
                style = Stroke(stroke),
            )
            drawLine(color.copy(alpha = 0.60f), Offset(size.width * 0.24f, bodyTop), Offset(size.width * 0.76f, bodyTop), strokeWidth = stroke)
            drawLine(color.copy(alpha = 0.66f), Offset(size.width * 0.39f, size.height * 0.56f), Offset(size.width * 0.61f, size.height * 0.56f), strokeWidth = stroke)
        }
        if (!compact || count != null) {
            Text(
                count?.let { "Archive $it" } ?: "Archive",
                color = color.copy(alpha = 0.84f),
                fontSize = if (compact) 9.sp else 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun DeleteButton(onClick: () -> Unit) {
    val color = Color(0xFFE7A1A1)
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .size(25.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.055f), color.copy(alpha = 0.040f), Color.Black.copy(alpha = 0.14f))))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.28f)), shape)
            .clickable(onClick = onClick)
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(13.dp)) {
            val stroke = 1.25.dp.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawCircle(color.copy(alpha = 0.055f), radius = size.minDimension * 0.42f, center = Offset(cx, cy))
            drawCircle(color.copy(alpha = 0.34f), radius = size.minDimension * 0.36f, center = Offset(cx, cy), style = Stroke(stroke * 0.82f))
            drawLine(color.copy(alpha = 0.72f), Offset(size.width * 0.33f, size.height * 0.33f), Offset(size.width * 0.67f, size.height * 0.67f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color.copy(alpha = 0.72f), Offset(size.width * 0.67f, size.height * 0.33f), Offset(size.width * 0.33f, size.height * 0.67f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
