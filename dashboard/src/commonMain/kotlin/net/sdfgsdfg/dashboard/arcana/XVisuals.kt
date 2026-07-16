package net.sdfgsdfg.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.dashboard.generated.resources.Res
import net.sdfgsdfg.dashboard.generated.resources.fraunces
import org.jetbrains.compose.resources.Font
import kotlin.math.max

internal enum class XSyncStage { IDLE, INITIALIZING, SYNCING, SYNCHRONIZED, ERROR }

internal data class XSyncUiState(
    val stage: XSyncStage = XSyncStage.IDLE,
    val progress: Int = 0,
    val message: String = "Select a repository",
)

private val xSelectedCardInnerShadow = Shadow(
    radius = 9.dp,
    spread = 2.dp,
    offset = DpOffset(0.dp, 1.dp),
    color = Color(0xFF13FF64),
    alpha = 0.92f,
)

@Composable
internal fun XBackdrop() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xF2070A0F),
                    0.30f to Color(0xC8060A11),
                    0.70f to Color(0x76040A12),
                    1f to Color(0x26000000),
                ),
            )
            .background(
                Brush.horizontalGradient(
                    0f to Color(0xB8082134),
                    0.38f to Color.Transparent,
                    0.76f to Color(0x401A0623),
                    1f to Color(0x781E0711),
                ),
            ),
    )
}

@Composable
internal fun XGlassCard(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val progress = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 80f),
        label = "x-glass-selection",
    )
    val breathing = if (selected) {
        rememberInfiniteTransition(label = "x-card-breathing").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2_500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "x-card-breathing-progress",
        )
    } else {
        null
    }
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .padding(horizontal = if (selected) 8.dp else 5.dp, vertical = if (selected) 7.dp else 4.dp)
            .xGlass(progress)
            .graphicsLayer {
                val breath = breathing?.value ?: 0f
                scaleX = 1f + breath * 0.018f
                scaleY = 1f + breath * 0.018f
                clip = false
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .then(
                if (selected) {
                    Modifier
                        .innerShadow(shape, xSelectedCardInnerShadow)
                        .border(BorderStroke(1.dp, Color(0xFF1FFF70).copy(alpha = 0.72f)), shape)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

private fun Modifier.xGlass(progress: State<Float>) = drawBehind {
    val radius = 14.dp.toPx()
    val gap = 1.8.dp.toPx()
    val stroke = 0.7.dp.toPx()
    val corner = CornerRadius(radius, radius)

    drawRoundRect(
        brush = Brush.horizontalGradient(
            0f to Color(0xFF8FB5DA).copy(alpha = 0.26f),
            0.44f to Color.Transparent,
            0.85f to Color(0xFF8FB5DA).copy(alpha = 0.16f),
            1f to Color.Transparent,
        ),
        cornerRadius = corner,
    )
    drawRoundRect(
        brush = Brush.radialGradient(
            listOf(Color(0xFF8FB5DA).copy(alpha = 0.30f), Color.Transparent),
            center = Offset(radius * 0.9f, radius * 0.9f),
            radius = radius * 2.2f,
        ),
        blendMode = BlendMode.Plus,
        cornerRadius = corner,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.11f to Color.White.copy(alpha = 0.12f),
            0.44f to Color.Transparent,
            0.88f to Color(0xFF8FB5DA).copy(alpha = 0.01f),
            1f to Color.Transparent,
        ),
        blendMode = BlendMode.Lighten,
        cornerRadius = corner,
    )

    fun rim(step: Int, brush: Brush) {
        val inset = gap * step
        drawRoundRect(
            brush = brush,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            style = Stroke(stroke),
            cornerRadius = CornerRadius(max(0f, radius - inset), max(0f, radius - inset)),
        )
    }
    rim(1, SolidColor(Color.White.copy(alpha = 0.12f + 0.10f * progress.value)))
    rim(2, SolidColor(Color.Black.copy(alpha = 0.30f)))
    rim(
        3,
        Brush.sweepGradient(
            0f to Color(0xFFC9E9FF).copy(alpha = 0.08f),
            0.22f to Color(0xFFC9E9FF).copy(alpha = 0.34f),
            0.34f to Color(0xFFC9E9FF).copy(alpha = 0.84f),
            0.72f to Color(0xFFC9E9FF).copy(alpha = 0.28f),
            1f to Color(0xFFC9E9FF).copy(alpha = 0.08f),
        ),
    )
    val inset = gap * 3f
    drawArc(
        color = Color.White.copy(alpha = 0.48f),
        startAngle = 180f,
        sweepAngle = 65f,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        style = Stroke(0.2f),
    )
}

@Composable
internal fun XSkeuoText(
    value: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFE3E3E3),
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
) {
    val family = FontFamily(Font(Res.font.fraunces, weight))
    val base = TextStyle(fontSize = fontSize, fontFamily = family, fontWeight = weight)
    val hi = color.xLighten(0.60f)
    val midHi = color.xLighten(0.20f)
    val low = color.xDarken(0.45f)
    val fill = Brush.verticalGradient(
        0f to hi.copy(alpha = 0.85f),
        0.20f to midHi.copy(alpha = 0.65f),
        0.60f to color,
        1f to low,
    )
    Box(modifier.graphicsLayer { clip = false }) {
        Text(value, style = base, color = hi.copy(alpha = 0.14f), maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.blur(4.dp))
        Text(
            value,
            style = base.merge(TextStyle(brush = fill, shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.60f), Offset(0f, 2f), 4f))),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = base,
            color = Color.Black.copy(alpha = 0.58f),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(y = 1.dp).blur(1.dp),
        )
        Text(
            value,
            style = base,
            color = hi.copy(alpha = 0.25f),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(x = (-2).dp, y = (-3.5).dp).blur(4.8.dp),
        )
    }
}

