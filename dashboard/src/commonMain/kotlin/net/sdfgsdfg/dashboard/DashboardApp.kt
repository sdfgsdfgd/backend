package net.sdfgsdfg.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsArtifactDto
import net.sdfgsdfg.data.model.IssueSummaryDto
import net.sdfgsdfg.data.model.OpsSignalDto
import net.sdfgsdfg.data.model.RepoHealthDto
import net.sdfgsdfg.data.model.SelfTestSummaryDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
import net.sdfgsdfg.dashboard.generated.resources.Res
import net.sdfgsdfg.dashboard.generated.resources.wallpaper_winter_river
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

private val background = Color(0xFF090C10)
private val panel = Color(0xFF121820)
private val panelRaised = Color(0xFF171E27)
private val border = Color(0xFF263240)
private val muted = Color(0xFF92A0B2)
private val text = Color(0xFFE8ECF2)
private val cyan = Color(0xFF75D4FF)
private val green = Color(0xFF5CE58B)
private val amber = Color(0xFFFFC86B)
private val rose = Color(0xFFFF7474)
private const val OPS_SUMMARY_REFRESH_MS = 45_000L
private const val UPDATE_FLASH_MS = 2_400L

private enum class DashboardTab(val label: String) {
    Home("Home"),
    Ci("CI Results"),
    Issues("Issues"),
    Arcana("Arcana Sessions"),
}

private sealed interface OpsLoadState {
    data object Loading : OpsLoadState
    data class Ready(val summary: OpsSummaryDto) : OpsLoadState
    data class Failed(val message: String) : OpsLoadState
}

private data class FieldSpec(val name: String, val value: String, val detail: String? = null)
private data class IssueLaneSpec(val label: String, val color: Color, val count: (RepoHealthDto) -> Int)

