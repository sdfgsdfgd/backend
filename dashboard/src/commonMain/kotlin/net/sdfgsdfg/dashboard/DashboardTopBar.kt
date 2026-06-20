package net.sdfgsdfg.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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

@Composable
internal fun Header(
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
    socketState: OpsSocketState,
    viewer: OpsViewerDto,
    onViewerChanged: () -> Unit,
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
        val headerWidth = maxWidth
        val stackedHeader = headerWidth < 760.dp
        if (headerWidth < 1120.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeaderTitle()
                if (stackedHeader) {
                    TabSwitcher(selectedTab, compact = true, onTabSelected)
                    OpsHeaderControls(socketState, viewer, onViewerChanged)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TabSwitcher(selectedTab, compact = false, onTabSelected)
                        OpsHeaderControls(socketState, viewer, onViewerChanged)
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                HeaderTitle(modifier = Modifier.align(Alignment.CenterStart))
                TabSwitcher(selectedTab, compact = false, onTabSelected, modifier = Modifier.align(Alignment.Center))
                val controlsWidth = ((headerWidth - tabSwitcherWidth(compact = false)) / 2f).coerceAtLeast(0.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(controlsWidth),
                    contentAlignment = Alignment.Center,
                ) {
                    OpsHeaderControls(socketState, viewer, onViewerChanged)
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
    val titleFont = githubPillFontFamily()
    val subtitleFont = adminPillFontFamily()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderGlyph()
        Column {
            Text("Trio Ops Cockpit", color = text, fontSize = 26.sp, fontFamily = titleFont, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("backend / server_py / arcana", color = Color(0xCCB5C1D0), fontSize = 13.sp, fontFamily = subtitleFont, fontWeight = FontWeight.Normal)
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
    val font = adminPillFontFamily()
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
        Text(tab.label, color = color, fontSize = 13.sp, fontFamily = font, fontWeight = FontWeight.Normal, maxLines = 1)
    }
}

private fun DashboardTab.navWidth(compact: Boolean): Dp = when (this) {
    DashboardTab.Home -> if (compact) 70.dp else 80.dp
    DashboardTab.Ci -> if (compact) 96.dp else 110.dp
    DashboardTab.Issues -> if (compact) 78.dp else 90.dp
    DashboardTab.Arcana -> if (compact) 130.dp else 154.dp
}

private fun tabSwitcherWidth(compact: Boolean): Dp {
    val tabs = DashboardTab.entries
    return tabs.fold(0.dp) { total, tab -> total + tab.navWidth(compact) } + 4.dp * (tabs.size - 1) + 10.dp
}

internal fun DashboardTab.shift(delta: Int): DashboardTab {
    val tabs = DashboardTab.entries
    val next = (tabs.indexOf(this) + delta).mod(tabs.size)
    return tabs[next]
}

@Composable
private fun OpsHeaderControls(
    socketState: OpsSocketState,
    viewer: OpsViewerDto,
    onViewerChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpsConnectionBadge(socketState)
        OpsGithubAuthButton(viewer, onViewerChanged)
        OpsViewerBadge(viewer)
    }
}

@Composable
private fun OpsConnectionBadge(state: OpsSocketState) {
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
        modifier = Modifier.widthIn(min = 76.dp),
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
private fun OpsGithubAuthButton(viewer: OpsViewerDto, onViewerChanged: () -> Unit) {
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
        modifier = Modifier.widthIn(min = if (githubLogin == null) 104.dp else 224.dp),
        onClick = if (pending == null) {
            { beginAuth(if (githubLogin == null) GithubAuthPending.Login else GithubAuthPending.Logout) }
        } else {
            null
        },
        contentPadding = if (githubLogin == null) {
            PaddingValues(horizontal = 12.dp, vertical = 8.dp)
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
            if (githubLogin != null) GithubAvatarPanel(viewer.avatarUrl, githubLogin, tone)
            if (githubLogin == null) {
                Text("GitHub", color = pillPrimaryText, fontSize = pillPrimaryTextSize, fontFamily = identityFont, fontWeight = FontWeight.SemiBold, maxLines = 1)
            } else {
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
private fun OpsViewerBadge(viewer: OpsViewerDto) {
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
        Text(label, color = pillPrimaryText, fontSize = pillPrimaryTextSize, fontFamily = identityFont, fontWeight = FontWeight.Normal, maxLines = 1)
        Text(if (writable) viewer.role.ifBlank { "admin" } else "read-only", color = pillMutedText, fontSize = pillMutedTextSize, fontFamily = labelFont, fontWeight = FontWeight.Medium, maxLines = 1)
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
    val clickableModifier = modifier.clip(shape).let { if (onClick == null) it else it.clickable(onClick = onClick) }
    Box(
        modifier = clickableModifier
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xF0101722),
                        Color(0xD8071018),
                        Color(0xED0B1018),
                    )
                )
            )
            .border(BorderStroke(1.dp, tone.copy(alpha = 0.44f)), shape),
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
private fun GithubAvatarPanel(url: String?, login: String, tone: Color) {
    val bitmap = rememberRemoteImageBitmap(url)
    Box(
        modifier = Modifier
            .width(112.dp)
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
