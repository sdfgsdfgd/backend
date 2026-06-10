package net.sdfgsdfg.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.sdfgsdfg.data.model.OpsRunEventDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsArtifactDto
import net.sdfgsdfg.data.model.RepoHealthDto
import net.sdfgsdfg.data.model.SelfTestSummaryDto
import net.sdfgsdfg.data.model.TestRunSummaryDto

internal fun LazyListScope.ciItems(loadState: OpsLoadState, pageWidth: Dp, historyState: CiHistoryState?) {
    when (loadState) {
        OpsLoadState.Loading -> item(key = "ci-loading") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                WorkSurface(
                    title = "CI Results",
                    detail = "Waiting for ops summary.",
                    items = listOf("backend", "server_py", "arcana"),
                )
            }
        }
        is OpsLoadState.Failed -> item(key = "ci-failed") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                WorkSurface(
                    title = "CI Results Unavailable",
                    detail = loadState.message,
                    items = listOf("/api/ops/summary", "backend control plane", "dashboard API"),
                )
            }
        }
        is OpsLoadState.Ready -> {
            item(key = "ci-header") {
                CiHeader(loadState.summary, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp))
            }
            verificationItems(loadState.summary, pageWidth)
            historyState?.let { historyItems(it, loadState.summary.generatedAtMs) }
        }
    }
}

@Composable
private fun CiHeader(summary: OpsSummaryDto, modifier: Modifier = Modifier) {
    val ok = remember(summary.repos) { summary.repos.count { it.ciStatus() == OpsStatusDto.OK } }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .glassSurface(shape, cyan, glowAlpha = 0.08f, borderAlpha = 0.28f)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CI Results", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Test evidence across backend, server_py, and Arcana.", color = muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill("$ok/${summary.repos.size} OK", if (ok == summary.repos.size) green else amber)
        }
    }
}

