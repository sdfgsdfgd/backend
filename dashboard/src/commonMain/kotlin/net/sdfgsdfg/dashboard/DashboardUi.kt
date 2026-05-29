package net.sdfgsdfg.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.sdfgsdfg.data.model.IssueSummaryDto
import net.sdfgsdfg.data.model.OpsSignalDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.RepoHealthDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
import kotlin.math.roundToInt

internal val background = Color(0xFF090C10)
internal val panel = Color(0xFF121820)
internal val panelRaised = Color(0xFF171E27)
internal val border = Color(0xFF263240)
internal val muted = Color(0xFF92A0B2)
internal val text = Color(0xFFE8ECF2)
internal val cyan = Color(0xFF75D4FF)
internal val green = Color(0xFF5CE58B)
internal val amber = Color(0xFFFFC86B)
internal val rose = Color(0xFFFF7474)
internal val liveProcessCount = Regex("""(\d+) (arcana|codex) live""")
internal const val OPS_SUMMARY_REFRESH_MS = 45_000L
internal const val UPDATE_FLASH_MS = 5 * 60 * 1_000L


internal data class FieldSpec(val name: String, val value: String, val detail: String? = null)
internal data class IssueLaneSpec(val label: String, val color: Color, val count: (RepoHealthDto) -> Int)
internal data class BadgeSpec(val label: String, val color: Color, val strong: Boolean = false)

@Composable
internal fun rememberFreshKeys(keys: List<String>): Set<String> {
    var known by remember { mutableStateOf<Set<String>?>(null) }
    var fresh by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(keys) {
        val current = keys.toSet()
        val nextFresh = known?.let { current - it }.orEmpty()
        known = current
        fresh = nextFresh
        if (nextFresh.isNotEmpty()) {
            delay(UPDATE_FLASH_MS)
            fresh = emptySet()
        }
    }
    return fresh
}