@Composable
fun DashboardApp(
    arrowShiftSignal: Int = 0,
    focusedArrowKeys: Boolean = true,
) {
    var selectedTab by remember { mutableStateOf(DashboardTab.Home) }
    var loadState by remember { mutableStateOf<OpsLoadState>(OpsLoadState.Loading) }
    val focusRequester = remember { FocusRequester() }
    val mounted = remember { booleanArrayOf(true) }
    var handledArrowShiftSignal by remember { mutableStateOf(arrowShiftSignal) }

    DisposableEffect(Unit) {
        onDispose { mounted[0] = false }
    }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            loadOpsSummary(
                onLoaded = { if (mounted[0]) loadState = OpsLoadState.Ready(it) },
                onFailed = { if (mounted[0]) loadState = OpsLoadState.Failed(it.ifBlank { "Failed to load ops summary" }) },
            )
            delay(OPS_SUMMARY_REFRESH_MS)
        }
    }
    LaunchedEffect(arrowShiftSignal) {
        val shift = arrowShiftSignal - handledArrowShiftSignal
        if (shift != 0) selectedTab = selectedTab.shift(shift)
        handledArrowShiftSignal = arrowShiftSignal
    }
    val surfaceModifier = Modifier
        .fillMaxSize()
        .focusRequester(focusRequester)
        .focusable()

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = cyan,
            secondary = green,
            surface = panel,
            background = background,
        ),
    ) {
        Surface(
            modifier = if (focusedArrowKeys) {
                surfaceModifier.onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (it.key) {
                        Key.DirectionLeft -> { selectedTab = selectedTab.shift(-1); true }
                        Key.DirectionRight -> { selectedTab = selectedTab.shift(1); true }
                        else -> false
                    }
                }
            } else {
                surfaceModifier
            },
            color = background,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pageBottomGutter = maxHeight * 0.28f
                OpsWallpaper()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(bottom = pageBottomGutter),
                ) {
                    item {
                        Header(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it },
                        )
                        if (loadState is OpsLoadState.Loading) {
                            TopLoadTrace()
                        }
                    }
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                        ) {
                            when (selectedTab) {
                                DashboardTab.Home -> Home(loadState)
                                DashboardTab.Ci -> CiResults(loadState)
                                DashboardTab.Issues -> Issues(loadState)
                                DashboardTab.Arcana -> WorkSurface(
                                    title = "Arcana Sessions",
                                    detail = "WIP. Ask the user what to steal from frontend-compose and frontend-next before this tab is implemented.",
                                    items = listOf("session chat", "patch review", "local artifacts", "desktop-only actions"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpsWallpaper() {
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
private fun Header(
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
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
        val compactTabs = maxWidth < 520.dp
        if (maxWidth < 900.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeaderTitle()
                TabSwitcher(selectedTab, compact = compactTabs, onTabSelected)
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                HeaderTitle(modifier = Modifier.align(Alignment.CenterStart))
                TabSwitcher(selectedTab, compact = false, onTabSelected, modifier = Modifier.align(Alignment.Center))
                HeaderRuntimeBadge(modifier = Modifier.align(Alignment.CenterEnd))
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
private fun Home(loadState: OpsLoadState) {
    when (loadState) {
        OpsLoadState.Loading -> LoadingPanel()
        is OpsLoadState.Failed -> WorkSurface(
            title = "Ops Summary Unavailable",
            detail = loadState.message,
            items = listOf("/api/ops/summary", "backend service", "local preview route"),
        )
        is OpsLoadState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryStrip(loadState.summary)
            RepoGrid(loadState.summary.repos, loadState.summary.generatedAtMs)
        }
    }
}

@Composable
private fun SummaryStrip(summary: OpsSummaryDto) {
    val ok = summary.repos.count { it.status == OpsStatusDto.OK }
    val activeIssues = summary.repos.sumOf { it.issues.active }
    val alerts = summary.repos.count { it.status in setOf(OpsStatusDto.WARN, OpsStatusDto.FAIL, OpsStatusDto.UNKNOWN) }
    val wip = summary.repos.count { it.status == OpsStatusDto.WIP }

    BoxWithConstraints {
        val vertical = maxWidth < 760.dp
        val metrics = listOf(
            FieldSpec("repos", summary.repos.size.toString()),
            FieldSpec("healthy", ok.toString()),
            FieldSpec("alerts", alerts.toString()),
            FieldSpec("wip", wip.toString()),
            FieldSpec("active issues", activeIssues.toString(), issueSourceBreakdown(summary.repos)),
        )
        if (vertical) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                metrics.forEach { MetricCard(it) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.forEach { metric ->
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(metric)
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoGrid(repos: List<RepoHealthDto>, generatedAtMs: Long) {
    BoxWithConstraints {
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repos.forEach { RepoCard(it, generatedAtMs, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repos.forEach { repo ->
                    RepoCard(repo, generatedAtMs, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RepoCard(repo: RepoHealthDto, generatedAtMs: Long, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .glassSurface(shape, repo.status.color(), glowAlpha = 0.11f, borderAlpha = 0.42f)
            .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RepoCardContent(repo, generatedAtMs)
    }
}

@Composable
private fun RunSignal(run: TestRunSummaryDto, generatedAtMs: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(run.label, color = text, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            RunTail(run, generatedAtMs)
        }
        run.detail?.let {
            Text(it, color = Color(0xFFC2CCDA), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RepoCardContent(repo: RepoHealthDto, generatedAtMs: Long) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(repo.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(repo.name, color = text, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                repo.runtimeBadges().forEach { PanelBadge(it, cyan) }
                Text(
                    repo.role,
                    modifier = Modifier.weight(1f),
                    color = muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (repo.id != "arcana") PanelBadge(repo.status.name, repo.status.color(), strong = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PanelBadge(repo.issues.badgeLabel(), repo.issues.badgeColor(), strong = repo.issues.active > 0)
                repo.serviceName?.let { PanelBadge(it, muted, modifier = Modifier.widthIn(max = 128.dp)) }
            }
        }
    }
    repo.latestRun?.let { RunSignal(it, generatedAtMs) }
    repo.signals.takeIf { it.isNotEmpty() }?.let {
        if (repo.id == "arcana") ArcanaSignalStack(it, generatedAtMs) else SignalStack(it, generatedAtMs)
    }
}

@Composable
private fun PanelBadge(label: String, color: Color, modifier: Modifier = Modifier, strong: Boolean = false) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        color.copy(alpha = if (strong) 0.20f else 0.12f),
                        Color.White.copy(alpha = 0.035f),
                    ),
                ),
            )
            .border(BorderStroke(1.dp, color.copy(alpha = if (strong) 0.52f else 0.30f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(5.dp)) {
            drawCircle(color.copy(alpha = 0.18f), radius = size.width * 0.88f)
            drawCircle(color, radius = size.width * 0.42f)
        }
        Text(
            label,
            color = if (strong) text else Color(0xFFD2DCE9),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun IssueSummaryDto.badgeLabel(): String = when (active) {
    1 -> "1 issue"
    else -> "$active issues"
}

private fun IssueSummaryDto.badgeColor(): Color = if (active == 0) green else amber

private fun RepoHealthDto.runtimeBadges(): List<String> = runtimeLabels.ifEmpty { runtimeLabel?.let(::listOf).orEmpty() }

@Composable
private fun rememberFreshKeys(keys: List<String>): Set<String> {
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
private fun RunPanel(run: TestRunSummaryDto) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0F151C))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(7.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(run.label, color = text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(run.status.name, color = run.status.color(), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        run.detail?.let {
            Text(it, color = muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        run.url?.let {
            Text(
                it,
                modifier = Modifier.clickable { openOpsUrl(it) },
                color = cyan,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SignalStack(signals: List<OpsSignalDto>, generatedAtMs: Long) {
    Column(
        modifier = Modifier.animateContentSize(animationSpec = tween(260, easing = FastOutSlowInEasing)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        signals.forEach { SignalRow(it, generatedAtMs) }
    }
}

@Composable
private fun ArcanaSignalStack(signals: List<OpsSignalDto>, generatedAtMs: Long) {
    val summary = signals.firstOrNull { it.label.startsWith("visible ") }
    val processRows = signals.filter { it != summary }
    val processKeys = processRows.map { "${it.label}-${it.meta}-${it.timestampMs}-${it.detail}" }
    val freshKeys = rememberFreshKeys(processKeys)
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.animateContentSize(animationSpec = tween(320, easing = FastOutSlowInEasing)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        summary?.let {
            SignalRow(
                signal = it,
                generatedAtMs = generatedAtMs,
                tailLabel = if (expanded) "hide" else "show",
                onClick = { expanded = !expanded },
            )
        }
        AnimatedVisibility(
            visible = expanded && processRows.isNotEmpty(),
            enter = fadeIn(tween(360, easing = FastOutSlowInEasing)) +
                expandVertically(tween(420, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                slideInVertically(tween(420, easing = FastOutSlowInEasing)) { -it / 3 },
            exit = fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                shrinkVertically(tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
                slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 4 },
        ) {
            Column(
                modifier = Modifier.animateContentSize(animationSpec = tween(260, easing = FastOutSlowInEasing)),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                processRows.forEachIndexed { index, process ->
                    val rowKey = processKeys[index]
                    key(rowKey) {
                        val visibleState = remember {
                            MutableTransitionState(false).apply { targetState = true }
                        }
                        AnimatedVisibility(
                            visibleState = visibleState,
                            enter = fadeIn(tween(320, easing = FastOutSlowInEasing)) +
                                expandVertically(tween(360, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                                slideInVertically(tween(360, easing = FastOutSlowInEasing)) { -it / 2 },
                            exit = fadeOut(tween(140, easing = FastOutSlowInEasing)) +
                                shrinkVertically(tween(190, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
                        ) {
                            ProcessSignalRow(process, generatedAtMs, fresh = rowKey in freshKeys)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalRow(
    signal: OpsSignalDto,
    generatedAtMs: Long,
    tailLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(7.dp)
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(Color.White.copy(alpha = 0.038f))
        .border(BorderStroke(1.dp, signal.status.color().copy(alpha = 0.18f)), shape)
        .let { if (onClick == null) it else it.clickable(onClick = onClick) }
        .padding(8.dp)
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FreshRail(signal.timestampMs, generatedAtMs, height = 34.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(signal.label, color = text, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                tailLabel?.let {
                    Text(it, color = cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                } ?: signal.timestampMs?.let { AgePill(it, generatedAtMs) }
            }
            signal.detail?.let {
                Text(it, color = Color(0xFFC2CCDA), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            signal.meta?.let {
                Text(it, color = muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ProcessSignalRow(signal: OpsSignalDto, generatedAtMs: Long, fresh: Boolean = false) {
    val shape = RoundedCornerShape(7.dp)
    val command = signal.detail?.takeIf { signal.label == "arcana" }?.arcanaCommandParts()
    val expandable = command != null || (signal.detail?.let { it.length > 130 || "\n" in it } == true)
    var expanded by remember(signal.label, signal.meta, signal.timestampMs, signal.detail) { mutableStateOf(false) }
    val flash by animateFloatAsState(
        targetValue = if (fresh) 1f else 0f,
        animationSpec = tween(if (fresh) 180 else 760, easing = FastOutSlowInEasing),
        label = "process-row-flash",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp)
            .clip(shape)
            .background(Color(0xFF0B1118).copy(alpha = 0.86f))
            .background(cyan.copy(alpha = flash * 0.10f))
            .border(BorderStroke(1.dp, signal.status.color().copy(alpha = 0.20f + flash * 0.30f)), shape)
            .animateContentSize(animationSpec = tween(260, easing = FastOutSlowInEasing))
            .let { if (expandable) it.clickable { expanded = !expanded } else it }
            .padding(horizontal = 9.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FreshRail(signal.timestampMs, generatedAtMs, height = if (expanded) 44.dp else 30.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(signal.label, modifier = Modifier.weight(1f), color = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(
                        visible = fresh,
                        enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.86f),
                        exit = fadeOut(tween(280, easing = FastOutSlowInEasing)),
                    ) {
                        UpdatePill(cyan)
                    }
                    signal.timestampMs?.let { AgePill(it, generatedAtMs) }
                    if (expandable) {
                        Text(if (expanded) "less" else "full", color = cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (command == null) {
                signal.detail?.let {
                    Text(
                        it,
                        color = Color(0xFFC6D1DF),
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
            } else {
                val (headline, commandKnobs) = command
                Text(headline, color = Color(0xFFD9E6F2), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val knobs = if (expanded) commandKnobs else commandKnobs.take(6)
                if (knobs.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        knobs.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                row.forEach { knob ->
                                    val color = if (" " in knob) cyan else green
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(color.copy(alpha = 0.09f))
                                            .border(BorderStroke(1.dp, color.copy(alpha = 0.22f)), RoundedCornerShape(999.dp))
                                            .padding(horizontal = 6.dp, vertical = 3.dp),
                                    ) {
                                        Text(knob, color = Color(0xFFD5E0EC), fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) + expandVertically(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
                    exit = fadeOut(tween(120, easing = FastOutSlowInEasing)) + shrinkVertically(tween(160, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
                ) {
                    Text(
                        signal.detail.orEmpty(),
                        color = Color(0xFFD4DEE9),
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            signal.meta?.let {
                Text(it, color = muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun String.arcanaCommandParts(): Pair<String, List<String>> {
    fun compact(value: String, max: Int) = value.replace(Regex("\\s+"), " ")
        .trim()
        .let { if (it.length <= max) it else it.take(max - 3).trimEnd() + "..." }

    val tokens = trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val optionIndex = tokens.indexOfFirst { it.startsWith("--") }.let { if (it < 0) tokens.size else it }
    val headline = tokens.take(optionIndex)
        .takeLast(2)
        .joinToString(" ") { it.substringAfterLast('/') }
        .ifBlank { compact(this, 80) }
    val knobs = mutableListOf<String>()
    var index = optionIndex
    while (index < tokens.size) {
        val name = tokens[index]
        if (!name.startsWith("--")) {
            index += 1
            continue
        }
        var end = index + 1
        while (end < tokens.size && !tokens[end].startsWith("--")) end += 1
        val value = tokens.subList(index + 1, end).joinToString(" ").trim('"')
        knobs += if (value.isBlank()) name else "$name ${compact(value, if (name == "--initial-query") 90 else 38)}"
        index = end
    }
    return headline to knobs
}

@Composable
private fun FieldGrid(fields: List<FieldSpec>) {
    BoxWithConstraints {
        if (maxWidth < 360.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                fields.forEach { Field(it) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                fields.forEach { field -> Box(Modifier.weight(1f)) { Field(field) } }
            }
        }
    }
}

@Composable
private fun Field(field: FieldSpec) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(field.name.uppercase(), color = Color(0xFF748195), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(field.value, color = Color(0xFFDCE4EE), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        field.detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MetricCard(metric: FieldSpec) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, Color(0xFF7BA9C8), glowAlpha = 0.06f, borderAlpha = 0.30f)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(metric.name.uppercase(), color = muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(metric.value, color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        metric.detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LoadingPanel() {
    val transition = rememberInfiniteTransition(label = "loading-skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "loading-skeleton-pulse",
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LoadingHero(pulse)
        LoadingRepoGrid(pulse)
    }
}

@Composable
private fun LoadingHero(pulse: Float) {
    val shape = RoundedCornerShape(8.dp)
    Card(
        modifier = Modifier.fillMaxWidth().surfaceDepth(shape, cyan, glowAlpha = 0.08f),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = panelRaised),
        border = BorderStroke(1.dp, border),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Loading Ops Summary", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                LoadingBar(width = 0.55f, height = 10.dp, pulse = pulse)
                LoadingBar(width = 0.34f, height = 10.dp, pulse = 1f - pulse)
            }
            StatusPill("syncing", cyan)
        }
    }
}

@Composable
private fun LoadingRepoGrid(pulse: Float) {
    BoxWithConstraints {
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("backend", "server_py", "arcana").forEachIndexed { index, repo ->
                    LoadingRepoCard(repo, pulse = (pulse + index * 0.12f).coerceAtMost(1f), modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("backend", "server_py", "arcana").forEachIndexed { index, repo ->
                    LoadingRepoCard(repo, pulse = (pulse + index * 0.12f).coerceAtMost(1f), modifier = Modifier.weight(1f).heightIn(min = 270.dp))
                }
            }
        }
    }
}

@Composable
private fun LoadingRepoCard(repo: String, pulse: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Card(
        modifier = modifier.surfaceDepth(shape, cyan, glowAlpha = 0.055f),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = panelRaised),
        border = BorderStroke(1.dp, border),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(OpsStatusDto.WIP)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(repo, color = text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    LoadingBar(width = 0.58f, height = 9.dp, pulse = pulse)
                }
                StatusPill("loading", cyan)
            }
            repeat(3) { index ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LoadingBar(width = 0.22f + index * 0.08f, height = 8.dp, pulse = 1f - pulse)
                    LoadingBar(width = 0.86f - index * 0.12f, height = 10.dp, pulse = pulse)
                }
            }
        }
    }
}

@Composable
private fun LoadingBar(width: Float, height: Dp, pulse: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(width.coerceIn(0.08f, 1f))
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF1B2733).copy(alpha = 0.42f + pulse * 0.28f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.035f + pulse * 0.045f)), RoundedCornerShape(999.dp)),
    )
}

@Composable
private fun CiResults(loadState: OpsLoadState) {
    when (loadState) {
        OpsLoadState.Loading -> WorkSurface(
            title = "CI Results",
            detail = "Waiting for the ops summary before shaping the pyramid lanes.",
            items = listOf("backend-local", "server_py live selftest", "dashboard web / desktop", "arcana-smoke"),
        )
        is OpsLoadState.Failed -> WorkSurface(
            title = "CI Results Unavailable",
            detail = loadState.message,
            items = listOf("/api/ops/summary", "backend control plane", "dashboard API"),
        )
        is OpsLoadState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PyramidHeader(loadState.summary)
            PipelineGrid(loadState.summary)
            RunHistoryPanel(loadState.summary)
            ServerPySelfTestPanel(loadState.summary.repos.firstOrNull { it.id == "server_py" }?.selfTest)
        }
    }
}

@Composable
private fun Issues(loadState: OpsLoadState) {
    when (loadState) {
        OpsLoadState.Loading -> WorkSurface(
            title = "Issues",
            detail = "Waiting for local .arcana/issues.json summaries before shaping lanes.",
            items = issueLanes.map { it.label },
        )
        is OpsLoadState.Failed -> WorkSurface(
            title = "Issues Unavailable",
            detail = loadState.message,
            items = listOf("/api/ops/summary", "issue summary DTO", "repo lanes"),
        )
        is OpsLoadState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            IssuesHeader(loadState.summary)
            IssueRepoStrip(loadState.summary.repos)
            IssueBoard(loadState.summary.repos)
            WorkSurface(
                title = "Issue Detail Contract",
                detail = "The board reads repo-local Arcana issue summaries now; detail events, artifacts, diffs, and feedback attach after the issue DTO grows.",
                items = listOf(".arcana/issues.json", "events", "artifacts", "feedback"),
            )
        }
    }
}

@Composable
private fun IssueRepoStrip(repos: List<RepoHealthDto>) {
    BoxWithConstraints {
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repos.forEach { IssueRepoTile(it, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repos.forEach { repo ->
                    IssueRepoTile(repo, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun IssueRepoTile(repo: RepoHealthDto, modifier: Modifier = Modifier) {
    val active = repo.issues.active
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .glassSurface(shape, repo.status.color(), glowAlpha = 0.05f, borderAlpha = 0.28f)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(repo.name, color = text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            StatusPill(if (active == 0) "clear" else "$active active", if (active == 0) green else amber)
        }
        Text(issueSourceBreakdown(listOf(repo)).ifBlank { "Arcana 0" }, color = muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("todo ${repo.issues.todo}", color = Color(0xFF8EA0B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("wip ${repo.issues.wip}", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("blocked ${repo.issues.blocked}", color = rose, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("review ${repo.issues.review}", color = amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IssuesHeader(summary: OpsSummaryDto) {
    val active = summary.repos.sumOf { it.issues.active }
    val done = summary.repos.sumOf { it.issues.done }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, amber, glowAlpha = 0.08f, borderAlpha = 0.26f)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Issue Command Board", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Arcana local issue files and GitHub Issues are counted together, with source splits kept visible.", color = muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("$active active", if (active > 0) amber else green)
            StatusPill("$done done", muted)
        }
    }
}

@Composable
private fun IssueBoard(repos: List<RepoHealthDto>) {
    BoxWithConstraints {
        if (maxWidth < 1180.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                issueLanes.forEach { lane -> IssueLane(lane, repos, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                issueLanes.forEach { lane ->
                    IssueLane(lane, repos, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun IssueLane(lane: IssueLaneSpec, repos: List<RepoHealthDto>, modifier: Modifier = Modifier) {
    val tickets = repos.mapNotNull { repo -> lane.count(repo).takeIf { it > 0 }?.let { repo to it } }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .glassSurface(shape, lane.color, glowAlpha = 0.07f, borderAlpha = 0.26f)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(lane.label, color = lane.color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            StatusPill(tickets.sumOf { it.second }.toString(), lane.color)
        }
        if (tickets.isEmpty()) {
            EmptyLane(lane)
        } else {
            tickets.forEach { (repo, count) -> IssueTicket(lane, repo, count) }
        }
    }
}

@Composable
private fun EmptyLane(lane: IssueLaneSpec) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 82.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0B1117))
            .border(BorderStroke(1.dp, Color(0xFF1C2632)), RoundedCornerShape(7.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("No ${lane.label.lowercase()} items", color = Color(0xFF697789), fontSize = 12.sp)
    }
}

@Composable
private fun IssueTicket(lane: IssueLaneSpec, repo: RepoHealthDto, count: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(panelRaised)
            .border(BorderStroke(1.dp, lane.color.copy(alpha = 0.26f)), RoundedCornerShape(7.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(repo.name, color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(count.toString(), color = lane.color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(repo.role, color = muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        repo.note?.let {
            Text(it, color = Color(0xFFB9C5D2), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PyramidHeader(summary: OpsSummaryDto) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, cyan, glowAlpha = 0.08f, borderAlpha = 0.28f)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Big CI Pyramid", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("GitHub Actions, deploy smoke, server_py live selftest, and Arcana pytest smoke converge here.", color = muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        StatusPill("${summary.repos.size} repos", cyan)
    }
}

@Composable
private fun PipelineGrid(summary: OpsSummaryDto) {
    val repos = summary.repos
    BoxWithConstraints {
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repos.forEach { PipelineLane(it, summary.generatedAtMs, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repos.forEach { repo ->
                    PipelineLane(repo, summary.generatedAtMs, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PipelineLane(repo: RepoHealthDto, generatedAtMs: Long, modifier: Modifier = Modifier) {
    val steps = pipelineSteps(repo)
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .glassSurface(shape, repo.status.color(), glowAlpha = 0.09f, borderAlpha = 0.32f)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(repo.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(repo.name, color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(repo.role, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        steps.forEachIndexed { index, step ->
            PipelineStep(index + 1, step, generatedAtMs)
        }
        if (repo.id == "arcana") ArcanaOperatorTile(repo.latestRun, generatedAtMs)
    }
}

@Composable
private fun ArcanaOperatorTile(run: TestRunSummaryDto?, generatedAtMs: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .border(BorderStroke(1.dp, cyan.copy(alpha = 0.24f)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Operator Trigger", color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            StatusPill("manual", cyan)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniFact("source", "arcana-smoke", modifier = Modifier.weight(1f))
            MiniFact("last", run?.timestampMs?.relativeFrom(generatedAtMs) ?: "waiting", modifier = Modifier.weight(1f))
            MiniFact("duration", run?.durationMs?.ms() ?: "-", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF101821))
            .border(BorderStroke(1.dp, Color(0xFF202B38)), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label.uppercase(), color = muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PipelineStep(index: Int, step: TestRunSummaryDto, generatedAtMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .border(BorderStroke(1.dp, step.status.color().copy(alpha = 0.22f)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(step.status.color().copy(alpha = 0.16f))
                .border(BorderStroke(1.dp, step.status.color().copy(alpha = 0.36f)), RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(index.toString(), color = step.status.color(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(step.label, color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RunTail(step, generatedAtMs, fontSize = 11.sp)
            }
            step.detail?.let {
                Text(it, color = muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            step.url?.let {
                Text(
                    it,
                    modifier = Modifier.clickable { openOpsUrl(it) },
                    color = cyan,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RunHistoryPanel(summary: OpsSummaryDto) {
    val events = summary.repos
        .flatMap { repo -> repo.history.map { repo to it } }
        .sortedByDescending { it.second.timestampMs ?: 0L }
        .take(8)
    if (events.isEmpty()) return
    val eventKeys = events.map { (repo, run) -> "${repo.id}-${run.label}-${run.timestampMs}" }
    val freshKeys = rememberFreshKeys(eventKeys)

    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, green, glowAlpha = 0.06f, borderAlpha = 0.26f)
            .animateContentSize(animationSpec = tween(320, easing = FastOutSlowInEasing))
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Recent Runs", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Latest backend history, newest first.", color = muted, fontSize = 12.sp)
            }
            StatusPill("${events.size} events", green)
        }
        events.forEachIndexed { index, (repo, run) ->
            val rowKey = eventKeys[index]
            key(rowKey) {
                val visibleState = remember {
                    MutableTransitionState(false).apply { targetState = true }
                }
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = fadeIn(tween(420, easing = FastOutSlowInEasing)) +
                        scaleIn(tween(420, easing = FastOutSlowInEasing), initialScale = 0.97f, transformOrigin = TransformOrigin(0.5f, 0f)) +
                        expandVertically(tween(460, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                        slideInVertically(tween(460, easing = FastOutSlowInEasing)) { -it / 2 },
                    exit = fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                        shrinkVertically(tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
                ) {
                    RunHistoryRow(repo, run, summary.generatedAtMs, fresh = rowKey in freshKeys)
                }
            }
        }
    }
}

@Composable
private fun RunHistoryRow(repo: RepoHealthDto, run: TestRunSummaryDto, generatedAtMs: Long, fresh: Boolean = false) {
    val flash by animateFloatAsState(
        targetValue = if (fresh) 1f else 0f,
        animationSpec = tween(if (fresh) 180 else 840, easing = FastOutSlowInEasing),
        label = "run-row-flash",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .background(run.status.color().copy(alpha = flash * 0.11f))
            .border(BorderStroke(1.dp, run.status.color().copy(alpha = 0.24f + flash * 0.32f)), RoundedCornerShape(7.dp))
            .animateContentSize(animationSpec = tween(260, easing = FastOutSlowInEasing))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FreshRail(run.timestampMs, generatedAtMs)
        StatusDot(run.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${repo.name} / ${run.label}", modifier = Modifier.weight(1f), color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimatedVisibility(
                    visible = fresh,
                    enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.86f),
                    exit = fadeOut(tween(280, easing = FastOutSlowInEasing)),
                ) {
                    UpdatePill(run.status.color())
                }
                RunTail(run, generatedAtMs, run.durationMs?.ms() ?: run.status.name, fontSize = 11.sp)
            }
            run.detail?.let {
                Text(it, color = muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ServerPySelfTestPanel(selfTest: SelfTestSummaryDto?) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, selfTest?.status?.color() ?: cyan, glowAlpha = 0.07f, borderAlpha = 0.28f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("server_py Live Selftest", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Dashboard-owned renderer for selftest JSON, workflow artifacts, model matrix, Zen state, and conversation signal.", color = muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            StatusPill(selfTest?.status?.name ?: "WAITING", selfTest?.status?.color() ?: Color(0xFF8D98A9))
        }
        if (selfTest == null) {
            SelfTestWaitingPanel()
        } else {
            SelfTestStats(selfTest)
            SelfTestZenPanel(selfTest)
            SelfTestExcerpt(selfTest)
            SelfTestArtifacts(selfTest)
            SelfTestCases(selfTest)
        }
    }
}

@Composable
private fun SelfTestWaitingPanel() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .border(BorderStroke(1.dp, Color(0xFF202B38)), RoundedCornerShape(7.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Waiting For server-py-selftest.json", color = text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("The panel is wired, but no persisted live selftest artifact exists in the current local log directory.", color = muted, fontSize = 12.sp, lineHeight = 17.sp)
        BoxWithConstraints {
            val items = listOf("conversation", "latencies", "model matrix", "zen")
            if (maxWidth < 720.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { PlaceholderTile(it) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item -> Box(modifier = Modifier.weight(1f)) { PlaceholderTile(item) } }
                }
            }
        }
    }
}

@Composable
private fun SelfTestStats(selfTest: SelfTestSummaryDto) {
    val stats = listOf(
        FieldSpec("conversation", if (selfTest.ok) "pass" else "fail"),
        FieldSpec("expectation", if (selfTest.satisfiedExpectation) "met" else "missed"),
        FieldSpec("total", selfTest.latencyMs.ms()),
        FieldSpec("ask", selfTest.askLatencyMs.ms()),
        FieldSpec("audit", selfTest.auditLatencyMs.ms()),
        FieldSpec("model menu", "${selfTest.casePassCount}/${selfTest.caseCount}"),
        FieldSpec("last run", selfTest.timestampLabel ?: "unknown"),
        FieldSpec("retry", if (selfTest.retried) "yes" else "no"),
    )
    BoxWithConstraints {
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { stat ->
                            Box(modifier = Modifier.weight(1f)) { SelfTestStatTile(stat) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.forEach { stat ->
                    Box(modifier = Modifier.weight(1f)) { SelfTestStatTile(stat) }
                }
            }
        }
    }
}

@Composable
private fun SelfTestStatTile(stat: FieldSpec) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .border(BorderStroke(1.dp, Color(0xFF202B38)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stat.name.uppercase(), color = muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stat.value, color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SelfTestZenPanel(selfTest: SelfTestSummaryDto) {
    val fields = listOfNotNull(
        selfTest.zenState?.let { FieldSpec("state", it) },
        selfTest.zenSeverity?.let { FieldSpec("severity", it) },
        selfTest.zenReason?.let { FieldSpec("reason", it) },
        selfTest.zenArtifactPath?.let { FieldSpec("artifact", it) },
    )
    if (fields.isEmpty()) return

    val shape = RoundedCornerShape(7.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .surfaceDepth(shape, amber, glowAlpha = 0.045f)
            .clip(shape)
            .background(Color(0xFF11161D))
            .border(BorderStroke(1.dp, amber.copy(alpha = 0.2f)), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("zen/autofix", color = amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        BoxWithConstraints {
            if (maxWidth < 780.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fields.forEach { Field(it) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    fields.forEach { field ->
                        Box(modifier = Modifier.weight(1f)) { Field(field) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelfTestExcerpt(selfTest: SelfTestSummaryDto) {
    val message = selfTest.rawError ?: selfTest.textExcerpt.ifBlank { "No excerpt recorded." }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0B1117))
            .border(BorderStroke(1.dp, (if (selfTest.rawError == null) green else rose).copy(alpha = 0.22f)), RoundedCornerShape(7.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (selfTest.rawError == null) "text excerpt" else "raw error", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(message, color = Color(0xFFD3DCE8), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SelfTestArtifacts(selfTest: SelfTestSummaryDto) {
    if (selfTest.workflowUrl == null && selfTest.artifacts.isEmpty()) return
    BoxWithConstraints {
        val items = buildList {
            selfTest.workflowUrl?.let { add(OpsArtifactDto(name = "GitHub workflow", url = it)) }
            addAll(selfTest.artifacts)
        }
        if (maxWidth < 860.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { ArtifactTile(it) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { artifact ->
                    Box(modifier = Modifier.weight(1f)) { ArtifactTile(artifact) }
                }
            }
        }
    }
}

@Composable
private fun ArtifactTile(artifact: OpsArtifactDto) {
    val shape = RoundedCornerShape(7.dp)
    val url = artifact.url
    val tile = Modifier
        .fillMaxWidth()
        .surfaceDepth(shape, cyan, glowAlpha = 0.045f)
        .clip(shape)
        .background(Color(0xFF0D141B))
        .border(BorderStroke(1.dp, Color(0xFF202B38)), shape)
    Column(
        modifier = (url?.let { tile.clickable { openOpsUrl(it) } } ?: tile)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(artifact.name, color = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(url ?: artifact.path ?: "pending", color = if (url == null) muted else cyan, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SelfTestCases(selfTest: SelfTestSummaryDto) {
    if (selfTest.cases.isEmpty()) {
        PlaceholderTile("No model case rows recorded yet")
        return
    }
    BoxWithConstraints {
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                selfTest.cases.forEach { case ->
                    SelfTestCaseRow(case.name, case.status, case.latencyMs, case.note)
                }
            }
        } else {
            val columns = selfTest.cases.chunked((selfTest.cases.size + 1) / 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                columns.forEach { column ->
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        column.forEach { case ->
                            SelfTestCaseRow(case.name, case.status, case.latencyMs, case.note)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelfTestCaseRow(name: String, status: OpsStatusDto, latencyMs: Double, note: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .border(BorderStroke(1.dp, status.color().copy(alpha = 0.22f)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(latencyMs.ms(), color = status.color(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            note?.let {
                Text(it, color = muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WorkSurface(title: String, detail: String, items: List<String>) {
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
private fun PlaceholderTile(label: String) {
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

private fun DashboardTab.shift(delta: Int): DashboardTab {
    val tabs = DashboardTab.entries
    val next = (tabs.indexOf(this) + delta).mod(tabs.size)
    return tabs[next]
}

@Composable
private fun HeaderRuntimeBadge(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF0A1724), Color(0xFF10101A))))
            .border(BorderStroke(1.dp, cyan.copy(alpha = 0.34f)), shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(green),
        )
        Text("Compose 1.11", color = Color(0xFFDFF5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("/", color = Color(0xFF516075), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("hotRunJvm", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
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
private fun UpdatePill(color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.16f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.42f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("new", color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RunTail(run: TestRunSummaryDto, generatedAtMs: Long, label: String = run.status.name, fontSize: TextUnit = 12.sp) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        run.timestampMs?.let { AgePill(it, generatedAtMs) }
        Text(label, color = run.status.color(), fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FreshRail(timestampMs: Long?, generatedAtMs: Long, height: Dp = 42.dp) {
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
private fun AgePill(timestampMs: Long, generatedAtMs: Long) {
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
private fun StatusDot(status: OpsStatusDto) {
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

@Composable
private fun TopLoadTrace() {
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

private fun issueSourceBreakdown(repos: List<RepoHealthDto>): String = repos
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

private fun Modifier.glassSurface(
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

private fun Double.ms(): String = if (this <= 0.0) "-" else "${roundToInt()}ms"

private fun Long.relativeFrom(nowMs: Long): String {
    val seconds = ((nowMs - this) / 1_000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "<1min"
        seconds < 3_600 -> "<${seconds / 60 + 1}min"
        seconds < 86_400 -> {
            val hours = seconds / 3_600
            val minutes = (seconds % 3_600) / 60
            if (minutes == 0L) "<${hours}h" else "<${hours}h ${minutes}min"
        }
        else -> {
            val days = seconds / 86_400
            val hours = (seconds % 86_400) / 3_600
            if (hours == 0L) "<${days}d" else "<${days}d ${hours}h"
        }
    }
}

private fun Long.ageColor(nowMs: Long): Color {
    val seconds = ((nowMs - this) / 1_000).coerceAtLeast(0)
    return when {
        seconds < 15 * 60 -> cyan
        seconds < 2 * 3_600 -> green
        seconds < 24 * 3_600 -> Color(0xFF8D98A9)
        else -> Color(0xFF657181)
    }
}

private fun Modifier.surfaceDepth(shape: RoundedCornerShape, accent: Color, glowAlpha: Float): Modifier = this
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

private val issueLanes = listOf(
    IssueLaneSpec("TODO", Color(0xFF8EA0B8)) { it.issues.todo },
    IssueLaneSpec("WIP", cyan) { it.issues.wip },
    IssueLaneSpec("BLOCKED", rose) { it.issues.blocked },
    IssueLaneSpec("REVIEW", amber) { it.issues.review },
    IssueLaneSpec("DONE", green) { it.issues.done },
)

private fun pipelineSteps(repo: RepoHealthDto): List<TestRunSummaryDto> = repo.runs.ifEmpty { fallbackPipelineSteps(repo) }

private fun fallbackPipelineSteps(repo: RepoHealthDto): List<TestRunSummaryDto> = when (repo.id) {
    "backend" -> listOf(
        repo.latestRun ?: TestRunSummaryDto("deploy smoke", repo.status, detail = "Latest backend health probes."),
        TestRunSummaryDto("server checks", OpsStatusDto.OK, detail = "Gradle server checks passed."),
        TestRunSummaryDto("public ingress", OpsStatusDto.WIP, detail = "External probe stays outside restart gating."),
    )
    "server_py" -> listOfNotNull(
        repo.latestRun ?: TestRunSummaryDto("live selftest", repo.status, detail = "Waiting for persisted server-py-selftest.json."),
        TestRunSummaryDto("selftest artifact", OpsStatusDto.OK, detail = "Dashboard renders the latest selftest JSON and workflow link."),
        TestRunSummaryDto("transport", OpsStatusDto.WIP, detail = "Backend gRPC channel reachability."),
    )
    "arcana" -> listOf(
        repo.latestRun ?: TestRunSummaryDto("arcana-smoke", OpsStatusDto.WIP, detail = "Run q Arcana pytest and publish only summary JSON."),
        TestRunSummaryDto("pytest unit spine", OpsStatusDto.WIP, detail = "Waiting for q arcana-smoke result."),
        TestRunSummaryDto("RSI sessions", OpsStatusDto.WIP, detail = "Deferred until issue and CI surfaces can receive output."),
    )
    else -> listOf(repo.latestRun ?: TestRunSummaryDto("latest run", repo.status, detail = repo.note))
}
