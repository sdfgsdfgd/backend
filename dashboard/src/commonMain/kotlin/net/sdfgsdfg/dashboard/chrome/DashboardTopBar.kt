package net.sdfgsdfg.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import net.sdfgsdfg.dashboard.generated.resources.Res
import net.sdfgsdfg.dashboard.generated.resources.delius
import net.sdfgsdfg.dashboard.generated.resources.fraunces
import net.sdfgsdfg.dashboard.generated.resources.geist
import net.sdfgsdfg.dashboard.generated.resources.wallpaper_winter_river
import net.sdfgsdfg.data.model.OpsViewerDto
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun OpsWallpaper() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF02060B)),
    ) {
        Image(
            painter = painterResource(Res.drawable.wallpaper_winter_river),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.20f)))
    }
}

@Stable
internal class LiquidTabMotionState(initial: DashboardTab) {
    var target by mutableStateOf(initial)
        private set
    internal val liquid = LiquidNavigationState(
        initialCenter = 0.dp,
        initialWidth = 78.dp,
        initialWarmth = liquidWarmth(initial),
    )
    internal val xExpansion = Animatable(if (initial == DashboardTab.Arcana) 1f else 0f)
    internal var ready by mutableStateOf(false)
        private set
    private var anchors: Map<DashboardTab, LiquidTabAnchor> = emptyMap()

    val phase get() = liquid.phase
    suspend fun bindAnchors(next: Map<DashboardTab, LiquidTabAnchor>, current: DashboardTab) {
        val changed = anchors != next
        anchors = next
        val anchor = anchors[current] ?: return
        if (!ready) {
            target = current
            liquid.settleAt(anchor.center, anchor.width, liquidWarmth(current))
            ready = true
        } else if (changed && phase >= 0.999f && current == target) {
            liquid.settleAt(anchor.center, anchor.width, liquidWarmth(current))
        }
    }

    suspend fun moveTo(next: DashboardTab) {
        val anchor = anchors[next] ?: return
        target = next
        liquid.moveTo(anchor.center, anchor.width, liquidWarmth(next))
    }

    suspend fun setXExpanded(expanded: Boolean, immediate: Boolean = false) {
        val value = if (expanded) 1f else 0f
        if (immediate) {
            xExpansion.stop()
            xExpansion.snapTo(value)
        } else {
            xExpansion.animateTo(
                targetValue = value,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 240f),
            )
        }
    }

    suspend fun forceSnap(next: DashboardTab) {
        val anchor = anchors[next]
        target = next
        setXExpanded(next == DashboardTab.Arcana, immediate = true)
        if (anchor != null) {
            liquid.settleAt(anchor.center, anchor.width, liquidWarmth(next))
            ready = true
        }
    }

    fun distanceTo(next: DashboardTab): Dp {
        val center = anchors[next]?.center ?: return 0.dp
        return abs(liquid.headCenter.value - center.value).dp
    }

}

@Composable
internal fun rememberLiquidTabMotionState(initial: DashboardTab) =
    remember { LiquidTabMotionState(initial) }

