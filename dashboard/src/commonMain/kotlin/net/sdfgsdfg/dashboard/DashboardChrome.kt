package net.sdfgsdfg.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.dashboard.generated.resources.Res
import net.sdfgsdfg.dashboard.generated.resources.wallpaper_winter_river
import net.sdfgsdfg.data.model.OpsViewerDto
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

@Composable
internal fun Header(
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
    socketState: OpsSocketState,
    viewer: OpsViewerDto,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .background(Color(0xF8050A11))
            .background(Brush.linearGradient(listOf(Color(0x99112844), Color(0x5A060A10), Color(0x78160613))))
            .border(BorderStroke(1.dp, Color(0x283A5876)))
            .padding(horizontal = 18.dp, vertical = 13.dp),
    ) {
        Image(
            painter = painterResource(Res.drawable.wallpaper_winter_river),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().alpha(0.24f),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.linearGradient(listOf(Color(0xEE06111E), Color(0xE904080D), Color(0xD8170710)))),
        )
        ToolbarTexture(Modifier.matchParentSize())
        val stackedHeader = maxWidth < 660.dp
        if (maxWidth < 900.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeaderTitle()
                if (stackedHeader) {
                    TabSwitcher(selectedTab, compact = true, onTabSelected)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OpsConnectionBadge(socketState)
                        OpsViewerBadge(viewer)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TabSwitcher(selectedTab, compact = false, onTabSelected)
                        OpsConnectionBadge(socketState)
                        OpsViewerBadge(viewer)
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                HeaderTitle(modifier = Modifier.align(Alignment.CenterStart))
                TabSwitcher(selectedTab, compact = false, onTabSelected, modifier = Modifier.align(Alignment.Center))
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpsConnectionBadge(socketState)
                    OpsViewerBadge(viewer)
                }
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

@Composable
private fun HeaderTitle(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderGlyph()
        Column {
            Text("Trio Ops Cockpit", color = text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text("backend / server_py / arcana", color = Color(0xFFB5C1D0), fontSize = 13.sp)
        }
    }
}

@Composable
private fun HeaderGlyph() {
    Canvas(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Brush.linearGradient(listOf(cyan.copy(alpha = 0.20f), Color(0xFF0A1320), rose.copy(alpha = 0.15f))))
            .border(BorderStroke(1.dp, cyan.copy(alpha = 0.32f)), RoundedCornerShape(11.dp)),
    ) {
        val a = Offset(size.width * 0.34f, size.height * 0.34f)
        val b = Offset(size.width * 0.66f, size.height * 0.40f)
        val c = Offset(size.width * 0.50f, size.height * 0.68f)
        drawLine(cyan.copy(alpha = 0.35f), a, b, strokeWidth = 1.1f)
        drawLine(cyan.copy(alpha = 0.28f), b, c, strokeWidth = 1.1f)
        drawLine(rose.copy(alpha = 0.22f), c, a, strokeWidth = 1.1f)
        listOf(a, b, c).forEachIndexed { index, point ->
            drawCircle(
                color = if (index == 2) green else cyan,
                radius = 3.2f,
                center = point,
            )
            drawCircle(
                color = (if (index == 2) green else cyan).copy(alpha = 0.18f),
                radius = 6.4f,
                center = point,
            )
        }
    }
}

@Composable
private fun TabSwitcher(
    selectedTab: DashboardTab,
    compact: Boolean,
    onTabSelected: (DashboardTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = DashboardTab.entries
    val gap = 4.dp
    val shape = RoundedCornerShape(999.dp)
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
    val widths = tabs.map { it.navWidth(compact) }
    val selectedOffset by animateDpAsState(
        targetValue = widths.take(selectedIndex).fold(0.dp) { acc, width -> acc + width + gap },
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "tab-switcher-offset",
    )
    val selectedWidth by animateDpAsState(
        targetValue = widths[selectedIndex],
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "tab-switcher-width",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0x84070C13))
            .border(BorderStroke(1.dp, Color(0x33466685)), shape)
            .padding(5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = selectedOffset)
                .width(selectedWidth)
                .height(42.dp)
                .glassSurface(shape, cyan, glowAlpha = 0.20f, borderAlpha = 0.56f),
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawLine(
                    color = Color.White.copy(alpha = 0.18f),
                    start = Offset(size.width * 0.18f, 1.2f),
                    end = Offset(size.width * 0.82f, 1.2f),
                    strokeWidth = 1.0f,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, tab ->
                TabLabel(tab, selected = index == selectedIndex, width = widths[index]) { onTabSelected(tab) }
            }
        }
    }
}

@Composable
private fun TabLabel(tab: DashboardTab, selected: Boolean, width: Dp, onClick: () -> Unit) {
    val color by animateColorAsState(
        targetValue = if (selected) Color(0xFFEAF7FF) else Color(0xFF8793A4),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tab-label-color",
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(42.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(tab.label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun DashboardTab.navWidth(compact: Boolean): Dp = when (this) {
    DashboardTab.Home -> if (compact) 70.dp else 80.dp
    DashboardTab.Ci -> if (compact) 96.dp else 110.dp
    DashboardTab.Issues -> if (compact) 78.dp else 90.dp
    DashboardTab.Arcana -> if (compact) 130.dp else 154.dp
}

internal fun DashboardTab.shift(delta: Int): DashboardTab {
    val tabs = DashboardTab.entries
    val next = (tabs.indexOf(this) + delta).mod(tabs.size)
    return tabs[next]
}

@Composable
private fun OpsConnectionBadge(state: OpsSocketState) {
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
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xC4081015))
            .border(BorderStroke(1.dp, tone.copy(alpha = 0.40f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .widthIn(min = 72.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tone),
        )
        Text(label, color = tone, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OpsViewerBadge(viewer: OpsViewerDto) {
    val writable = viewer.issueWrite
    val tone = if (writable) green else amber
    // TODO(auth): make the read-only "Login" badge open GitHub OAuth, mirroring
    // the frontend-nextjs GitHub login flow, then feed the resolved identity back
    // into OpsViewerDto so the badge can show the authenticated viewer and issue
    // write capability can come from the server instead of owner-network probing.
    val label = if (writable) viewer.displayName.ifBlank { "kaan" } else "Login"
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xC4081015))
            .border(BorderStroke(1.dp, tone.copy(alpha = 0.40f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tone),
        )
        Text(label, color = tone, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(if (writable) "admin" else "read-only", color = Color(0xFF8FA1B5), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
