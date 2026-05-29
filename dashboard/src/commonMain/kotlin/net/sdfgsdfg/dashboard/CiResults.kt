package net.sdfgsdfg.dashboard

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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
internal fun CiResults(loadState: OpsLoadState, atPageBottom: Boolean = false) {
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
            RunHistoryPanel(loadState.summary, atPageBottom)
        }
    }
}

@Composable
private fun CiHeader(summary: OpsSummaryDto) {
    val ok = summary.repos.count { it.ciStatus() == OpsStatusDto.OK }
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
            Text("Test evidence across backend, server_py, and Arcana.", color = muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill("$ok/${summary.repos.size} OK", if (ok == summary.repos.size) green else amber)
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
    val status = repo.ciStatus()
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
            runs.filterNot { it.label in setOf("live e2e selftest", "model matrix") }.takeWhile { it.label != "full suite" }.forEach { run ->
                CiEvidenceCard(
                    title = run.evidenceTitle(repo.id),
                    status = run.status,
                    generatedAtMs = generatedAtMs,
                    timestampMs = run.timestampMs,
                    durationMs = run.durationMs,
                    subtitle = run.evidenceSubtitle(repo.id),
                    detail = run.evidenceDetail(repo.id),
                    fields = run.evidenceFields(repo.id, generatedAtMs),
                    artifactUrl = run.url,
                )
            }
            ServerPySelfTestSummary(repo.selfTest, generatedAtMs)
            runs.firstOrNull { it.label == "full suite" }?.let { run ->
                CiEvidenceCard(
                    title = run.evidenceTitle(repo.id),
                    status = run.status,
                    generatedAtMs = generatedAtMs,
                    timestampMs = run.timestampMs,
                    durationMs = run.durationMs,
                    subtitle = run.evidenceSubtitle(repo.id),
                    detail = run.evidenceDetail(repo.id),
                    fields = run.evidenceFields(repo.id, generatedAtMs),
                    artifactUrl = run.url,
                )
            }
        } else {
            if (runs.isEmpty()) PlaceholderTile("test evidence unavailable")
            runs.forEach { run ->
                CiEvidenceCard(
                    title = run.evidenceTitle(repo.id),
                    status = run.status,
                    generatedAtMs = generatedAtMs,
                    timestampMs = run.timestampMs.takeUnless { repo.id == "arcana" },
                    durationMs = run.durationMs.takeUnless { repo.id == "arcana" },
                    subtitle = run.evidenceSubtitle(repo.id),
                    detail = run.evidenceDetail(repo.id),
                    fields = run.evidenceFields(repo.id, generatedAtMs),
                    artifactUrl = run.url,
                )
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
        if (fields.isNotEmpty()) FieldGrid(fields)
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
private fun ServerPySelfTestSummary(selfTest: SelfTestSummaryDto?, generatedAtMs: Long) {
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
        artifactUrl = selfTest.workflowUrl,
        artifacts = selfTest.artifacts,
    )
}

@Composable
private fun FieldGrid(fields: List<FieldSpec>) {
    BoxWithConstraints {
        if (maxWidth < 620.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fields.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { field -> Box(modifier = Modifier.weight(1f)) { FactTile(field) } }
                        if (row.size == 1) Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    val links = buildList {
        primaryUrl?.let { add(OpsArtifactDto(name = if (it.startsWith("http")) "GitHub workflow" else it.substringAfterLast('/'), url = it)) }
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
private fun RunHistoryPanel(summary: OpsSummaryDto, atPageBottom: Boolean) {
    val repos = summary.repos.filter { it.id in historyRepoIds }
    var enabled by remember { mutableStateOf(historyRepoIds.toSet()) }
    var visibleLimit by remember { mutableStateOf(12) }
    val eventsByRepo = repos.associateWith { repo ->
        (repo.history + repo.runs.filter { it.status == OpsStatusDto.WIP })
            .distinctBy { it.label to it.timestampMs }
    }
    val counts = eventsByRepo.entries.associate { (repo, runs) -> repo.id to runs.size }
    val allEvents = eventsByRepo
        .flatMap { (repo, runs) -> runs.map { repo to it } }
        .sortedByDescending { it.second.timestampMs ?: 0L }
    val events = allEvents.filter { it.first.id in enabled }
    val visibleEvents = events.take(visibleLimit)
    if (allEvents.isEmpty()) return
    val eventKeys = visibleEvents.map { (repo, run) -> "${repo.id}-${run.label}-${run.timestampMs}" }
    val freshKeys = rememberFreshKeys(eventKeys)
    LaunchedEffect(atPageBottom, events.size, visibleLimit) {
        if (atPageBottom && visibleLimit < events.size) visibleLimit = (visibleLimit + 12).coerceAtMost(events.size)
    }

    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, green, glowAlpha = 0.06f, borderAlpha = 0.26f)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Recent Runs", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Unified backend, server_py, and Arcana CI evidence.", color = muted, fontSize = 12.sp)
            }
            StatusPill("${visibleEvents.size}/${events.size} shown", green)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            historyRepoIds.forEach { id ->
                HistoryFilterPill(
                    label = "${id.displayRepoName()} ${counts[id] ?: 0}",
                    color = id.historyColor(),
                    enabled = id in enabled,
                    onClick = { enabled = if (id in enabled) enabled - id else enabled + id },
                )
            }
        }
        if (events.isEmpty()) PlaceholderTile("no runs selected")
        visibleEvents.forEachIndexed { index, (repo, run) ->
            RunHistoryRow(repo, run, summary.generatedAtMs, fresh = eventKeys[index] in freshKeys)
        }
    }
}

private val historyRepoIds = listOf("backend", "server_py", "arcana")

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
private fun RunHistoryRow(repo: RepoHealthDto, run: TestRunSummaryDto, generatedAtMs: Long, fresh: Boolean = false) {
    val flash = if (fresh) 1f else 0f
    val running = run.status == OpsStatusDto.WIP
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .background(run.status.color().copy(alpha = flash * 0.11f))
            .border(BorderStroke(1.dp, run.status.color().copy(alpha = 0.24f + flash * 0.32f)), RoundedCornerShape(7.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FreshRail(run.timestampMs, generatedAtMs)
        StatusDot(run.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${repo.name} / ${run.label}", modifier = Modifier.weight(1f), color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (fresh) UpdatePill(run.status.color())
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