@Composable
internal fun Header(
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
    socketState: OpsSocketState,
    viewer: OpsViewerDto,
    onViewerChanged: () -> Unit,
    tabMotion: LiquidTabMotionState,
    tabs: List<DashboardTab> = DashboardTab.entries,
    xContext: (@Composable RowScope.() -> Unit)? = null,
) {
    val shellShape = RoundedCornerShape(32.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        val full = maxWidth >= 1_430.dp
        val compact = maxWidth < 1_260.dp
        val expandedContextWidth = when {
            full -> 660.dp
            !compact -> 548.dp
            else -> 424.dp
        }
        val xExpansion = tabMotion.xExpansion.value.coerceIn(0f, 1f)
        val contextWidth = 74.dp + (expandedContextWidth - 74.dp) * xExpansion
        Box(
            Modifier
                .matchParentSize()
                .dropShadow(
                    shellShape,
                    Shadow(
                        radius = 26.dp,
                        offset = DpOffset(0.dp, 12.dp),
                        color = Color.Black,
                        alpha = 0.52f,
                    ),
                )
                .dropShadow(
                    shellShape,
                    Shadow(
                        radius = 8.dp,
                        offset = DpOffset(0.dp, 1.dp),
                        color = Color(0xFF63B9EF),
                        alpha = 0.12f,
                    ),
                )
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xB30B1723),
                        0.26f to Color(0xAE080E17),
                        0.66f to Color(0xB0080B12),
                        1f to Color(0xB31B0812),
                    ),
                    shellShape,
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.085f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.22f),
                        ),
                    ),
                    shellShape,
                )
                .innerShadow(
                    shellShape,
                    Shadow(
                        radius = 8.dp,
                        offset = DpOffset((-1).dp, (-2).dp),
                        color = Color(0xFFD4EEFF),
                        alpha = 0.17f,
                    ),
                )
                .innerShadow(
                    shellShape,
                    Shadow(
                        radius = 18.dp,
                        offset = DpOffset(2.dp, 7.dp),
                        color = Color.Black,
                        alpha = 0.46f,
                    ),
                )
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            0f to Color(0xFF78CFFF).copy(alpha = 0.55f),
                            0.28f to Color.White.copy(alpha = 0.14f),
                            0.72f to Color.White.copy(alpha = 0.08f),
                            1f to Color(0xFFFF274E).copy(alpha = 0.46f),
                        ),
                    ),
                    shellShape,
                )
                .clip(shellShape),
        ) {
            ToolbarTexture(Modifier.matchParentSize())
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiquidTabCluster(
                    tabs = tabs,
                    selectedTab = selectedTab,
                    compact = compact,
                    contextWidth = contextWidth,
                    contextExpansion = xExpansion,
                    tabMotion = tabMotion,
                    onTabSelected = onTabSelected,
                    xContext = xContext,
                )
                Spacer(Modifier.weight(1f))
                OpsHeaderControls(
                    socketState = socketState,
                    viewer = viewer,
                    onViewerChanged = onViewerChanged,
                    compact = compact,
                )
            }
        }
    }
}

@Composable
private fun ToolbarTexture(modifier: Modifier = Modifier) {
    Canvas(modifier.alpha(0.86f)) {
        drawCircle(
            color = Color(0xFF164B7C).copy(alpha = 0.18f),
            radius = size.width * 0.26f,
            center = Offset(size.width * 0.12f, size.height * 1.80f),
        )
        drawCircle(
            color = Color(0xFF6C0719).copy(alpha = 0.18f),
            radius = size.width * 0.24f,
            center = Offset(size.width * 0.88f, size.height * 2.15f),
        )
        var radius = size.height * 1.65f
        while (radius < size.width * 0.36f) {
            drawCircle(
                color = Color(0xFF4F83B6).copy(alpha = 0.018f),
                radius = radius,
                center = Offset(size.width * 0.11f, size.height * 2.35f),
                style = Stroke(width = 0.8f),
            )
            radius += size.height * 0.70f
        }
        var meridian = -size.width * 0.08f
        while (meridian < size.width * 0.58f) {
            drawLine(
                color = Color(0xFF6EA2D2).copy(alpha = 0.026f),
                start = Offset(meridian, size.height),
                end = Offset(meridian + size.height * 2.9f, 0f),
                strokeWidth = 0.8f,
            )
            meridian += 86f
        }
        var fault = size.width * 0.64f
        while (fault < size.width) {
            drawLine(
                color = Color(0xFFC51F3C).copy(alpha = 0.024f),
                start = Offset(fault, 0f),
                end = Offset(fault + size.height * 2.5f, size.height),
                strokeWidth = 0.8f,
            )
            fault += 112f
        }
        drawLine(
            color = Color(0xFF6ECFFF).copy(alpha = 0.14f),
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width * 0.50f, size.height - 1f),
            strokeWidth = 1f,
        )
        drawLine(
            color = Color(0xFFA9142C).copy(alpha = 0.17f),
            start = Offset(size.width * 0.66f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1f,
        )
        var dot = 0
        while (dot < 34) {
            val px = ((dot * 233) % 1900).toFloat() / 1900f * size.width
            val py = 8f + (((dot * 89) % 68).toFloat() / 68f * (size.height - 16f)).coerceAtLeast(0f)
            drawCircle(
                color = (if (dot % 7 == 0) rose else cyan).copy(alpha = if (dot % 7 == 0) 0.075f else 0.055f),
                radius = if (dot % 7 == 0) 1.2f else 0.8f,
                center = Offset(px, py),
            )
            dot += 1
        }
    }
}

internal data class LiquidTabAnchor(val left: Dp, val width: Dp) {
    val center get() = left + width / 2f
}