private fun LazyListScope.verificationItems(summary: OpsSummaryDto, pageWidth: Dp) {
    val repos = summary.repos
    if (pageWidth < 980.dp) {
        itemsIndexed(repos, key = { _, repo -> "ci-repo-${repo.id}" }) { index, repo ->
            val bottom = if (index == repos.lastIndex) 14.dp else 12.dp
            CiRepoCard(
                repo = repo,
                generatedAtMs = summary.generatedAtMs,
                fieldCompact = pageWidth < 620.dp,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = bottom),
            )
        }
    } else {
        item(key = "ci-repo-grid") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repos.forEach { repo ->
                    key(repo.id) {
                        CiRepoCard(repo, summary.generatedAtMs, fieldCompact = true, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CiRepoCard(repo: RepoHealthDto, generatedAtMs: Long, fieldCompact: Boolean, modifier: Modifier = Modifier) {
    val runs = remember(repo) { repo.ciRuns() }
    val status = remember(repo) { repo.ciStatus() }
    val serverPyEvidence = remember(runs) {
        runs.filterNot { it.label in setOf("live e2e selftest", "model matrix") }.takeWhile { it.label != "full suite" }
    }
    val serverPyFullSuite = remember(runs) { runs.firstOrNull { it.label == "full suite" } }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .glassSurface(shape, status.color(), glowAlpha = 0.09f, borderAlpha = 0.32f)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(status)
            Column(modifier = Modifier.weight(1f)) {
                Text(repo.name, color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(repo.ciRole(), color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(status.name, status.color())
        }
        if (repo.id == "server_py") {
            serverPyEvidence.forEach { run ->
                key(run.ciKey(repo)) {
                    CiEvidenceCard(
                        title = run.evidenceTitle(repo.id),
                        status = run.status,
                        generatedAtMs = generatedAtMs,
                        timestampMs = run.timestampMs,
                        durationMs = run.durationMs,
                        subtitle = run.evidenceSubtitle(repo.id),
                        detail = run.evidenceDetail(repo.id),
                        fields = run.evidenceFields(repo.id, generatedAtMs),
                        fieldCompact = fieldCompact,
                        artifactUrl = run.url,
                    )
                }
            }
            key("server_py-selftest") {
                ServerPySelfTestSummary(repo.selfTest, generatedAtMs, fieldCompact)
            }
            serverPyFullSuite?.let { run ->
                key(run.ciKey(repo)) {
                    CiEvidenceCard(
                        title = run.evidenceTitle(repo.id),
                        status = run.status,
                        generatedAtMs = generatedAtMs,
                        timestampMs = run.timestampMs,
                        durationMs = run.durationMs,
                        subtitle = run.evidenceSubtitle(repo.id),
                        detail = run.evidenceDetail(repo.id),
                        fields = run.evidenceFields(repo.id, generatedAtMs),
                        fieldCompact = fieldCompact,
                        artifactUrl = run.url,
                    )
                }
            }
        } else {
            if (runs.isEmpty()) PlaceholderTile("test evidence unavailable")
            runs.forEach { run ->
                key(run.ciKey(repo)) {
                    CiEvidenceCard(
                        title = run.evidenceTitle(repo.id),
                        status = run.status,
                        generatedAtMs = generatedAtMs,
                        timestampMs = run.timestampMs.takeUnless { repo.id == "arcana" },
                        durationMs = run.durationMs.takeUnless { repo.id == "arcana" },
                        subtitle = run.evidenceSubtitle(repo.id),
                        detail = run.evidenceDetail(repo.id),
                        fields = run.evidenceFields(repo.id, generatedAtMs),
                        fieldCompact = fieldCompact,
                        artifactUrl = run.url,
                    )
                }
            }
        }
    }
}

@Composable
private fun CiEvidenceCard(
    title: String,
    status: OpsStatusDto,
    generatedAtMs: Long,
    timestampMs: Long? = null,
    durationMs: Double? = null,
    subtitle: String? = null,
    detail: String? = null,
    fields: List<FieldSpec> = emptyList(),
    fieldCompact: Boolean,
    artifactUrl: String? = null,
    artifacts: List<OpsArtifactDto> = emptyList(),
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .border(BorderStroke(1.dp, status.evidenceColor().copy(alpha = 0.24f)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            EvidenceTail(status, generatedAtMs, timestampMs, durationMs)
        }
        if (fields.isNotEmpty()) FieldGrid(fields, fieldCompact)
        detail?.let { Text(it, color = Color(0xFFD3DCE8), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        ArtifactStrip(artifactUrl, artifacts)
    }
}

@Composable
private fun EvidenceTail(status: OpsStatusDto, generatedAtMs: Long, timestampMs: Long?, durationMs: Double?) {
    val duration = durationMs?.durationLabel()
    if (timestampMs == null && duration == null && status == OpsStatusDto.OK) return
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        timestampMs?.let { AgePill(it, generatedAtMs) }
        duration?.let { Text(it, color = status.evidenceColor(), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        if (status != OpsStatusDto.OK) Text(status.name, color = status.color(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ServerPySelfTestSummary(selfTest: SelfTestSummaryDto?, generatedAtMs: Long, fieldCompact: Boolean) {
    if (selfTest == null) {
        PlaceholderTile("selftest artifact unavailable")
        return
    }
    val failures = listOfNotNull(
        "conversation failed".takeIf { !selfTest.ok },
        "expectation missed".takeIf { !selfTest.satisfiedExpectation },
        "model matrix ${selfTest.casePassCount}/${selfTest.caseCount}".takeIf { selfTest.casePassCount != selfTest.caseCount },
    ).joinToString(" · ")
    val fields = listOf(
        FieldSpec("models", "${selfTest.casePassCount}/${selfTest.caseCount}"),
        FieldSpec("total", selfTest.latencyMs.durationLabel()),
        FieldSpec("ask", selfTest.askLatencyMs.durationLabel()),
        FieldSpec("audit", selfTest.auditLatencyMs.durationLabel()),
    )
    CiEvidenceCard(
        title = "live e2e selftest",
        status = selfTest.status,
        generatedAtMs = generatedAtMs,
        timestampMs = null,
        durationMs = null,
        subtitle = selfTest.timestampLabel ?: selfTest.timestampMs?.relativeFrom(generatedAtMs) ?: "timestamp unknown",
        detail = listOfNotNull(
            failures.takeIf { it.isNotBlank() },
            (selfTest.rawError ?: selfTest.textExcerpt).takeIf { it.isNotBlank() },
        ).joinToString("\n").takeIf { it.isNotBlank() },
        fields = fields + selfTest.zenFields(),
        fieldCompact = fieldCompact,
        artifactUrl = selfTest.workflowUrl,
        artifacts = selfTest.artifacts,
    )
}

@Composable
private fun FieldGrid(fields: List<FieldSpec>, compact: Boolean) {
    if (compact) {
        val rows = remember(fields) { fields.chunked(2) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { field ->
                        key(field.name) {
                            Box(modifier = Modifier.weight(1f)) { FactTile(field) }
                        }
                    }
                    if (row.size == 1) Box(modifier = Modifier.weight(1f))
                }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fields.forEach { field ->
                key(field.name) {
                    Box(modifier = Modifier.weight(1f)) { FactTile(field) }
                }
            }
        }
    }
}

@Composable
private fun FactTile(field: FieldSpec) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF101821))
            .border(BorderStroke(1.dp, Color(0xFF202B38)), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(field.name.uppercase(), color = muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(field.value, color = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        field.detail?.let { Text(it, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun ArtifactStrip(primaryUrl: String?, artifacts: List<OpsArtifactDto>) {
    val links = remember(primaryUrl, artifacts) {
        buildList {
            primaryUrl?.let { add(OpsArtifactDto(name = if (it.startsWith("http")) "GitHub workflow" else it.substringAfterLast('/'), url = it)) }
            addAll(artifacts)
        }
    }
    if (links.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        links.forEach { artifact ->
            key("${artifact.name}-${artifact.url}") {
                val url = artifact.url
                Text(
                    artifact.name,
                    modifier = if (url == null) Modifier else Modifier.clickable { openOpsUrl(url) },
                    color = if (url == null) muted else cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun rememberCiHistoryState(summary: OpsSummaryDto?, activeRunEvents: List<OpsRunEventDto>, atPageBottom: Boolean): CiHistoryState? {
    if (summary == null) return null
    var enabled by remember { mutableStateOf(readHistoryRepoFilter()) }
    var visibleLimit by remember { mutableStateOf(12) }
    val repos = remember(summary.repos) { summary.repos.filter { it.id in historyRepoIds } }
    val activeByRepo = remember(activeRunEvents) { activeRunEvents.groupBy({ it.repoId }, { it.run }) }
    val eventsByRepo = remember(repos, activeByRepo) {
        repos.associateWith { repo ->
            (activeByRepo[repo.id].orEmpty() + repo.history + repo.runs.filter { it.status == OpsStatusDto.WIP })
                .distinctBy { it.label to it.timestampMs }
        }
    }
    val counts = remember(eventsByRepo) { eventsByRepo.entries.associate { (repo, runs) -> repo.id to runs.size } }
    val allEvents = remember(eventsByRepo) {
        eventsByRepo
            .flatMap { (repo, runs) -> runs.map { repo to it } }
            .sortedByDescending { it.second.timestampMs ?: 0L }
    }
    if (allEvents.isEmpty()) return null
    val events = remember(allEvents, enabled) { allEvents.filter { it.first.id in enabled } }
    val visibleEvents = remember(events, visibleLimit) {
        events.take(visibleLimit).map { (repo, run) -> CiHistoryEvent(repo, run, run.ciKey(repo)) }
    }
    val eventKeys = remember(visibleEvents) { visibleEvents.map { it.key } }
    val freshKeys = rememberFreshKeys(eventKeys)
    var knownEnterKeys by remember { mutableStateOf<Set<String>?>(null) }
    var enterKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(eventKeys) {
        val current = eventKeys.toSet()
        enterKeys = knownEnterKeys?.let { current - it } ?: current
        knownEnterKeys = current
        if (enterKeys.isNotEmpty()) {
            delay(840)
            enterKeys = emptySet()
        }
    }
    LaunchedEffect(atPageBottom, events.size, visibleLimit) {
        if (atPageBottom && visibleLimit < events.size) visibleLimit = (visibleLimit + 12).coerceAtMost(events.size)
    }
    return CiHistoryState(
        counts = counts,
        enabled = enabled,
        visibleEvents = visibleEvents,
        total = events.size,
        freshKeys = freshKeys,
        enterKeys = enterKeys,
        onToggleRepo = { id ->
            val next = if (id in enabled) enabled - id else enabled + id
            enabled = next
            writeDashboardPref(historyRepoFilterPrefKey, historyRepoIds.filter { it in next }.joinToString(","))
        },
    )
}

internal data class CiHistoryState(
    val counts: Map<String, Int>,
    val enabled: Set<String>,
    val visibleEvents: List<CiHistoryEvent>,
    val total: Int,
    val freshKeys: Set<String>,
    val enterKeys: Set<String>,
    val onToggleRepo: (String) -> Unit,
)

internal data class CiHistoryEvent(
    val repo: RepoHealthDto,
    val run: TestRunSummaryDto,
    val key: String,
)

private fun LazyListScope.historyItems(state: CiHistoryState, generatedAtMs: Long) {
    item(key = "ci-history-header") {
        RunHistoryHeader(state, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
    }
    item(key = "ci-history-empty") {
        AnimatedVisibility(
            visible = state.visibleEvents.isEmpty(),
            modifier = Modifier.animateItem().fillMaxWidth(),
            enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                expandVertically(tween(260, easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
            exit = fadeOut(tween(140, easing = FastOutSlowInEasing)) +
                shrinkVertically(tween(180, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
        ) {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                PlaceholderTile("no runs selected")
            }
        }
    }
    items(state.visibleEvents, key = { event -> "ci-history-${event.key}" }) { event ->
        val visibleState = remember(event.key) {
            MutableTransitionState(event.key !in state.enterKeys).apply { targetState = true }
        }
        AnimatedVisibility(
            visibleState = visibleState,
            modifier = Modifier.animateItem().fillMaxWidth(),
            enter = fadeIn(tween(660, easing = FastOutSlowInEasing)) +
                expandVertically(tween(840, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                slideInVertically(tween(840, easing = FastOutSlowInEasing)) { -it / 4 },
            exit = fadeOut(tween(420, easing = FastOutSlowInEasing)) +
                shrinkVertically(tween(570, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
        ) {
            RunHistoryRow(
                repo = event.repo,
                run = event.run,
                generatedAtMs = generatedAtMs,
                fresh = event.key in state.freshKeys,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun RunHistoryHeader(state: CiHistoryState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .glassSurface(shape, green, glowAlpha = 0.06f, borderAlpha = 0.26f)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Recent Runs", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Unified backend, server_py, and Arcana CI evidence.", color = muted, fontSize = 12.sp)
            }
            StatusPill("${state.visibleEvents.size}/${state.total} shown", green)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            historyRepoIds.forEach { id ->
                key(id) {
                    HistoryFilterPill(
                        label = "${id.displayRepoName()} ${state.counts[id] ?: 0}",
                        color = id.historyColor(),
                        enabled = id in state.enabled,
                        onClick = { state.onToggleRepo(id) },
                    )
                }
            }
        }
    }
}

private val historyRepoIds = listOf("backend", "server_py", "arcana")
private const val historyRepoFilterPrefKey = "ops.ci.enabledRepos"

private fun readHistoryRepoFilter(): Set<String> = readDashboardPref(historyRepoFilterPrefKey)
    ?.split(',')
    ?.filter { it in historyRepoIds }
    ?.toSet()
    ?: historyRepoIds.toSet()

@Composable
private fun HistoryFilterPill(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    val activeColor = if (enabled) color else muted
    Row(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(activeColor.copy(alpha = if (enabled) 0.14f else 0.06f))
            .border(BorderStroke(1.dp, activeColor.copy(alpha = if (enabled) 0.46f else 0.20f)), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(activeColor),
        )
        Text(label, color = activeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RunHistoryRow(repo: RepoHealthDto, run: TestRunSummaryDto, generatedAtMs: Long, fresh: Boolean = false, modifier: Modifier = Modifier) {
    val flash = if (fresh) 1f else 0f
    val running = run.status == OpsStatusDto.WIP
    val color = run.status.color()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .background(color.copy(alpha = flash * 0.11f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.24f + flash * 0.32f)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FreshRail(run.timestampMs, generatedAtMs)
        StatusDot(run.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${repo.name} / ${run.label}", modifier = Modifier.weight(1f), color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (running) {
                    val transition = rememberInfiniteTransition(label = "run-loop")
                    val rotation by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing), repeatMode = RepeatMode.Restart),
                        label = "run-loop-rotation",
                    )
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(color.copy(alpha = 0.08f))
                            .border(BorderStroke(1.dp, color.copy(alpha = 0.24f)), RoundedCornerShape(999.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("∞", modifier = Modifier.graphicsLayer { rotationZ = rotation }, color = color.copy(alpha = 0.88f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (fresh) UpdatePill(color)
                if (running) UpdatePill(cyan, "running")
                RunTail(run, generatedAtMs, run.durationMs?.durationLabel() ?: run.status.name, fontSize = 11.sp)
            }
            run.detail?.let {
                Text(it, color = muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun RepoHealthDto.ciRole() = when (id) {
    "backend" -> "deploy gate + unit tests + umbrella suite"
    "server_py" -> "unit tests + live e2e"
    "arcana" -> "unit tests; integration/e2e slots ready"
    else -> role
}

private fun String.displayRepoName() = if (this == "server_py") "server_py" else replaceFirstChar { it.uppercase() }

private fun TestRunSummaryDto.ciKey(repo: RepoHealthDto): String {
    val identity = url
        ?: timestampMs?.toString()
        ?: listOfNotNull(durationMs?.toString(), coveragePct?.toString(), status.name).joinToString(":")
    return "${repo.id}-$label-$identity"
}

private fun String.historyColor() = when (this) {
    "backend" -> green
    "server_py" -> cyan
    "arcana" -> amber
    else -> muted
}

private fun TestRunSummaryDto.evidenceTitle(repoId: String) = when {
    repoId == "arcana" -> "unit tests"
    label.startsWith("deploy ") || label == "deploy gate" -> "deploy gate"
    label == "full suite" -> "full suite"
    label == "unit tests" -> "unit tests"
    else -> label
}

private fun TestRunSummaryDto.evidenceSubtitle(repoId: String) = when {
    repoId == "arcana" -> label
    label.startsWith("deploy ") -> label
    label == "full suite" && repoId == "backend" -> "weekly GitHub umbrella"
    else -> null
}

private fun TestRunSummaryDto.evidenceDetail(repoId: String) = detail.takeUnless { repoId == "arcana" }

private fun TestRunSummaryDto.arcanaFields(generatedAtMs: Long): List<FieldSpec> {
    val detail = detail.orEmpty()
    return listOfNotNull(
        detail.substringBefore(" passed", missingDelimiterValue = "").takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.let { FieldSpec("unit tests", "$it passed") },
        durationMs?.durationLabel()?.let { FieldSpec("duration", it) },
        timestampMs?.relativeFrom(generatedAtMs)?.let { FieldSpec("last", it) },
        detail.substringAfter("@", missingDelimiterValue = "").takeIf { it.isNotBlank() }?.let { FieldSpec("commit", it) },
    )
}

private fun TestRunSummaryDto.evidenceFields(repoId: String, generatedAtMs: Long): List<FieldSpec> =
    (if (repoId == "arcana") arcanaFields(generatedAtMs) else emptyList()) +
        listOfNotNull(coveragePct?.let { FieldSpec("coverage", it.percentLabel()) })

private fun OpsStatusDto.evidenceColor() = if (this == OpsStatusDto.OK) cyan else color()

private fun RepoHealthDto.ciStatus(): OpsStatusDto {
    val statuses = ciRuns().filterNot { it.label == "full suite" }.map { it.status }
        .ifEmpty { listOf(if (id == "server_py") selfTest?.status ?: OpsStatusDto.UNKNOWN else latestRun?.status ?: OpsStatusDto.UNKNOWN) }
    return when {
        OpsStatusDto.FAIL in statuses -> OpsStatusDto.FAIL
        OpsStatusDto.WARN in statuses -> OpsStatusDto.WARN
        OpsStatusDto.WIP in statuses -> OpsStatusDto.WIP
        OpsStatusDto.UNKNOWN in statuses -> OpsStatusDto.UNKNOWN
        else -> OpsStatusDto.OK
    }
}

private fun RepoHealthDto.ciRuns(): List<TestRunSummaryDto> = when (id) {
    "backend" -> listOfNotNull(latestRun) + runs.filter { it.label in setOf("unit tests", "full suite") }
    "server_py" -> runs.filter { it.label in setOf("unit tests", "live e2e selftest", "model matrix") }
    "arcana" -> listOfNotNull(latestRun).filter { it.isArcanaTestRun() }.ifEmpty { runs.filter { it.isArcanaTestRun() } }
    else -> runs.ifEmpty { listOfNotNull(latestRun) }
}.distinctBy { it.label to it.timestampMs }

private fun SelfTestSummaryDto.zenFields() = listOfNotNull(
    zenState?.let { FieldSpec("zen state", it) },
    zenSeverity?.let { FieldSpec("severity", it) },
    zenReason?.let { FieldSpec("reason", it) },
    zenArtifactPath?.let { FieldSpec("artifact", it) },
)

private fun Double.durationLabel(): String = when {
    this <= 0.0 -> "-"
    this < 1_000.0 -> ms()
    this < 60_000.0 -> "${(this / 1_000.0).round1()}s"
    else -> {
        val totalSeconds = (this / 1_000.0).toInt()
        "${totalSeconds / 60}m ${totalSeconds % 60}s"
    }
}

private fun Double.round1(): String {
    val rounded = kotlin.math.round(this * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

private fun Double.percentLabel() = "${round1()}%"
