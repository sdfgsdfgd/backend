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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            detail = "Waiting for ops summary.",
            items = listOf("backend", "server_py", "arcana"),
        )
        is OpsLoadState.Failed -> WorkSurface(
            title = "CI Results Unavailable",
            detail = loadState.message,
            items = listOf("/api/ops/summary", "backend control plane", "dashboard API"),
        )
        is OpsLoadState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CiHeader(loadState.summary)
            VerificationGrid(loadState.summary)
            RunHistoryPanel(loadState.summary)
        }
    }
}

@Composable
private fun CiHeader(summary: OpsSummaryDto) {
    val remoteCi = summary.repos.count { it.id == "backend" || it.id == "server_py" }
    val ok = summary.repos.count { it.status == OpsStatusDto.OK }
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
            Text("CI Results", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("backend + server_py remote CI, Arcana local pytest evidence.", color = muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill("$ok/${summary.repos.size} OK", if (ok == summary.repos.size) green else amber)
            StatusPill("$remoteCi remote CI", cyan)
        }
    }
}

@Composable
private fun VerificationGrid(summary: OpsSummaryDto) {
    val repos = summary.repos
    BoxWithConstraints {
        if (maxWidth < 980.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repos.forEach { CiRepoCard(it, summary.generatedAtMs, modifier = Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repos.forEach { repo ->
                    CiRepoCard(repo, summary.generatedAtMs, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CiRepoCard(repo: RepoHealthDto, generatedAtMs: Long, modifier: Modifier = Modifier) {
    val runs = repo.ciRuns()
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
                Text(repo.ciRole(), color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(repo.status.name, repo.status.color())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repo.ciBadges().forEach { PanelBadge(it) }
        }
        if (repo.id == "server_py") {
            ServerPySelfTestSummary(repo.selfTest, generatedAtMs)
        } else {
            runs.forEach { CiRunRow(it, generatedAtMs) }
            if (repo.id == "arcana") ArcanaCoverage(repo, generatedAtMs)
        }
    }
}

@Composable
private fun CiRunRow(run: TestRunSummaryDto, generatedAtMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .border(BorderStroke(1.dp, run.status.color().copy(alpha = 0.22f)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(run.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(run.label, modifier = Modifier.weight(1f), color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RunTail(run, generatedAtMs, run.durationMs?.durationLabel() ?: run.status.name, fontSize = 11.sp)
            }
            run.detail?.let {
                Text(it, color = muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            run.url?.let { url ->
                Text(url, modifier = Modifier.clickable { openOpsUrl(url) }, color = cyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ServerPySelfTestSummary(selfTest: SelfTestSummaryDto?, generatedAtMs: Long) {
    if (selfTest == null) {
        PlaceholderTile("selftest artifact unavailable")
        return
    }
    val fields = listOf(
        FieldSpec("conversation", if (selfTest.ok) "pass" else "fail"),
        FieldSpec("expectation", if (selfTest.satisfiedExpectation) "met" else "missed"),
        FieldSpec("models", "${selfTest.casePassCount}/${selfTest.caseCount}"),
        FieldSpec("total", selfTest.latencyMs.durationLabel()),
        FieldSpec("ask", selfTest.askLatencyMs.durationLabel()),
        FieldSpec("audit", selfTest.auditLatencyMs.durationLabel()),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .border(BorderStroke(1.dp, selfTest.status.color().copy(alpha = 0.24f)), RoundedCornerShape(7.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("last selftest", color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(selfTest.timestampLabel ?: selfTest.timestampMs?.relativeFrom(generatedAtMs) ?: "timestamp unknown", color = muted, fontSize = 11.sp)
            }
            StatusPill("TEST: selftest ${selfTest.status.name}", selfTest.status.color())
        }
        FieldGrid(fields)
        (selfTest.rawError ?: selfTest.textExcerpt).takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color(0xFFD3DCE8), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        ArtifactStrip(selfTest.workflowUrl, selfTest.artifacts)
        selfTest.zenFields().takeIf { it.isNotEmpty() }?.let { FieldGrid(it) }
    }
}

@Composable
private fun ArcanaCoverage(repo: RepoHealthDto, generatedAtMs: Long) {
    val latest = repo.latestRun ?: return
    val detail = latest.detail.orEmpty()
    val fields = listOfNotNull(
        detail.substringBefore(" passed", missingDelimiterValue = "").takeIf { it.all(Char::isDigit) }?.let { FieldSpec("unit tests", "$it passed") },
        latest.durationMs?.durationLabel()?.let { FieldSpec("duration", it) },
        latest.timestampMs?.relativeFrom(generatedAtMs)?.let { FieldSpec("last", it) },
        detail.substringAfter("@", missingDelimiterValue = "").takeIf { it.isNotBlank() }?.let { FieldSpec("commit", it) },
    )
    if (fields.isEmpty()) return
    FieldGrid(fields)
}

@Composable
private fun FieldGrid(fields: List<FieldSpec>) {
    BoxWithConstraints {
        if (maxWidth < 620.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { field -> Box(modifier = Modifier.weight(1f)) { FactTile(field) } }
                        if (row.size == 1) Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { field -> Box(modifier = Modifier.weight(1f)) { FactTile(field) } }
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
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(field.name.uppercase(), color = muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(field.value, color = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        field.detail?.let { Text(it, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun ArtifactStrip(workflowUrl: String?, artifacts: List<OpsArtifactDto>) {
    val links = buildList {
        workflowUrl?.let { add(OpsArtifactDto(name = "GitHub workflow", url = it)) }
        addAll(artifacts)
    }
    if (links.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        links.forEach { artifact ->
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
                RunTail(run, generatedAtMs, run.durationMs?.durationLabel() ?: run.status.name, fontSize = 11.sp)
            }
            run.detail?.let {
                Text(it, color = muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun RepoHealthDto.ciRole() = when (id) {
    "backend" -> "deploy verify + Gradle checks"
    "server_py" -> "GitHub selftest + model matrix"
    "arcana" -> "local pytest coverage"
    else -> role
}

private fun RepoHealthDto.ciBadges(): List<BadgeSpec> = when (id) {
    "backend" -> listOfNotNull(
        BadgeSpec("remote CI", cyan, strong = true),
        latestRun?.let { BadgeSpec("verifyServer ${it.status.name}", it.status.color(), strong = it.status == OpsStatusDto.OK) },
        runs.firstOrNull { it.label == "server checks" }?.let { BadgeSpec("Gradle ${it.status.name}", it.status.color(), strong = it.status == OpsStatusDto.OK) },
    )
    "server_py" -> listOfNotNull(
        BadgeSpec("remote CI", cyan, strong = true),
        selfTest?.let { BadgeSpec("selftest ${it.status.name}", it.status.color(), strong = it.status == OpsStatusDto.OK) },
        selfTest?.let { BadgeSpec("${it.casePassCount}/${it.caseCount} models", if (it.casePassCount == it.caseCount) green else rose, strong = true) },
    )
    "arcana" -> listOf(
        BadgeSpec("local tests", cyan, strong = true),
        BadgeSpec(status.name, status.color(), strong = status == OpsStatusDto.OK),
    )
    else -> emptyList()
}

private fun RepoHealthDto.ciRuns(): List<TestRunSummaryDto> = when (id) {
    "backend" -> listOfNotNull(latestRun) + runs.filter { it.label == "server checks" }
    "server_py" -> emptyList()
    "arcana" -> runs.filter { it.isArcanaTestRun() && it.label != latestRun?.label }.ifEmpty { listOfNotNull(latestRun) }
    else -> runs.ifEmpty { listOfNotNull(latestRun) }
}.distinctBy { it.label to it.timestampMs }

private fun TestRunSummaryDto.isArcanaTestRun() = label.contains("pytest", ignoreCase = true) ||
    label.contains("z_tests", ignoreCase = true) ||
    detail?.contains("passed", ignoreCase = true) == true

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