private fun Color.xLighten(fraction: Float) = Color(
    red = red + (1f - red) * fraction,
    green = green + (1f - green) * fraction,
    blue = blue + (1f - blue) * fraction,
    alpha = alpha,
)

private fun Color.xDarken(fraction: Float) = Color(
    red = red * (1f - fraction),
    green = green * (1f - fraction),
    blue = blue * (1f - fraction),
    alpha = alpha,
)

@Composable
internal fun XGlowComparison(
    connected: Boolean,
    latencyMs: Long?,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            XDonorGlow(if (connected) Color(0xFF00FF9D) else Color(0xFFFFC244))
            Column {
                Text(if (connected) "Online" else "Connecting…", color = Color.White, fontSize = 12.sp)
                Text("Latency: ${latencyMs?.let { "${it}ms" } ?: "—"}", color = Color(0xFF34D399), fontSize = 10.sp)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            XOpsRadialGlow(if (connected) cyan else amber)
            Text("ops radial", color = cyan.copy(alpha = 0.72f), fontSize = 9.sp)
        }
    }
}

@Composable
private fun XDonorGlow(color: Color) {
    val pulse = rememberInfiniteTransition(label = "x-donor-heartbeat").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 2_200
                0f at 0 using LinearOutSlowInEasing
                1f at 1_100 using FastOutSlowInEasing
                0f at 2_200 using FastOutSlowInEasing
            },
            RepeatMode.Reverse,
        ),
        label = "x-donor-heartbeat-pulse",
    )
    Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val progress = pulse.value
                    val radius = lerp(2.dp, 24.dp, 0.6f + 0.4f * progress)
                    val alpha = 0.5f + 0.3f * progress
                    val r = radius.toPx().coerceAtLeast(1f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to color.copy(alpha = alpha * 0.6f),
                            0.5f to color.copy(alpha = alpha * 0.3f),
                            1f to Color.Transparent,
                            radius = r,
                        ),
                        radius = r,
                    )
                }
                .blur(16.dp),
        )
        Box(
            Modifier
                .size(12.dp)
                .background(color, CircleShape)
                .innerShadow(
                    CircleShape,
                    Shadow(radius = 2.6.dp, spread = 0.8.dp, offset = DpOffset(1.5.dp, 1.dp), color = Color.Black, alpha = 0.8f),
                ),
        )
    }
}

/** The previously-commented Compose status light, deliberately scoped to X for live comparison. */
@Composable
private fun XOpsRadialGlow(color: Color) {
    val glow by rememberInfiniteTransition(label = "x-ops-radial").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "x-ops-radial-progress",
    )
    Canvas(
        Modifier
            .size(36.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val core = 5.8.dp.toPx()
        val gap = core * 1.06f
        val bloom = size.minDimension * (0.40f + glow * 0.15f)
        val bloomCenter = Offset(center.x - size.width * 0.010f, center.y - size.height * 0.018f)
        drawCircle(
            Brush.radialGradient(
                0f to color.copy(alpha = 0.006f + glow * 0.030f),
                0.26f to color.copy(alpha = 0.018f + glow * 0.090f),
                0.58f to color.copy(alpha = 0.035f + glow * 0.140f),
                0.82f to color.copy(alpha = 0.012f + glow * 0.055f),
                1f to Color.Transparent,
                center = bloomCenter,
                radius = bloom,
            ),
            bloom,
            bloomCenter,
        )
        drawCircle(
            Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.14f),
                0.70f to Color.Black.copy(alpha = 0.10f),
                1f to Color.Transparent,
                radius = gap * 1.25f,
            ),
            gap,
        )
        drawCircle(
            Brush.radialGradient(
                0f to Color.Transparent,
                0.36f to color.copy(alpha = 0.06f + glow * 0.18f),
                0.74f to color.copy(alpha = 0.025f + glow * 0.07f),
                1f to Color.Transparent,
                radius = core * (2.35f + glow * 0.35f),
            ),
            core * (2.35f + glow * 0.35f),
            Offset(center.x - size.width * 0.010f, center.y - size.height * 0.014f),
        )
        drawCircle(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.46f + glow * 0.18f),
                0.20f to color.copy(alpha = 0.92f),
                0.68f to color.copy(alpha = 0.78f + glow * 0.12f),
                1f to color.copy(alpha = 0.58f + glow * 0.08f),
                center = Offset(center.x - core * 0.24f, center.y - core * 0.28f),
                radius = core * 1.55f,
            ),
            core,
        )
        drawCircle(Color.White.copy(alpha = 0.28f + glow * 0.16f), 1.25.dp.toPx(), Offset(center.x - core * 0.38f, center.y - core * 0.42f))
        drawCircle(Color.White.copy(alpha = 0.18f), core, style = Stroke(0.8.dp.toPx()))
    }
}

@Composable
internal fun XWorkspaceStatus(state: XSyncUiState, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(
        state.progress.coerceIn(0, 100) / 100f,
        spring(stiffness = 50f, dampingRatio = 0.5f),
        label = "x-workspace-progress",
    )
    val tint by animateColorAsState(
        when (state.stage) {
            XSyncStage.ERROR -> Color.Red
            XSyncStage.INITIALIZING, XSyncStage.SYNCING -> Color.Yellow
            XSyncStage.SYNCHRONIZED -> Color.Green
            XSyncStage.IDLE -> Color(0xFF2478FF)
        }.copy(alpha = 0.20f),
        tween(180),
        label = "x-workspace-tint",
    )
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(Color.Transparent)
            .drawBehind {
                val width = size.width * fraction
                if (width > 0f) drawRect(tint, size = Size(width, size.height))
            }
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(state.message, color = Color(0xFFE6E6E6), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