@Composable
internal fun Field(field: FieldSpec) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(field.name.uppercase(), color = Color(0xFF748195), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(field.value, color = Color(0xFFDCE4EE), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        field.detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun WorkSurface(title: String, detail: String, items: List<String>) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, cyan, glowAlpha = 0.06f, borderAlpha = 0.28f)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = muted, fontSize = 13.sp, lineHeight = 19.sp)
        BoxWithConstraints {
            if (maxWidth < 720.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { PlaceholderTile(it) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item ->
                        Box(modifier = Modifier.weight(1f)) {
                            PlaceholderTile(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaceholderTile(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D131A))
            .border(BorderStroke(1.dp, Color(0xFF202B38)), RoundedCornerShape(7.dp))
            .padding(12.dp),
    ) {
        Text(label, color = Color(0xFFD3DCE8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StatusPill(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.38f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun PanelBadge(badge: BadgeSpec, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        badge.color.copy(alpha = if (badge.strong) 0.20f else 0.12f),
                        Color.White.copy(alpha = 0.035f),
                    ),
                ),
            )
            .border(BorderStroke(1.dp, badge.color.copy(alpha = if (badge.strong) 0.52f else 0.30f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(5.dp)) {
            drawCircle(badge.color.copy(alpha = 0.18f), radius = size.width * 0.88f)
            drawCircle(badge.color, radius = size.width * 0.42f)
        }
        Text(
            badge.label,
            color = if (badge.strong) text else Color(0xFFD2DCE9),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun UpdatePill(color: Color, label: String = "new") {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.16f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.42f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun RunTail(run: TestRunSummaryDto, generatedAtMs: Long, label: String = run.status.name, fontSize: TextUnit = 12.sp) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        run.timestampMs?.let { AgePill(it, generatedAtMs) }
        Text(label, color = run.status.color(), fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun FreshRail(timestampMs: Long?, generatedAtMs: Long, height: Dp = 42.dp) {
    val recent = timestampMs?.let { generatedAtMs - it in 0..(15 * 60 * 1_000) } == true
    val color = timestampMs?.ageColor(generatedAtMs) ?: Color(0xFF6F7A89)
    val pulse = if (recent) {
        val transition = rememberInfiniteTransition(label = "fresh-rail")
        val value by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(900, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
            label = "fresh-rail-pulse",
        )
        value
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = if (recent) 0.34f + pulse * 0.34f else 0.26f)),
    )
}

@Composable
internal fun AgePill(timestampMs: Long, generatedAtMs: Long) {
    val color = timestampMs.ageColor(generatedAtMs)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.11f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.30f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(timestampMs.relativeFrom(generatedAtMs), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StatusDot(status: OpsStatusDto) {
    // Compose/Wasm reference only: continuous Canvas radial-gradient animation caused global frame jank.
    // val transition = rememberInfiniteTransition(label = "status-dot")
    // val glow by transition.animateFloat(
    //     initialValue = 0f,
    //     targetValue = 1f,
    //     animationSpec = infiniteRepeatable(
    //         animation = tween(if (status == OpsStatusDto.OK) 3_600 else 1_900, easing = FastOutSlowInEasing),
    //         repeatMode = RepeatMode.Reverse,
    //     ),
    //     label = "status-dot-glow",
    // )
    /*
     * Replay-only body removed from runtime:
     * - kept here because it reproduced whole-page lag in Compose/Wasm;
     * - do not call this on Wasm unless intentionally recording/debugging the regression;
     * - the shipped web path is PlatformStatusDot(status), which delegates to a DOM/CSS overlay.
     *
     * @Composable
     * private fun ComposeLagStatusDot(status: OpsStatusDto) {
     *     val transition = rememberInfiniteTransition(label = "status-dot")
     *     val glow by transition.animateFloat(
     *         initialValue = 0f,
     *         targetValue = 1f,
     *         animationSpec = infiniteRepeatable(
     *             animation = tween(if (status == OpsStatusDto.OK) 3_600 else 1_900, easing = FastOutSlowInEasing),
     *             repeatMode = RepeatMode.Reverse,
     *         ),
     *         label = "status-dot-glow",
     *     )
     *     val color = status.color()
     *     val fullGlow = status == OpsStatusDto.OK || status == OpsStatusDto.FAIL
     *     val inactiveGlow = if (fullGlow) 1f else 0.5f
     *     Box(
     *         modifier = Modifier
     *             .padding(top = 6.dp)
     *             .size(36.dp),
     *         contentAlignment = Alignment.Center,
     *     ) {
     *         Canvas(Modifier.fillMaxSize()) {
     *             val core = 5.8.dp.toPx()
     *             val gap = core * 1.06f
     *             val bloom = size.minDimension * (0.40f + glow * 0.15f)
     *             val bloomCenter = Offset(center.x - size.width * 0.010f, center.y - size.height * 0.018f)
     *             drawCircle(
     *                 brush = Brush.radialGradient(
     *                     colorStops = arrayOf(
     *                         0.00f to color.copy(alpha = if (fullGlow) 0.006f + glow * 0.030f else 0.010f + glow * 0.030f),
     *                         0.26f to color.copy(alpha = if (fullGlow) 0.018f + glow * 0.090f else (0.030f + glow * 0.055f) * inactiveGlow),
     *                         0.58f to color.copy(alpha = if (fullGlow) 0.035f + glow * 0.140f else (0.020f + glow * 0.060f) * inactiveGlow),
     *                         0.82f to color.copy(alpha = if (fullGlow) 0.012f + glow * 0.055f else (0.008f + glow * 0.028f) * inactiveGlow),
     *                         1.00f to Color.Transparent,
     *                     ),
     *                     center = bloomCenter,
     *                     radius = bloom,
     *                 ),
     *                 radius = bloom,
     *                 center = bloomCenter,
     *             )
     *             drawCircle(
     *                 brush = Brush.radialGradient(
     *                     colorStops = arrayOf(
     *                         0.00f to Color.Black.copy(alpha = 0.14f),
     *                         0.70f to Color.Black.copy(alpha = 0.10f),
     *                         1.00f to Color.Transparent,
     *                     ),
     *                     center = center,
     *                     radius = gap * 1.25f,
     *                 ),
     *                 radius = gap,
     *                 center = center,
     *             )
     *             drawCircle(
     *                 brush = Brush.radialGradient(
     *                     colorStops = arrayOf(
     *                         0.00f to Color.Transparent,
     *                         0.36f to color.copy(alpha = if (fullGlow) 0.06f + glow * 0.18f else (0.04f + glow * 0.08f) * inactiveGlow),
     *                         0.74f to color.copy(alpha = if (fullGlow) 0.025f + glow * 0.07f else (0.016f + glow * 0.035f) * inactiveGlow),
     *                         1.00f to Color.Transparent,
     *                     ),
     *                     center = Offset(center.x - size.width * 0.024f, center.y - size.height * 0.032f),
     *                     radius = core * (2.35f + glow * 0.35f),
     *                 ),
     *                 radius = core * (2.35f + glow * 0.35f),
     *                 center = Offset(center.x - size.width * 0.010f, center.y - size.height * 0.014f),
     *             )
     *             drawCircle(
     *                 brush = Brush.radialGradient(
     *                     colorStops = arrayOf(
     *                         0.00f to Color.White.copy(alpha = 0.46f + glow * 0.18f),
     *                         0.20f to color.copy(alpha = 0.92f),
     *                         0.68f to color.copy(alpha = 0.78f + glow * 0.12f),
     *                         1.00f to color.copy(alpha = 0.58f + glow * 0.08f),
     *                     ),
     *                     center = Offset(center.x - core * 0.24f, center.y - core * 0.28f),
     *                     radius = core * 1.55f,
     *                 ),
     *                 radius = core,
     *                 center = center,
     *             )
     *             drawCircle(
     *                 color = Color.White.copy(alpha = 0.28f + glow * 0.16f),
     *                 radius = 1.25.dp.toPx(),
     *                 center = Offset(center.x - core * 0.38f, center.y - core * 0.42f),
     *             )
     *             drawCircle(
     *                 color = Color.White.copy(alpha = 0.18f),
     *                 radius = core,
     *                 center = center,
     *                 style = Stroke(width = 0.8.dp.toPx()),
     *             )
     *         }
     *     }
     * }
     */
    PlatformStatusDot(status)
}

internal fun issueSourceBreakdown(repos: List<RepoHealthDto>): String = repos
    .flatMap { it.issues.sources }
    .groupBy { it.id }
    .map { (id, sources) ->
        val label = when (id) {
            "arcana" -> "Arcana"
            "github" -> "GitHub"
            else -> sources.firstOrNull()?.label ?: id
        }
        "$label ${sources.sumOf { it.active }}"
    }
    .joinToString(" · ")

internal fun IssueSummaryDto.badgeSpec() = BadgeSpec(
    label = if (active == 1) "1 issue" else "$active issues",
    color = if (active == 0) green else amber,
    strong = active > 0,
)

internal fun RepoHealthDto.testBadges(): List<BadgeSpec> = when (id) {
    "backend" -> listOfNotNull(
        runs.firstOrNull { it.label == "unit tests" }?.testBadge("unit"),
        runs.firstOrNull { it.label == "full suite" }?.testBadge("suite"),
    )
    "server_py" -> listOfNotNull(
        runs.firstOrNull { it.label == "unit tests" }?.testBadge("unit"),
        runs.firstOrNull { it.label == "live e2e selftest" }?.testBadge("e2e")
            ?: selfTest?.let { BadgeSpec("TEST: e2e ${it.status.name}", it.status.color(), strong = it.status != OpsStatusDto.UNKNOWN) },
    )
    "arcana" -> listOfNotNull(
        (latestRun?.takeIf { it.isArcanaTestRun() } ?: runs.firstOrNull { it.isArcanaTestRun() })?.testBadge("unit"),
    )
    else -> emptyList()
}

private fun TestRunSummaryDto.testBadge(kind: String) =
    BadgeSpec("TEST: $kind ${status.name}", status.color(), strong = status != OpsStatusDto.UNKNOWN)

internal fun TestRunSummaryDto.isArcanaTestRun() = label.contains("pytest", ignoreCase = true) ||
    label.contains("z_tests", ignoreCase = true) ||
    detail?.contains("passed", ignoreCase = true) == true

internal fun RepoHealthDto.runtimeBadges(): List<BadgeSpec> {
    val labels = runtimeLabels.ifEmpty { runtimeLabel?.let(::listOf).orEmpty() }
    val visibleProcesses = signals.firstOrNull { it.isActiveProcessSummary() }
    val arcanaStatuses = if (id == "arcana") {
        val labeled = visibleProcesses?.detail
            ?.split(" / ")
            .orEmpty()
            .mapNotNull { segment ->
                segment.substringBefore(": ", missingDelimiterValue = "")
                    .takeIf { it.isNotBlank() }
                    ?.let { it to segment.substringAfter(": ") }
            }
        labeled.ifEmpty { visibleProcesses?.let { listOf(it.meta to it.detail.orEmpty()) }.orEmpty() }
            .associate { (label, detail) ->
                label to if (liveProcessCount.findAll(detail).sumOf { it.groupValues[1].toInt() } > 0) OpsStatusDto.OK else OpsStatusDto.UNKNOWN
            }
    } else {
        emptyMap()
    }
    return labels.mapNotNull { label ->
        val status = when (id) {
            "arcana" -> arcanaStatuses[label]
            "backend" -> OpsStatusDto.OK
            else -> signals.firstOrNull { it.meta == label }?.status
        }
        when {
            label == "local" && status != OpsStatusDto.OK -> null
            label == "remote q" && status != OpsStatusDto.OK -> BadgeSpec(label, rose)
            status == OpsStatusDto.OK -> BadgeSpec(label, green)
            else -> BadgeSpec(label, cyan)
        }
    }
}

internal fun OpsSignalDto.isActiveProcessSummary() = label == "active" || label.startsWith("visible ")

internal fun Modifier.glassSurface(
    shape: RoundedCornerShape,
    accent: Color,
    glowAlpha: Float,
    borderAlpha: Float,
): Modifier = this
    .surfaceDepth(shape, accent, glowAlpha)
    .background(Color.Black.copy(alpha = 0.62f), shape)
    .background(
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.13f),
                Color.Transparent,
                accent.copy(alpha = 0.055f),
                Color.Black.copy(alpha = 0.18f),
            ),
            start = Offset(-90f, -60f),
            end = Offset(520f, 700f),
        ),
        shape,
    )
    .innerShadow(
        shape,
        Shadow(
            radius = 18.dp,
            spread = (-7).dp,
            offset = DpOffset((-4).dp, 3.dp),
            color = Color.White,
            alpha = 0.20f,
        ),
    )
    .innerShadow(
        shape,
        Shadow(
            radius = 32.dp,
            spread = (-12).dp,
            offset = DpOffset(0.dp, (-14).dp),
            color = Color.Black,
            alpha = 0.42f,
        ),
    )
    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.17f)), shape)
    .border(BorderStroke(1.dp, accent.copy(alpha = borderAlpha)), shape)

internal fun OpsStatusDto.color(): Color = when (this) {
    OpsStatusDto.OK -> green
    OpsStatusDto.WARN -> amber
    OpsStatusDto.FAIL -> rose
    OpsStatusDto.WIP -> cyan
    OpsStatusDto.UNKNOWN -> Color(0xFF8D98A9)
}

internal fun Double.ms(): String = if (this <= 0.0) "-" else "${roundToInt()}ms"

internal fun Long.relativeFrom(nowMs: Long): String {
    val seconds = ((nowMs - this) / 1_000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "1min"
        seconds < 3_600 -> "${seconds / 60 + 1}min"
        seconds < 86_400 -> {
            val hours = seconds / 3_600
            val minutes = (seconds % 3_600) / 60
            if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}min"
        }
        else -> {
            val days = seconds / 86_400
            val hours = (seconds % 86_400) / 3_600
            if (hours == 0L) "${days}d" else "${days}d ${hours}h"
        }
    }
}

internal fun Long.ageColor(nowMs: Long): Color {
    val seconds = ((nowMs - this) / 1_000).coerceAtLeast(0)
    return when {
        seconds < 15 * 60 -> cyan
        seconds < 2 * 3_600 -> green
        seconds < 24 * 3_600 -> Color(0xFF8D98A9)
        else -> Color(0xFF657181)
    }
}

internal fun Modifier.surfaceDepth(shape: RoundedCornerShape, accent: Color, glowAlpha: Float): Modifier = this
    .dropShadow(
        shape,
        Shadow(
            radius = 34.dp,
            spread = (-7).dp,
            offset = DpOffset(0.dp, 19.dp),
            color = Color.Black,
            alpha = 0.52f,
        ),
    )
    .dropShadow(
        shape,
        Shadow(
            radius = 36.dp,
            spread = (-15).dp,
            offset = DpOffset((-10).dp, (-7).dp),
            color = accent,
            alpha = glowAlpha,
        ),
    )
    .border(BorderStroke(1.dp, accent.copy(alpha = glowAlpha * 0.5f)), shape)
