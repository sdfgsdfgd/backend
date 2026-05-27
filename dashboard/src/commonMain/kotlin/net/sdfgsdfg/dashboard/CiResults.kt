package net.sdfgsdfg.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsArtifactDto
import net.sdfgsdfg.data.model.RepoHealthDto
import net.sdfgsdfg.data.model.SelfTestSummaryDto
import net.sdfgsdfg.data.model.TestRunSummaryDto

@Composable
internal fun CiResults(loadState: OpsLoadState) {
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
    val steps = repo.runs.ifEmpty { listOf(repo.latestRun ?: TestRunSummaryDto("latest run", repo.status, detail = repo.note)) }
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