private fun smoothStep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

@Composable
private fun LiquidTabCluster(
    tabs: List<DashboardTab>,
    selectedTab: DashboardTab,
    compact: Boolean,
    contextWidth: Dp,
    contextExpansion: Float,
    tabMotion: LiquidTabMotionState,
    onTabSelected: (DashboardTab) -> Unit,
    xContext: (@Composable RowScope.() -> Unit)?,
) {
    val navigationTabs = tabs.filterNot { it == DashboardTab.Arcana }
    val navigationGap = if (compact) 2.dp else 4.dp
    val anchors = linkedMapOf<DashboardTab, LiquidTabAnchor>()
    var cursor = 0.dp
    navigationTabs.forEachIndexed { index, tab ->
        val width = tab.navWidth(compact)
        anchors[tab] = LiquidTabAnchor(cursor, width)
        cursor += width
        if (index < navigationTabs.lastIndex) cursor += navigationGap
    }
    val hasX = DashboardTab.Arcana in tabs
    val xGap = if (hasX) 8.dp else 0.dp
    if (hasX) anchors[DashboardTab.Arcana] = LiquidTabAnchor(cursor + xGap, 78.dp)
    val clusterWidth = cursor + xGap + if (hasX) contextWidth else 0.dp
    LaunchedEffect(anchors, selectedTab) {
        tabMotion.bindAnchors(anchors, selectedTab)
    }

    Box(Modifier.width(clusterWidth).height(54.dp)) {
        if (hasX) {
            XContextSurface(
                expansion = contextExpansion,
                modifier = Modifier
                    .offset(x = anchors.getValue(DashboardTab.Arcana).left)
                    .width(contextWidth),
                content = xContext,
            )
        }
        if (tabMotion.ready) {
            LiquidNavigationLayer(
                state = tabMotion.liquid,
                modifier = Modifier.matchParentSize(),
                restHeight = 54.dp,
            )
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabSwitcher(
                tabs = navigationTabs,
                selectedTab = selectedTab,
                compact = compact,
                onTabSelected = onTabSelected,
                modifier = Modifier.height(54.dp),
            )
            if (hasX) {
                Spacer(Modifier.width(xGap))
                Box(Modifier.width(contextWidth).height(54.dp)) {
                    XPortalTarget(
                        selected = selectedTab == DashboardTab.Arcana,
                        onClick = { onTabSelected(DashboardTab.Arcana) },
                        modifier = Modifier.width(78.dp).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabSwitcher(
    tabs: List<DashboardTab>,
    selectedTab: DashboardTab,
    compact: Boolean,
    onTabSelected: (DashboardTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TabLabel(tab, selected = tab == selectedTab, width = tab.navWidth(compact)) { onTabSelected(tab) }
        }
    }
}

@Composable
private fun TabLabel(tab: DashboardTab, selected: Boolean, width: Dp, onClick: () -> Unit) {
    val font = adminPillFontFamily()
    val shape = RoundedCornerShape(999.dp)
    val color by animateColorAsState(
        targetValue = if (selected) Color(0xFFEAF7FF) else Color(0xFF8793A4),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tab-label-color",
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(48.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(tab.label, color = color, fontSize = 14.sp, fontFamily = font, fontWeight = FontWeight.Normal, maxLines = 1)
    }
}

private fun DashboardTab.navWidth(compact: Boolean): Dp = when (this) {
    DashboardTab.Home -> if (compact) 66.dp else 80.dp
    DashboardTab.Ci -> if (compact) 90.dp else 112.dp
    DashboardTab.Issues,
    -> if (compact) 68.dp else 84.dp
    DashboardTab.Arcana -> 54.dp
}

internal fun DashboardTab.shift(delta: Int, tabs: List<DashboardTab> = DashboardTab.entries): DashboardTab {
    if (tabs.isEmpty()) return this
    val next = (tabs.indexOf(this) + delta).mod(tabs.size)
    return tabs[next]
}

private fun liquidWarmth(tab: DashboardTab): Float =
    if (tab == DashboardTab.Arcana) 1f else 0f

private object LiquidNeckShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Generic(Path())
        val shoulder = w * 0.225f
        val neck = w - shoulder
        val capControl = shoulder * (1f - 0.5522848f)
        return Outline.Generic(
            Path().apply {
                moveTo(shoulder, 0f)
                cubicTo(shoulder + neck * 0.20f, 0f, shoulder + neck * 0.24f, h * 0.305556f, shoulder + neck * 0.50f, h * 0.361111f)
                cubicTo(shoulder + neck * 0.76f, h * 0.305556f, shoulder + neck * 0.80f, h * 0.111111f, w, h * 0.111111f)
                lineTo(w, h * 0.888889f)
                cubicTo(shoulder + neck * 0.80f, h * 0.888889f, shoulder + neck * 0.76f, h * 0.694444f, shoulder + neck * 0.50f, h * 0.638889f)
                cubicTo(shoulder + neck * 0.24f, h * 0.694444f, shoulder + neck * 0.20f, h, shoulder, h)
                cubicTo(capControl, h, 0f, h * 0.776142f, 0f, h * 0.50f)
                cubicTo(0f, h * 0.223858f, capControl, 0f, shoulder, 0f)
                close()
            },
        )
    }
}

@Composable
private fun XContextSurface(
    expansion: Float,
    modifier: Modifier = Modifier,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val expanded = expansion.coerceIn(0f, 1f)
    if (expanded <= 0.001f || content == null) return
    val neckProgress = smoothStep(expanded)

    val capsuleShape = RoundedCornerShape(999.dp)
    val steelRim = Brush.verticalGradient(
        0f to Color(0xFFDCF4FF).copy(alpha = 0.50f),
        0.42f to Color(0xFF83AABD).copy(alpha = 0.12f),
        1f to Color(0xFF5F7A89).copy(alpha = 0.24f),
    )
    val liquidRim = Brush.verticalGradient(
        0f to Color(0xFFFFE5A6).copy(alpha = 0.58f),
        0.34f to Color(0xFFC6DDE7).copy(alpha = 0.12f),
        0.66f to Color(0xFF7895A3).copy(alpha = 0.08f),
        1f to Color(0xFFD8A24A).copy(alpha = 0.40f),
    )
    val reservoirFeather = remember {
        Brush.horizontalGradient(
            0f to Color.Black,
            0.05625f to Color.Black.copy(alpha = 0.8965f),
            0.1125f to Color.Black.copy(alpha = 0.50f),
            0.16875f to Color.Black.copy(alpha = 0.1035f),
            0.225f to Color.Transparent,
            1f to Color.Transparent,
        )
    }

    Box(modifier = modifier.height(54.dp)) {
        Box(
            modifier = Modifier
                .offset(x = 60.dp - 18.dp * neckProgress)
                .width(80.dp * neckProgress)
                .height(54.dp)
                .alpha(expanded)
                .dropShadow(
                    LiquidNeckShape,
                    Shadow(radius = 14.dp, offset = DpOffset(0.dp, 7.dp), color = Color.Black, alpha = 0.50f),
                )
                .dropShadow(
                    LiquidNeckShape,
                    Shadow(radius = 7.dp, offset = DpOffset(0.dp, 0.dp), color = Color(0xFFFFC857), alpha = 0.17f),
                )
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(brush = reservoirFeather, blendMode = BlendMode.DstOut)
                }
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xD38A642D),
                        0.225f to Color(0xD38A642D),
                        0.6125f to Color(0xE507111B),
                        1f to Color(0xD179592A),
                    ),
                    LiquidNeckShape,
                )
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFFFFE4A8).copy(alpha = 0.28f),
                        0.42f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Color(0xFFC18A35).copy(alpha = 0.16f),
                    ),
                    LiquidNeckShape,
                )
                .innerShadow(
                    LiquidNeckShape,
                    Shadow(radius = 6.dp, offset = DpOffset((-1).dp, (-2).dp), color = Color(0xFFFFE9B8), alpha = 0.28f),
                )
                .innerShadow(
                    LiquidNeckShape,
                    Shadow(radius = 9.dp, offset = DpOffset(1.dp, 4.dp), color = Color.Black, alpha = 0.53f),
                )
                .border(BorderStroke(1.dp, liquidRim), LiquidNeckShape)
                .clip(LiquidNeckShape),
        )

        val contextAlpha = smoothStep((expanded - 0.16f) / 0.84f)
        if (contextAlpha > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 104.dp, top = 4.dp, bottom = 4.dp)
                    .alpha(contextAlpha)
                    .dropShadow(
                        capsuleShape,
                        Shadow(radius = 14.dp, offset = DpOffset(0.dp, 7.dp), color = Color.Black, alpha = 0.50f),
                    )
                    .dropShadow(
                        capsuleShape,
                        Shadow(radius = 6.dp, offset = DpOffset(0.dp, 0.dp), color = Color(0xFF8FD1F3), alpha = 0.11f),
                    )
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xE0182A38), Color(0xE9070D15), Color(0xF0070A10)),
                        ),
                        capsuleShape,
                    )
                    .innerShadow(
                        capsuleShape,
                        Shadow(radius = 8.dp, offset = DpOffset((-1).dp, (-2).dp), color = Color(0xFFD8F0FF), alpha = 0.20f),
                    )
                    .innerShadow(
                        capsuleShape,
                        Shadow(radius = 11.dp, offset = DpOffset(1.dp, 4.dp), color = Color.Black, alpha = 0.54f),
                    )
                    .border(BorderStroke(1.dp, steelRim), capsuleShape)
                    .clip(capsuleShape),
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(3.dp)
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.075f)), capsuleShape),
                )
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun XPortalTarget(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    val color by animateColorAsState(
        targetValue = if (selected) Color(0xFFF1F7FA) else Color(0xFF8793A4),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "x-target-color",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("X", color = color, fontSize = 19.sp, fontFamily = adminPillFontFamily(), fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun OpsHeaderControls(
    socketState: OpsSocketState,
    viewer: OpsViewerDto,
    onViewerChanged: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpsConnectionBadge(socketState, compact)
        OpsGithubAuthButton(viewer, onViewerChanged, compact)
        OpsViewerBadge(viewer, compact)
    }
}

@Composable
private fun OpsConnectionBadge(state: OpsSocketState, compact: Boolean) {
    val pillFont = adminPillFontFamily()
    val tone = when {
        state.status == OpsSocketStatus.DISCONNECTED -> rose
        state.status == OpsSocketStatus.CONNECTING -> amber
        (state.latencyMs ?: 0L) > 700L -> amber
        else -> green
    }
    val label = when (state.status) {
        OpsSocketStatus.CONNECTED -> state.latencyMs?.let { "$it ms" } ?: "online"
        OpsSocketStatus.CONNECTING -> "linking"
        OpsSocketStatus.DISCONNECTED -> "offline"
    }
    OpsGlassPill(
        tone = tone,
        modifier = Modifier.widthIn(min = if (compact) 62.dp else 76.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tone),
        )
        Text(label, color = pillPrimaryText, fontSize = pillPrimaryTextSize, fontFamily = pillFont, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OpsGithubAuthButton(viewer: OpsViewerDto, onViewerChanged: () -> Unit, compact: Boolean) {
    val labelFont = opsPillFontFamily()
    val identityFont = githubPillFontFamily()
    val githubLogin = viewer.proofs.firstOrNull { it.startsWith("github:") }?.removePrefix("github:")
    var pending by remember { mutableStateOf<GithubAuthPending?>(null) }
    var authCompleted by remember { mutableStateOf(false) }
    var motionCompleted by remember { mutableStateOf(false) }
    val pendingAction = pending
    val tone = if (githubLogin == null) cyan else green

    LaunchedEffect(pendingAction) {
        if (pendingAction == null) return@LaunchedEffect
        delay(2_000)
        motionCompleted = true
    }
    LaunchedEffect(authCompleted, motionCompleted) {
        if (!authCompleted || !motionCompleted) return@LaunchedEffect
        pending = null
        authCompleted = false
        motionCompleted = false
        onViewerChanged()
    }

    fun beginAuth(action: GithubAuthPending) {
        if (pending != null) return
        pending = action
        authCompleted = false
        motionCompleted = false
        val complete = { authCompleted = true }
        if (action == GithubAuthPending.Login) startOpsGithubAuth(complete) else endOpsGithubAuth(complete)
    }

    OpsGlassPill(
        tone = tone,
        modifier = Modifier.widthIn(min = if (githubLogin == null) 104.dp else if (compact) 52.dp else 224.dp),
        onClick = if (pending == null) {
            { beginAuth(if (githubLogin == null) GithubAuthPending.Login else GithubAuthPending.Logout) }
        } else {
            null
        },
        contentPadding = if (githubLogin == null) {
            PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        } else if (compact) {
            PaddingValues(0.dp)
        } else {
            PaddingValues(start = 0.dp, top = 0.dp, end = 15.dp, bottom = 0.dp)
        },
        horizontalArrangement = Arrangement.Start,
    ) {
        GitHubAuthButtonContent(
            viewer = viewer,
            githubLogin = githubLogin,
            tone = tone,
            labelFont = labelFont,
            identityFont = identityFont,
            pending = pendingAction,
            compact = compact,
        )
    }
}

private enum class GithubAuthPending(val label: String) {
    Login("Connecting"),
    Logout("Signing out"),
}

@Composable
private fun GitHubAuthButtonContent(
    viewer: OpsViewerDto,
    githubLogin: String?,
    tone: Color,
    labelFont: FontFamily,
    identityFont: FontFamily,
    pending: GithubAuthPending?,
    compact: Boolean,
) {
    var lastPending by remember { mutableStateOf<GithubAuthPending?>(null) }
    LaunchedEffect(pending) {
        if (pending != null) lastPending = pending
    }
    val pendingAlpha by animateFloatAsState(if (pending == null) 0f else 1f, tween(2_000, easing = FastOutSlowInEasing), label = "github-pending-alpha")
    val normalAlpha by animateFloatAsState(if (pending == null) 1f else 0f, tween(2_000, easing = FastOutSlowInEasing), label = "github-normal-alpha")
    val pendingScale by animateFloatAsState(if (pending == null) 0.72f else 1f, tween(2_000, easing = FastOutSlowInEasing), label = "github-pending-scale")
    val normalScale by animateFloatAsState(if (pending == null) 1f else 0.86f, tween(2_000, easing = FastOutSlowInEasing), label = "github-normal-scale")
    Box(contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = Modifier.graphicsLayer {
                alpha = normalAlpha
                scaleX = normalScale
                scaleY = normalScale
            },
            horizontalArrangement = Arrangement.spacedBy(if (githubLogin == null) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (githubLogin != null) GithubAvatarPanel(viewer.avatarUrl, githubLogin, tone, compact)
            if (githubLogin == null) {
                Text("GitHub", color = pillPrimaryText, fontSize = pillPrimaryTextSize, fontFamily = identityFont, fontWeight = FontWeight.SemiBold, maxLines = 1)
            } else if (!compact) {
                Text("GitHub: ", color = pillMutedText, fontSize = pillPrimaryTextSize, fontFamily = labelFont, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(githubLogin, color = pillPrimaryText, fontSize = pillPrimaryTextSize, fontFamily = identityFont, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        if (pending != null || pendingAlpha > 0.01f) {
            Row(
                modifier = Modifier
                    .padding(start = if (githubLogin == null) 0.dp else 14.dp)
                    .graphicsLayer {
                        alpha = pendingAlpha
                        scaleX = pendingScale
                        scaleY = pendingScale
                    },
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GithubAuthSpinner(tone)
                Text(
                    lastPending?.label.orEmpty(),
                    color = pillPrimaryText,
                    fontSize = pillPrimaryTextSize,
                    fontFamily = identityFont,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun GithubAuthSpinner(tone: Color, modifier: Modifier = Modifier.size(18.dp)) {
    val transition = rememberInfiniteTransition(label = "github-auth-spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(880, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "github-auth-rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1_150, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "github-auth-pulse",
    )
    Canvas(modifier.graphicsLayer(rotationZ = rotation)) {
        val stroke = 2.2.dp.toPx()
        val dot = 2.15.dp.toPx()
        drawCircle(
            color = tone.copy(alpha = 0.08f + 0.10f * pulse),
            radius = size.minDimension * 0.43f,
            center = center,
        )
        drawArc(
            color = tone.copy(alpha = 0.95f),
            startAngle = -42f,
            sweepAngle = 246f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = Color.White.copy(alpha = 0.22f),
            startAngle = 232f,
            sweepAngle = 74f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawCircle(
            color = tone.copy(alpha = 0.82f),
            radius = dot,
            center = Offset(size.width * 0.82f, size.height * 0.22f),
        )
    }
}

@Composable
private fun OpsViewerBadge(viewer: OpsViewerDto, compact: Boolean) {
    val labelFont = opsPillFontFamily()
    val identityFont = adminPillFontFamily()
    val writable = viewer.issueWrite
    val tone = if (writable) green else amber
    val label = if (writable) viewer.displayName.ifBlank { viewer.userId.ifBlank { "Admin" } } else "Guest"
    OpsGlassPill(
        tone = tone,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tone),
        )
        Text(label, color = pillPrimaryText, fontSize = if (compact) 15.sp else 18.sp, fontFamily = identityFont, fontWeight = FontWeight.Normal, maxLines = 1)
        if (!compact) Text(if (writable) viewer.role.ifBlank { "admin" } else "read-only", color = pillMutedText, fontSize = pillMutedTextSize, fontFamily = labelFont, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private val pillPrimaryText = Color(0xCCF0FFF6)
private val pillMutedText = Color(0x66B4C1CA)
private val pillPrimaryTextSize = 12.sp
private val pillMutedTextSize = 11.sp

@Composable
private fun opsPillFontFamily() = FontFamily(
    Font(Res.font.geist, FontWeight.Medium),
    Font(Res.font.geist, FontWeight.SemiBold),
)

@Composable
private fun githubPillFontFamily() = FontFamily(
    Font(Res.font.fraunces, FontWeight.SemiBold),
)

@Composable
private fun adminPillFontFamily() = FontFamily(
    Font(Res.font.delius, FontWeight.Normal),
)

@Composable
private fun OpsGlassPill(
    tone: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(7.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    val surfaceModifier = modifier
        .dropShadow(
            shape,
            Shadow(radius = 12.dp, offset = DpOffset(0.dp, 6.dp), color = Color.Black, alpha = 0.44f),
        )
        .dropShadow(
            shape,
            Shadow(radius = 5.dp, offset = DpOffset((-1).dp, (-1).dp), color = tone, alpha = 0.12f),
        )
        .background(
            Brush.linearGradient(
                listOf(
                    Color(0xC719222D),
                    Color(0xAC080E15),
                    Color(0xC112141A),
                ),
            ),
            shape,
        )
        .innerShadow(
            shape,
            Shadow(radius = 8.dp, offset = DpOffset((-1).dp, (-2).dp), color = Color.White, alpha = 0.13f),
        )
        .innerShadow(
            shape,
            Shadow(radius = 12.dp, offset = DpOffset(1.dp, 5.dp), color = Color.Black, alpha = 0.40f),
        )
        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape)
        .border(BorderStroke(1.dp, tone.copy(alpha = 0.34f)), shape)
        .clip(shape)
        .let { if (onClick == null) it else it.clickable(onClick = onClick) }
    Box(
        modifier = surfaceModifier,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.11f),
                            Color.White.copy(alpha = 0.02f),
                            Color.Transparent,
                        )
                    )
                )
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            tone.copy(alpha = 0.05f),
                            Color.Transparent,
                            tone.copy(alpha = 0.10f),
                        )
                    )
                )
        )
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun GithubAvatarPanel(url: String?, login: String, tone: Color, compact: Boolean) {
    val bitmap = rememberRemoteImageBitmap(url)
    Box(
        modifier = Modifier
            .width(if (compact) 52.dp else 112.dp)
            .height(44.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (bitmap != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Transparent,
                                    0.30f to Color.Transparent,
                                    0.46f to Color.Black.copy(alpha = 0.60f),
                                    0.60f to Color.Black.copy(alpha = 0.88f),
                                    0.7f to Color.Black.copy(alpha = 0.88f),
                                    0.92f to Color.Black.copy(alpha = 0.98f),
                                    0.96f to Color.Black.copy(alpha = 0.99f),
                                    1.00f to Color.Black,
                                )
                            ),
                            blendMode = BlendMode.DstOut,
                        )
                    }
            ) {
                Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                tone.copy(alpha = 0.42f),
                                tone.copy(alpha = 0.16f),
                                Color(0xE0101722),
                            )
                        )
                    )
            )
            Text(
                login.firstOrNull()?.uppercase() ?: "G",
                modifier = Modifier.padding(start = 24.dp),
                color = Color.White.copy(alpha = 0.90f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
internal fun TopLoadTrace() {
    val transition = rememberInfiniteTransition(label = "ops-load")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "ops-load-trace",
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Color(0xFF101822)),
    ) {
        Box(
            modifier = Modifier
                .offset(x = (maxWidth + 140.dp) * progress - 140.dp)
                .width(140.dp)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color.Transparent, cyan, green, Color.Transparent))),
        )
    }
}
