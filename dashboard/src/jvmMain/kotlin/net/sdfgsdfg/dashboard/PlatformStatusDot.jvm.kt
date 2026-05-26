package net.sdfgsdfg.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import net.sdfgsdfg.data.model.OpsStatusDto

@Composable
internal actual fun PlatformStatusDot(status: OpsStatusDto) {
    val glow = if (status == OpsStatusDto.OK || status == OpsStatusDto.FAIL) 0.65f else 0.20f
    val color = status.color()
    val fullGlow = status == OpsStatusDto.OK || status == OpsStatusDto.FAIL
    val inactiveGlow = if (fullGlow) 1f else 0.5f
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val core = 5.8.dp.toPx()
            val gap = core * 1.06f
            val bloom = size.minDimension * (0.40f + glow * 0.15f)
            val bloomCenter = Offset(center.x - size.width * 0.010f, center.y - size.height * 0.018f)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to color.copy(alpha = if (fullGlow) 0.006f + glow * 0.030f else 0.010f + glow * 0.030f),
                        0.26f to color.copy(alpha = if (fullGlow) 0.018f + glow * 0.090f else (0.030f + glow * 0.055f) * inactiveGlow),
                        0.58f to color.copy(alpha = if (fullGlow) 0.035f + glow * 0.140f else (0.020f + glow * 0.060f) * inactiveGlow),
                        0.82f to color.copy(alpha = if (fullGlow) 0.012f + glow * 0.055f else (0.008f + glow * 0.028f) * inactiveGlow),
                        1.00f to Color.Transparent,
                    ),
                    center = bloomCenter,
                    radius = bloom,
                ),
                radius = bloom,
                center = bloomCenter,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Black.copy(alpha = 0.14f),
                        0.70f to Color.Black.copy(alpha = 0.10f),
                        1.00f to Color.Transparent,
                    ),
                    center = center,
                    radius = gap * 1.25f,
                ),
                radius = gap,
                center = center,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Transparent,
                        0.36f to color.copy(alpha = if (fullGlow) 0.06f + glow * 0.18f else (0.04f + glow * 0.08f) * inactiveGlow),
                        0.74f to color.copy(alpha = if (fullGlow) 0.025f + glow * 0.07f else (0.016f + glow * 0.035f) * inactiveGlow),
                        1.00f to Color.Transparent,
                    ),
                    center = Offset(center.x - size.width * 0.024f, center.y - size.height * 0.032f),
                    radius = core * (2.35f + glow * 0.35f),
                ),
                radius = core * (2.35f + glow * 0.35f),
                center = Offset(center.x - size.width * 0.010f, center.y - size.height * 0.014f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color.White.copy(alpha = 0.46f + glow * 0.18f),
                        0.20f to color.copy(alpha = 0.92f),
                        0.68f to color.copy(alpha = 0.78f + glow * 0.12f),
                        1.00f to color.copy(alpha = 0.58f + glow * 0.08f),
                    ),
                    center = Offset(center.x - core * 0.24f, center.y - core * 0.28f),
                    radius = core * 1.55f,
                ),
                radius = core,
                center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.28f + glow * 0.16f),
                radius = 1.25.dp.toPx(),
                center = Offset(center.x - core * 0.38f, center.y - core * 0.42f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.18f),
                radius = core,
                center = center,
                style = Stroke(width = 0.8.dp.toPx()),
            )
        }
    }
}
