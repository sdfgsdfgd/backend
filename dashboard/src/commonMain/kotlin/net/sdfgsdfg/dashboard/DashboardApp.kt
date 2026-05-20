package net.sdfgsdfg.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsArtifactDto
import net.sdfgsdfg.data.model.RepoHealthDto
import net.sdfgsdfg.data.model.SelfTestSummaryDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
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

private data class FieldSpec(val name: String, val value: String)
private data class IssueLaneSpec(val label: String, val color: Color, val count: (RepoHealthDto) -> Int)

@Composable
fun DashboardApp() {
    var selectedTab by remember { mutableStateOf(DashboardTab.Home) }
    var loadState by remember { mutableStateOf<OpsLoadState>(OpsLoadState.Loading) }

    DisposableEffect(Unit) {
        var active = true
        loadOpsSummary(
            onLoaded = { if (active) loadState = OpsLoadState.Ready(it) },
            onFailed = { if (active) loadState = OpsLoadState.Failed(it.ifBlank { "Failed to load ops summary" }) },
        )
        onDispose { active = false }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = cyan,
            secondary = green,
            surface = panel,
            background = background,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF0A1117), background))),
            ) {
                Header(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
                if (loadState is OpsLoadState.Loading) {
                    TopLoadTrace()
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 1480.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
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

@Composable
private fun Header(selectedTab: DashboardTab, onTabSelected: (DashboardTab) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC0A0F14))
            .border(BorderStroke(1.dp, Color(0x181FFFFFF)))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        if (maxWidth < 900.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                HeaderTitle()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashboardTab.entries.forEach { tab ->
                        TabChip(tab.label, selected = selectedTab == tab) { onTabSelected(tab) }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderTitle(modifier = Modifier.weight(0.85f))
                Row(
                    modifier = Modifier.weight(1.45f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DashboardTab.entries.forEach { tab ->
                        TabChip(tab.label, selected = selectedTab == tab) { onTabSelected(tab) }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                StatusPill("Compose 1.11 / hotRunJvm", cyan)
            }
        }
    }
}

@Composable
private fun HeaderTitle(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Trio Ops Cockpit", color = text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("backend / server_py / arcana", color = muted, fontSize = 13.sp)
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
        is OpsLoadState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SummaryStrip(loadState.summary)
            RepoGrid(loadState.summary.repos)
            HomeSignalGrid(loadState.summary)
        }
    }
}

@Composable
private fun SummaryStrip(summary: OpsSummaryDto) {
    val ok = summary.repos.count { it.status == OpsStatusDto.OK }
    val activeIssues = summary.repos.sumOf { it.issues.active }
    val attention = summary.repos.count { it.status in setOf(OpsStatusDto.WARN, OpsStatusDto.FAIL, OpsStatusDto.UNKNOWN) }
    val wip = summary.repos.count { it.status == OpsStatusDto.WIP }

    BoxWithConstraints {
        val vertical = maxWidth < 760.dp
        val metrics = listOf(
            FieldSpec("repos", summary.repos.size.toString()),
            FieldSpec("healthy", ok.toString()),
            FieldSpec("attention", attention.toString()),
            FieldSpec("wip", wip.toString()),
            FieldSpec("active issues", activeIssues.toString()),
        )
        if (vertical) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.forEach { MetricCard(it) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
private fun RepoGrid(repos: List<RepoHealthDto>) {
    BoxWithConstraints {
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repos.forEach { RepoCard(it, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repos.forEach { repo ->
                    RepoCard(repo, modifier = Modifier.weight(1f).heightIn(min = 330.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeSignalGrid(summary: OpsSummaryDto) {
    BoxWithConstraints {
        if (maxWidth < 1040.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SignalStack(summary.repos)
                WorkSurface(
                    title = "Action Boundary",
                    detail = "Public web stays typed and request-only; desktop becomes the privileged local operator when admin actions arrive.",
                    items = listOf("view", "request", "desktop execute"),
                )
                WorkSurface(
                    title = "Next Landing Zones",
                    detail = "Collectors should feed real DTOs before the UI pretends to know more than the control plane knows.",
                    items = listOf("selftest parity", ".arcana schema", "CI history"),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.weight(1.35f)) {
                    SignalStack(summary.repos)
                }
                Box(modifier = Modifier.weight(1f)) {
                    WorkSurface(
                        title = "Action Boundary",
                        detail = "Public web stays typed and request-only; desktop becomes the privileged local operator when admin actions arrive.",
                        items = listOf("view", "request", "desktop execute"),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    WorkSurface(
                        title = "Next Landing Zones",
                        detail = "Collectors should feed real DTOs before the UI pretends to know more than the control plane knows.",
                        items = listOf("selftest parity", ".arcana schema", "CI history"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalStack(repos: List<RepoHealthDto>) {
    val shape = RoundedCornerShape(8.dp)
    Card(
        modifier = Modifier.fillMaxWidth().surfaceDepth(shape, green, glowAlpha = 0.07f),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = panelRaised),
        border = BorderStroke(1.dp, border),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Live Signal Stack", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            repos.forEach { SignalRow(it) }
        }
    }
}

@Composable
private fun SignalRow(repo: RepoHealthDto) {
    val run = repo.latestRun
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .border(BorderStroke(1.dp, repo.status.color().copy(alpha = 0.22f)), RoundedCornerShape(7.dp))
            .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(repo.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(repo.name, color = text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(run?.label ?: repo.note ?: "waiting for first collector", color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            run?.detail?.let {
                Text(it, color = Color(0xFFB9C5D2), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        StatusPill(run?.status?.name ?: repo.status.name, (run?.status ?: repo.status).color())
    }
}

@Composable
private fun RepoCard(repo: RepoHealthDto, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Card(
        modifier = modifier.surfaceDepth(shape, repo.status.color(), glowAlpha = 0.11f),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = panelRaised),
        border = BorderStroke(1.dp, repo.status.color().copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(repo.status)
                Column(modifier = Modifier.weight(1f)) {
                    Text(repo.name, color = text, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(repo.role, color = muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(repo.status.name, repo.status.color())
            }
            FieldGrid(
                listOf(
                    FieldSpec("service", repo.serviceName ?: "-"),
                    FieldSpec("issues", repo.issues.active.toString()),
                    FieldSpec("location", repo.location),
                ),
            )
            repo.latestRun?.let { RunPanel(it) }
            repo.note?.let {
                Text(it, color = Color(0xFFC9D3DF), fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
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
private fun FieldGrid(fields: List<FieldSpec>) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        fields.forEach { Field(it.name, it.value) }
    }
}

@Composable
private fun Field(name: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name.uppercase(), color = Color(0xFF748195), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color(0xFFDCE4EE), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MetricCard(metric: FieldSpec) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .surfaceDepth(shape, cyan, glowAlpha = 0.06f)
            .clip(shape)
            .background(Color(0xFF101720))
            .border(BorderStroke(1.dp, border), shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(metric.name.uppercase(), color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(metric.value, color = text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
            items = listOf("backend-local", "server_py live selftest", "dashboard web / desktop", "arcana local publisher"),
        )
        is OpsLoadState.Failed -> WorkSurface(
            title = "CI Results Unavailable",
            detail = loadState.message,
            items = listOf("/api/ops/summary", "backend control plane", "dashboard API"),
        )
        is OpsLoadState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PyramidHeader(loadState.summary)
            PipelineGrid(loadState.summary.repos)
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
            .surfaceDepth(shape, repo.status.color(), glowAlpha = 0.05f)
            .clip(shape)
            .background(Color(0xFF101720))
            .border(BorderStroke(1.dp, border), shape)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(repo.name, color = text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            StatusPill(if (active == 0) "clear" else "$active active", if (active == 0) green else amber)
        }
        Text(".arcana/issues.json", color = muted, fontSize = 11.sp)
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
    Card(
        modifier = Modifier.fillMaxWidth().surfaceDepth(shape, amber, glowAlpha = 0.08f),
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Issue Command Board", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Local Arcana issue files across backend, server_py, and arcana; agent-managed lanes, linked runs, and review artifacts next.", color = muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("$active active", if (active > 0) amber else green)
                StatusPill("$done done", muted)
            }
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
    Card(
        modifier = modifier.surfaceDepth(shape, lane.color, glowAlpha = 0.07f),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)),
        border = BorderStroke(1.dp, lane.color.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Big CI Pyramid", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("GitHub Actions, deploy smoke, server_py live selftest, and future Arcana publishers converge here.", color = muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            StatusPill("${summary.repos.size} repos", cyan)
        }
    }
}

@Composable
private fun PipelineGrid(repos: List<RepoHealthDto>) {
    BoxWithConstraints {
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repos.forEach { PipelineLane(it, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repos.forEach { repo ->
                    PipelineLane(repo, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PipelineLane(repo: RepoHealthDto, modifier: Modifier = Modifier) {
    val steps = pipelineSteps(repo)
    val shape = RoundedCornerShape(8.dp)
    Card(
        modifier = modifier.surfaceDepth(shape, repo.status.color(), glowAlpha = 0.09f),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = panelRaised),
        border = BorderStroke(1.dp, repo.status.color().copy(alpha = 0.24f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(repo.status)
                Column(modifier = Modifier.weight(1f)) {
                    Text(repo.name, color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(repo.role, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            steps.forEachIndexed { index, step ->
                PipelineStep(index + 1, step)
            }
        }
    }
}

@Composable
private fun PipelineStep(index: Int, step: TestRunSummaryDto) {
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
                Text(step.status.name, color = step.status.color(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

    val shape = RoundedCornerShape(8.dp)
    Card(
        modifier = Modifier.fillMaxWidth().surfaceDepth(shape, green, glowAlpha = 0.06f),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = panelRaised),
        border = BorderStroke(1.dp, border),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Recent Runs", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Successful deploy-gated runs emitted by the backend control plane.", color = muted, fontSize = 12.sp)
                }
                StatusPill("${events.size} events", green)
            }
            events.forEach { (repo, run) -> RunHistoryRow(repo, run) }
        }
    }
}

@Composable
private fun RunHistoryRow(repo: RepoHealthDto, run: TestRunSummaryDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .border(BorderStroke(1.dp, run.status.color().copy(alpha = 0.24f)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(run.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${repo.name} / ${run.label}", color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(run.durationMs?.ms() ?: run.status.name, color = run.status.color(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
    Card(
        modifier = Modifier.fillMaxWidth().surfaceDepth(shape, selfTest?.status?.color() ?: cyan, glowAlpha = 0.07f),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = panelRaised),
        border = BorderStroke(1.dp, border),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
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
                    fields.forEach { Field(it.name, it.value) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    fields.forEach { field ->
                        Box(modifier = Modifier.weight(1f)) { Field(field.name, field.value) }
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
    Card(
        modifier = Modifier.fillMaxWidth().surfaceDepth(shape, cyan, glowAlpha = 0.06f),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = panelRaised),
        border = BorderStroke(1.dp, border),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
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
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) cyan else Color(0xFF6D7889)
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .then(if (selected) Modifier.surfaceDepth(shape, cyan, glowAlpha = 0.14f) else Modifier)
            .clip(shape)
            .background(if (selected) Color(0xFF12303E) else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) cyan.copy(alpha = 0.45f) else Color(0xFF25313E)), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
private fun StatusDot(status: OpsStatusDto) {
    val transition = rememberInfiniteTransition(label = "status-dot")
    val pulse by transition.animateFloat(
        initialValue = if (status == OpsStatusDto.OK || status == OpsStatusDto.UNKNOWN) 0.32f else 0.18f,
        targetValue = if (status == OpsStatusDto.OK || status == OpsStatusDto.UNKNOWN) 0.32f else 0.42f,
        animationSpec = infiniteRepeatable(animation = tween(1300, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "status-dot-pulse",
    )
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .size(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(999.dp))
                .alpha(pulse)
                .background(status.color()),
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(status.color()),
        )
    }
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

private fun OpsStatusDto.color(): Color = when (this) {
    OpsStatusDto.OK -> green
    OpsStatusDto.WARN -> amber
    OpsStatusDto.FAIL -> rose
    OpsStatusDto.WIP -> cyan
    OpsStatusDto.UNKNOWN -> Color(0xFF8D98A9)
}

private fun Double.ms(): String = if (this <= 0.0) "-" else "${roundToInt()}ms"

private fun Modifier.surfaceDepth(shape: RoundedCornerShape, accent: Color, glowAlpha: Float): Modifier = this
    .dropShadow(
        shape,
        Shadow(
            radius = 18.dp,
            spread = (-4).dp,
            offset = DpOffset(0.dp, 8.dp),
            color = Color.Black,
            alpha = 0.44f,
        ),
    )
    .dropShadow(
        shape,
        Shadow(
            radius = 24.dp,
            spread = (-12).dp,
            offset = DpOffset.Zero,
            color = accent,
            alpha = glowAlpha,
        ),
    )
    .innerShadow(
        shape,
        Shadow(
            radius = 8.dp,
            offset = DpOffset(0.dp, 1.dp),
            color = Color.White,
            alpha = 0.035f,
        ),
    )
    .border(BorderStroke(1.dp, accent.copy(alpha = glowAlpha)), shape)

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
        repo.latestRun ?: TestRunSummaryDto("deploy smoke", repo.status, detail = "Latest backend deploy-gated checks."),
        TestRunSummaryDto("server tests", OpsStatusDto.OK, detail = ":server:test and shared DTO checks compile against the control plane."),
        TestRunSummaryDto("public ingress", OpsStatusDto.WIP, detail = "External https://sdfgsdfg.net/test probe stays outside deploy restart gating."),
    )
    "server_py" -> listOfNotNull(
        repo.latestRun ?: TestRunSummaryDto("live selftest", repo.status, detail = "Waiting for persisted server-py-selftest.json."),
        TestRunSummaryDto("dashboard selftest parity", OpsStatusDto.OK, detail = "Dashboard renders the live selftest JSON, workflow link, and model matrix."),
        TestRunSummaryDto("gRPC/browser bridge", OpsStatusDto.WIP, detail = "server_py keeps browser automation internals; backend displays normalized facts."),
    )
    "arcana" -> listOf(
        repo.latestRun ?: TestRunSummaryDto("local publisher", OpsStatusDto.WIP, detail = "Arcana remains local-first for MVP."),
        TestRunSummaryDto("pytest unit spine", OpsStatusDto.WIP, detail = "Future local publisher reports pytest/session/issue summaries."),
        TestRunSummaryDto("RSI sessions", OpsStatusDto.WIP, detail = "Deferred until issue and CI surfaces can receive output."),
    )
    else -> listOf(repo.latestRun ?: TestRunSummaryDto("latest run", repo.status, detail = repo.note))
}
