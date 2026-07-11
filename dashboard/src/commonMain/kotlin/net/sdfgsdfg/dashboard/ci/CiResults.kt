package net.sdfgsdfg.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import net.sdfgsdfg.data.model.OPS_SUMMARY_PATH
import net.sdfgsdfg.data.model.OpsRunEventDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.RepoHealthDto
import net.sdfgsdfg.data.model.SelfTestSummaryDto
import net.sdfgsdfg.data.model.TestArtifactDto
import net.sdfgsdfg.data.model.TestArtifactKindDto
import net.sdfgsdfg.data.model.TestCaseDto
import net.sdfgsdfg.data.model.TestContractRefDto
import net.sdfgsdfg.data.model.TestRunSummaryDto

@Composable
internal fun CiTab(loadState: OpsLoadState, pageWidth: Dp, modifier: Modifier = Modifier) {
    when (loadState) {
        OpsLoadState.Loading -> Box(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            WorkSurface(
                title = "CI Results",
                detail = "Waiting for ops summary.",
                items = listOf("backend", "server_py", "arcana"),
            )
        }
        is OpsLoadState.Failed -> Box(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            WorkSurface(
                title = "CI Results Unavailable",
                detail = loadState.message,
                items = listOf(OPS_SUMMARY_PATH, "backend control plane", "dashboard API"),
            )
        }
        is OpsLoadState.Ready -> Column(modifier.fillMaxWidth()) {
            CiHeader(loadState.summary, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp))
            VerificationItems(loadState.summary, pageWidth)
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

@Composable
private fun VerificationItems(summary: OpsSummaryDto, pageWidth: Dp) {
    val repos = summary.repos
    // Three evidence stacks need roughly 400dp each; narrower windows reuse the existing mobile column.
    if (pageWidth < 1280.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repos.forEach { repo ->
                key(repo.id) {
                    CiRepoCard(
                        repo = repo,
                        generatedAtMs = summary.generatedAtMs,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repos.forEach { repo ->
                key(repo.id) {
                    CiRepoCard(repo, summary.generatedAtMs, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CiRepoCard(repo: RepoHealthDto, generatedAtMs: Long, modifier: Modifier = Modifier) {
    val runs = remember(repo) { repo.ciRuns() }
    val status = remember(repo) { repo.ciStatus() }
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
        TestClassificationPanel(repo, runs, generatedAtMs)
    }
}

@Composable
private fun TestClassificationPanel(repo: RepoHealthDto, runs: List<TestRunSummaryDto>, generatedAtMs: Long) {
    val status = remember(runs) { runs.map { it.status }.layerStatus() }
    var expandedRuns by remember { mutableStateOf(emptySet<String>()) }
    var artifacts by remember { mutableStateOf(emptyMap<String, TestArtifactState>()) }
    val shape = RoundedCornerShape(7.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF0D141B).copy(alpha = 0.72f))
            .border(BorderStroke(1.dp, status.color().copy(alpha = 0.24f)), shape)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(repo.classificationTitle(), modifier = Modifier.weight(1f), color = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            repo.latestRun?.coveragePct?.let { MiniMetric("coverage", it.percentLabel(), cyan) }
            MiniMetric(repo.classificationCountLabel(), "${runs.count { it.status == OpsStatusDto.OK }}/${runs.size}", status.color())
        }
        if (runs.isEmpty()) PlaceholderTile("test evidence unavailable")
        runs.forEach { run ->
            val runKey = run.classificationKey(repo.id)
            val expanded = runKey in expandedRuns
            val artifactUrl = run.artifactLocation(repo.id)
            val embedded = repo.embeddedArtifact(run)
            val artifactState = artifacts[runKey]
            LaunchedEffect(expanded, runKey, artifactUrl, embedded) {
                if (!expanded) return@LaunchedEffect
                if (embedded != null) {
                    artifacts = artifacts + (runKey to TestArtifactState(sourceUrl = artifactUrl, artifact = embedded))
                    return@LaunchedEffect
                }
                val fresh = artifactState?.let {
                    it.sourceUrl == artifactUrl && (it.loading || it.artifact != null || it.error != null)
                } == true
                if (fresh) return@LaunchedEffect
                if (artifactUrl == null) {
                    artifacts = artifacts + (runKey to TestArtifactState(error = "no artifact published"))
                    return@LaunchedEffect
                }
                artifacts = artifacts + (runKey to TestArtifactState(sourceUrl = artifactUrl, loading = true))
                loadOpsText(
                    path = artifactUrl,
                    onLoaded = { body ->
                        artifacts = artifacts + (runKey to runCatching {
                            TestArtifactState(sourceUrl = artifactUrl, artifact = dashboardJson.decodeFromString<TestArtifactDto>(body))
                        }.getOrElse { error ->
                            TestArtifactState(sourceUrl = artifactUrl, error = error.message ?: "artifact decode failed")
                        })
                    },
                    onFailed = { error -> artifacts = artifacts + (runKey to TestArtifactState(sourceUrl = artifactUrl, error = error)) },
                )
            }
            key(runKey) {
                TestClassificationTile(
                    repo = repo,
                    run = run,
                    generatedAtMs = generatedAtMs,
                    expanded = expanded,
                    artifactState = artifactState,
                    onToggle = { expandedRuns = if (expanded) expandedRuns - runKey else expandedRuns + runKey },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repo.latestRun?.timestampMs?.let { MiniMetric("last", it.relativeFrom(generatedAtMs), muted) }
            repo.latestRun?.detail?.substringAfter("@", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }?.let { MiniMetric("commit", it, muted) }
        }
    }
}

@Composable
private fun TestClassificationTile(
    repo: RepoHealthDto,
    run: TestRunSummaryDto,
    generatedAtMs: Long,
    expanded: Boolean,
    artifactState: TestArtifactState?,
    onToggle: () -> Unit,
) {
    val color = if (run.status == OpsStatusDto.UNKNOWN) muted else run.status.color()
    val result = run.detail.testCountLabel()
        ?: repo.selfTest?.takeIf { repo.id == "server_py" && run.isLiveE2E() }?.let { "${it.casePassCount}/${it.caseCount}" }
    val value = result ?: if (run.status == OpsStatusDto.UNKNOWN) "-" else run.status.name
    val shape = RoundedCornerShape(7.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF101821).copy(alpha = 0.44f))
            .border(BorderStroke(1.dp, color.copy(alpha = if (expanded) 0.50f else if (run.status == OpsStatusDto.UNKNOWN) 0.16f else 0.30f)), shape)
            .animateContentSize(animationSpec = tween(180, easing = FastOutSlowInEasing)),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 11.dp, vertical = 6.dp)
                .heightIn(min = 42.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(run.status)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(run.classificationLabel(repo.id), color = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                run.classificationSubtitle(repo.id)?.let {
                    Text(it, color = muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                run.timestampMs?.let { AgePill(it, generatedAtMs) }
                run.durationMs?.durationLabel()?.let { LayerMetric(it, muted) }
                run.coveragePct?.percentLabel()?.let { LayerMetric(it, cyan) }
            }
            Text(value, color = if (result == null) color else text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(160, easing = FastOutSlowInEasing)) + fadeIn(tween(120)),
            exit = shrinkVertically(tween(140, easing = FastOutSlowInEasing)) + fadeOut(tween(100)),
        ) {
            Box(Modifier.padding(start = 11.dp, end = 11.dp, bottom = 6.dp)) {
                TestArtifactExpanded(run, artifactState, repo.selfTest)
            }
        }
    }
}

@Composable
private fun TestArtifactExpanded(run: TestRunSummaryDto, state: TestArtifactState?, selfTest: SelfTestSummaryDto?) {
    val artifact = state?.artifact
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.14f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        when {
            state == null || state.loading -> ArtifactMessage("loading evidence...", muted)
            state.error != null -> ArtifactMessage("evidence unavailable: ${state.error}", OpsStatusDto.WARN.color())
            artifact == null -> ArtifactMessage("evidence unavailable", muted)
            artifact.cases.isEmpty() -> ArtifactMessage(artifact.summary ?: artifact.detail ?: "artifact has no cases", muted)
            else -> {
                val failures = artifact.cases.count { it.status == OpsStatusDto.FAIL }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniMetric("cases", "${artifact.cases.count { it.status == OpsStatusDto.OK }}/${artifact.cases.size}", if (failures == 0) green else OpsStatusDto.FAIL.color())
                    artifact.summary?.takeIf(String::isNotBlank)?.let {
                        Text(it, modifier = Modifier.weight(1f), color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } ?: Spacer(Modifier.weight(1f))
                    state.sourceUrl?.let { EvidenceLink("artifact", it) }
                    run.url?.takeIf { it != state.sourceUrl }?.let { EvidenceLink("source", it) }
                }
                if (artifact.kind == TestArtifactKindDto.MODEL_SELECTORS) {
                    ModelSelectorEvidence(artifact, selfTest)
                } else {
                    TestCaseGroups(artifact)
                }
                artifact.outputTail?.takeIf { failures > 0 && it.isNotBlank() }?.let { tail ->
                    Text(tail.takeLast(700), color = Color(0xFFC7D3E0), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TestCaseGroups(artifact: TestArtifactDto) {
    // Layer -> subsystem/module -> contract/scope -> case. Both inner levels stay collapsed so large suites remain cheap to inspect.
    val groups = remember(artifact) { artifact.cases.normalizedCases().evidenceGroups() }
    val umbrellas = remember(groups) { groups.evidenceUmbrellas() }
    var expandedUmbrellas by remember(artifact.sourceRevision, artifact.label, umbrellas.size) {
        mutableStateOf(emptySet<String>())
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp).nativeWheelRegion().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        umbrellas.forEach { umbrella ->
            val expanded = umbrella.key in expandedUmbrellas
            EvidenceUmbrellaCard(
                umbrella = umbrella,
                expanded = expanded,
                onToggle = {
                    expandedUmbrellas = if (expanded) expandedUmbrellas - umbrella.key else expandedUmbrellas + umbrella.key
                },
            )
        }
    }
}

@Composable
private fun EvidenceUmbrellaCard(umbrella: EvidenceUmbrella, expanded: Boolean, onToggle: () -> Unit) {
    var expandedGroups by remember(umbrella.key) { mutableStateOf(emptySet<String>()) }
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF0D141B).copy(alpha = 0.82f))
            .border(BorderStroke(1.dp, umbrella.status.color().copy(alpha = if (expanded) 0.40f else 0.20f)), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 9.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(umbrella.status)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HierarchyText(listOf(if (umbrella.ledgerBacked) "capability ledger" else "test module", umbrella.title))
                Text(umbrella.purpose, color = muted, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            umbrella.durationMs?.durationLabel()?.let { Text(it, color = muted, fontSize = 9.sp, maxLines = 1) }
            Text("${umbrella.passed}/${umbrella.total}", color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(140, easing = FastOutSlowInEasing)) + fadeIn(tween(100)),
            exit = shrinkVertically(tween(120, easing = FastOutSlowInEasing)) + fadeOut(tween(90)),
        ) {
            Box(Modifier.padding(start = 9.dp, end = 9.dp, bottom = 7.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    umbrella.groups.forEach { group ->
                        val groupExpanded = group.key in expandedGroups
                        EvidenceGroupCard(
                            group = group,
                            expanded = groupExpanded,
                            onToggle = {
                                expandedGroups = if (groupExpanded) expandedGroups - group.key else expandedGroups + group.key
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceGroupCard(group: EvidenceGroup, expanded: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF0D141B).copy(alpha = 0.78f))
            .border(BorderStroke(1.dp, group.status.color().copy(alpha = if (expanded) 0.38f else 0.18f)), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 9.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(group.status)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HierarchyText(group.hierarchy)
                group.meaning?.let { Text(it, color = muted, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            group.durationMs?.durationLabel()?.let { Text(it, color = muted, fontSize = 9.sp, maxLines = 1) }
            Text("${group.passed}/${group.cases.size}", color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(140, easing = FastOutSlowInEasing)) + fadeIn(tween(100)),
            exit = shrinkVertically(tween(120, easing = FastOutSlowInEasing)) + fadeOut(tween(90)),
        ) {
            Box(Modifier.padding(start = 9.dp, end = 9.dp, bottom = 7.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.cases.sortedWith(compareBy<TestCaseDto> { it.status.caseRank() }.thenBy { it.name }).forEach { TestCaseRow(it) }
                }
            }
        }
    }
}

@Composable
private fun TestCaseRow(case: TestCaseDto) {
    val baseName = case.name.substringBefore('[')
    val variant = case.name.substringAfter('[', missingDelimiterValue = "").removeSuffix("]").takeIf(String::isNotBlank)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
        StatusDot(case.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                baseName.humanIdentifier(),
                color = text,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            variant?.let { Text(it.replace("-", " · "), color = amber, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            case.detail?.takeIf(String::isNotBlank)?.let {
                Text(it, color = if (case.status == OpsStatusDto.OK) muted else Color(0xFFD3DCE8), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        case.durationMs?.durationLabel()?.let { Text(it, color = muted, fontSize = 9.sp, maxLines = 1) }
        case.url?.let { EvidenceLink("job", it) }
    }
}

@Composable
private fun ModelSelectorEvidence(artifact: TestArtifactDto, selfTest: SelfTestSummaryDto?) {
    val canaries = remember(artifact) { artifact.cases.filter { it.scope == "canary" } }
    val selectors = remember(artifact) { artifact.cases.filterNot { it.scope == "canary" } }
    val families = remember(selectors) { selectors.groupBy { it.name.substringBefore(" / ") } }
    val fields = listOfNotNull(
        selfTest?.let { FieldSpec("selectors", "${selectors.count { case -> case.status == OpsStatusDto.OK }}/${selectors.size}") },
        selfTest?.let { FieldSpec("total", it.latencyMs.durationLabel()) },
        selfTest?.let { FieldSpec("ask", it.askLatencyMs.durationLabel()) },
        selfTest?.let { FieldSpec("audit", it.auditLatencyMs.durationLabel()) },
    )
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 370.dp).nativeWheelRegion().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (fields.isNotEmpty()) FieldGrid(fields, compact = true)
        canaries.forEach { case -> SelectorFamilyCard("DeepSeek canary", listOf(case)) }
        families.entries.sortedBy { selectorFamilyOrder(it.key) }.forEach { (family, cases) -> SelectorFamilyCard(family, cases) }
    }
}

@Composable
private fun SelectorFamilyCard(family: String, cases: List<TestCaseDto>) {
    val status = cases.map { it.status }.layerStatus()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D141B).copy(alpha = 0.82f))
            .border(BorderStroke(1.dp, status.color().copy(alpha = 0.22f)), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status)
            Text(family, modifier = Modifier.weight(1f), color = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LayerMetric("${cases.count { it.status == OpsStatusDto.OK }}/${cases.size}", status.color())
        }
        cases.forEach { case ->
            val effort = case.name.substringAfter(" / ", missingDelimiterValue = case.name)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(effort, modifier = Modifier.weight(1f), color = text, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                case.observedPill()?.let { LayerMetric(it, cyan) }
                case.durationMs?.durationLabel()?.let { Text(it, color = muted, fontSize = 9.sp, maxLines = 1) }
            }
            case.detail?.takeIf { case.status != OpsStatusDto.OK && it.isNotBlank() }?.let {
                Text(it, color = case.status.color(), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HierarchyText(parts: List<String>) {
    val colors = listOf(green, cyan, amber, text)
    Text(
        buildAnnotatedString {
            parts.takeLast(5).forEachIndexed { index, part ->
                if (index > 0) withStyle(SpanStyle(color = muted)) { append("  ·  ") }
                withStyle(SpanStyle(color = colors[index.coerceAtMost(colors.lastIndex)])) { append(part) }
            }
        },
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EvidenceLink(label: String, url: String) {
    Text(label, modifier = Modifier.clickable { openOpsUrl(url) }, color = cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

@Composable
private fun ArtifactMessage(message: String, color: Color) {
    Text(message, color = color, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private data class TestArtifactState(
    val sourceUrl: String? = null,
    val loading: Boolean = false,
    val artifact: TestArtifactDto? = null,
    val error: String? = null,
)

private data class EvidenceGroup(
    val key: String,
    val hierarchy: List<String>,
    val meaning: String?,
    val contract: TestContractRefDto?,
    val cases: List<TestCaseDto>,
) {
    val status = cases.map { it.status }.layerStatus()
    val passed = cases.count { it.status == OpsStatusDto.OK }
    val durationMs = cases.mapNotNull { it.durationMs }.takeIf(List<Double>::isNotEmpty)?.sum()
}

private data class EvidenceUmbrella(
    val key: String,
    val title: String,
    val purpose: String,
    val ledgerBacked: Boolean,
    val groups: List<EvidenceGroup>,
) {
    // A case may satisfy several contracts in one subsystem; umbrella totals count the execution once.
    val cases = groups.flatMap { it.cases }.distinctBy { it.scope to it.name }
    val status = cases.map { it.status }.layerStatus()
    val passed = cases.count { it.status == OpsStatusDto.OK }
    val total = cases.size
    val durationMs = cases.mapNotNull { it.durationMs }.takeIf(List<Double>::isNotEmpty)?.sum()
}

private fun List<TestCaseDto>.normalizedCases() = map { case ->
    if (case.scope == null && "::" in case.name) case.copy(scope = case.name.substringBefore("::"), name = case.name.substringAfter("::")) else case
}

private fun List<TestCaseDto>.evidenceGroups(): List<EvidenceGroup> = flatMap { case ->
    case.contracts
        .map { contract -> Triple(contract.id, contract, case) }
        .ifEmpty { listOf(Triple(case.scope.orEmpty().ifBlank { "unscoped" }, null, case)) }
}.groupBy { it.first }.map { (key, evidence) ->
    val contract = evidence.firstNotNullOfOrNull { it.second }
    val cases = evidence.map { it.third }
    val scopeParts = key.replace('/', '.').removeSuffix(".py").split('.').filter(String::isNotBlank)
    EvidenceGroup(
        key = key,
        hierarchy = contract?.id?.split('.')?.map(String::humanIdentifier)
            ?: scopeParts.dropWhile { it in setOf("z_tests_n_benchmarks", "net", "sdfgsdfg") }.map(String::humanIdentifier),
        meaning = contract?.capability,
        contract = contract,
        cases = cases,
    )
}.sortedWith(compareBy<EvidenceGroup> { it.status.caseRank() }.thenBy { it.key })

private fun List<EvidenceGroup>.evidenceUmbrellas(): List<EvidenceUmbrella> = groupBy { group ->
    group.contract?.let { contract -> "ledger:${contract.subsystem ?: contract.id.split('.').take(2).joinToString(".")}" }
        ?: "module:${group.moduleFamily()}"
}.map { (key, groups) ->
    val contract = groups.firstNotNullOfOrNull(EvidenceGroup::contract)
    val family = groups.first().moduleFamily()
    EvidenceUmbrella(
        key = key,
        title = contract?.subsystemName
            ?: contract?.subsystem?.humanIdentifier()
            ?: contract?.id?.split('.')?.getOrNull(1)?.humanIdentifier()?.let { "$it contracts" }
            ?: family.humanIdentifier(),
        purpose = contract?.subsystemPurpose
            ?: "Ledger contracts · legacy subsystem taxonomy.".takeIf { contract != null }
            ?: "JUnit scope · not ledger-mapped.",
        ledgerBacked = contract != null,
        groups = groups,
    )
}.sortedWith(compareBy<EvidenceUmbrella> { it.status.caseRank() }.thenByDescending { it.ledgerBacked }.thenBy { it.title })

private fun EvidenceGroup.moduleFamily(): String {
    val roots = setOf("z_tests_n_benchmarks", "unit", "integration", "e2e", "benchmarks", "net", "sdfgsdfg", "tests")
    return key.replace('/', '.').removeSuffix(".py").split('.')
        .firstOrNull { it.isNotBlank() && it !in roots }
        ?.removePrefix("0_")
        ?: "unscoped"
}

private fun RepoHealthDto.embeddedArtifact(run: TestRunSummaryDto): TestArtifactDto? =
    selfTest?.takeIf { id == "server_py" && run.isLiveE2E() }?.let { summary ->
        TestArtifactDto(
            label = "live e2e",
            status = summary.status,
            timestampMs = summary.timestampMs,
            durationMs = summary.latencyMs,
            detail = summary.rawError,
            url = summary.workflowUrl,
            kind = TestArtifactKindDto.MODEL_SELECTORS,
            summary = "${summary.casePassCount}/${summary.caseCount} checks passed",
            cases = summary.cases.map { case ->
                TestCaseDto(
                    name = case.name,
                    status = case.status,
                    scope = if (case.name.startsWith("DeepSeek")) "canary" else "model selectors",
                    durationMs = case.latencyMs,
                    detail = case.note,
                )
            },
        )
    }

private fun TestRunSummaryDto.artifactLocation(repoId: String): String? = artifactUrl ?: when {
    repoId == "backend" && (label.startsWith("deploy ") || label == "deploy gate") -> "/api/ops/artifacts/backend-deploy.json"
    repoId == "backend" && label == "unit tests" -> "/api/ops/artifacts/backend-unit.json"
    repoId == "backend" && label == "full suite" -> "/api/ops/artifacts/backend-full-suite.json"
    repoId == "server_py" && label == "unit tests" -> "/api/ops/artifacts/server-py-unit.json"
    repoId == "server_py" && isLiveE2E() -> "/api/ops/artifacts/server-py-live-e2e.json"
    repoId == "arcana" -> url
    url?.startsWith("/api/ops/artifacts/") == true -> url
    else -> null
}

private fun TestRunSummaryDto.isLiveE2E() = label in setOf("live e2e", "live e2e selftest", "live selftest")

// Selector audits may traverse a neighbouring effort first; the final observed pill is the asserted destination.
private fun TestCaseDto.observedPill(): String? = detail?.let { note ->
    Regex("""pill=([^;]+)""").findAll(note).lastOrNull()?.groupValues?.getOrNull(1)?.trim()
}

private fun selectorFamilyOrder(family: String) = listOf("GPT-5.6 Sol", "GPT-5.5", "GPT-5.4", "GPT-5.3", "o3")
    .indexOf(family).takeIf { it >= 0 } ?: Int.MAX_VALUE

private fun String.humanIdentifier(): String = removePrefix("test_")
    .replace('_', ' ')
    .replace('-', ' ')
    .split(' ')
    .filter(String::isNotBlank)
    .joinToString(" ") { token ->
        when (token.lowercase()) {
            "api", "cli", "e2e", "grpc", "http", "rpc", "ui", "uds", "json" -> token.uppercase()
            else -> token.replaceFirstChar(Char::uppercase)
        }
    }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.nativeWheelRegion(): Modifier {
    val onChanged = LocalNativeWheelRegionChanged.current ?: return this
    val hovered = remember(onChanged) { booleanArrayOf(false) }
    DisposableEffect(onChanged) {
        onDispose { if (hovered[0]) onChanged(false) }
    }
    return onPointerEvent(PointerEventType.Enter) {
        if (!hovered[0]) {
            hovered[0] = true
            onChanged(true)
        }
    }.onPointerEvent(PointerEventType.Exit) {
        if (hovered[0]) {
            hovered[0] = false
            onChanged(false)
        }
    }
}

@Composable
private fun LayerMetric(value: String, color: Color) {
    Text(
        value,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.08f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.18f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

private fun List<OpsStatusDto>.layerStatus() = when {
    OpsStatusDto.FAIL in this -> OpsStatusDto.FAIL
    OpsStatusDto.WARN in this -> OpsStatusDto.WARN
    OpsStatusDto.WIP in this -> OpsStatusDto.WIP
    OpsStatusDto.OK in this -> OpsStatusDto.OK
    else -> OpsStatusDto.UNKNOWN
}

private fun OpsStatusDto.caseRank() = when (this) {
    OpsStatusDto.FAIL -> 0
    OpsStatusDto.WARN -> 1
    OpsStatusDto.WIP -> 2
    OpsStatusDto.UNKNOWN -> 3
    OpsStatusDto.OK -> 4
}

@Composable
private fun MiniMetric(name: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.24f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name.uppercase(), color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(value, color = text, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
    var renderedEvents by remember { mutableStateOf(visibleEvents) }
    var exitingKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(visibleEvents) {
        val activeKeys = eventKeys.toSet()
        val nextExitingKeys = (exitingKeys + renderedEvents.mapNotNull { event -> event.key.takeIf { it !in activeKeys } }) - activeKeys
        val nextEvents = visibleEvents.toMutableList()
        renderedEvents.forEachIndexed { index, event ->
            if (event.key in nextExitingKeys && nextEvents.none { it.key == event.key }) {
                nextEvents.add(index.coerceAtMost(nextEvents.size), event)
            }
        }
        exitingKeys = nextExitingKeys
        renderedEvents = nextEvents
    }
    LaunchedEffect(exitingKeys) {
        if (exitingKeys.isNotEmpty()) {
            val settling = exitingKeys
            delay(ciHistoryExitMs.toLong())
            exitingKeys -= settling
            renderedEvents = renderedEvents.filter { it.key !in settling }
        }
    }
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
        visibleEvents = renderedEvents,
        visibleCount = visibleEvents.size,
        total = events.size,
        freshKeys = freshKeys,
        enterKeys = enterKeys,
        exitingKeys = exitingKeys,
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
    val visibleCount: Int,
    val total: Int,
    val freshKeys: Set<String>,
    val enterKeys: Set<String>,
    val exitingKeys: Set<String>,
    val onToggleRepo: (String) -> Unit,
)

internal data class CiHistoryEvent(
    val repo: RepoHealthDto,
    val run: TestRunSummaryDto,
    val key: String,
)

internal fun LazyListScope.ciHistoryItems(state: CiHistoryState, generatedAtMs: Long) {
    // Viewport churn stays native-fast; true run births/removals animate below.
    val placementSpec = spring<IntOffset>(
        dampingRatio = 0.90f,
        stiffness = 520f,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )
    item(key = "ci-history-header") {
        RunHistoryHeader(state, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
    }
    item(key = "ci-history-empty") {
        AnimatedVisibility(
            visible = state.visibleEvents.isEmpty(),
            modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = placementSpec, fadeOutSpec = null).fillMaxWidth(),
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
        val entering = event.key in state.enterKeys
        val exiting = event.key in state.exitingKeys
        val visibleState = remember(event.key) {
            MutableTransitionState(!entering && !exiting)
        }
        visibleState.targetState = !exiting
        AnimatedVisibility(
            visibleState = visibleState,
            modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = placementSpec, fadeOutSpec = null).fillMaxWidth(),
            enter = fadeIn(tween(260, easing = FastOutSlowInEasing)) +
                scaleIn(spring(dampingRatio = 0.82f, stiffness = 360f), initialScale = 0.976f, transformOrigin = TransformOrigin(0.5f, 0f)) +
                expandVertically(tween(720, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                slideInVertically(tween(720, easing = FastOutSlowInEasing)) { -it / 3 },
            exit = fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                scaleOut(tween(260, easing = FastOutSlowInEasing), targetScale = 0.965f, transformOrigin = TransformOrigin(0.5f, 0f)) +
                slideOutVertically(tween(ciHistoryExitMs, easing = FastOutSlowInEasing)) { -it / 10 } +
                shrinkVertically(tween(ciHistoryExitMs, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
        ) {
            RunHistoryRow(
                repo = event.repo,
                run = event.run,
                generatedAtMs = generatedAtMs,
                fresh = event.key in state.freshKeys,
                entering = entering,
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
            StatusPill("${state.visibleCount}/${state.total} shown", green)
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
private const val ciHistoryExitMs = 300

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
private fun RunHistoryRow(repo: RepoHealthDto, run: TestRunSummaryDto, generatedAtMs: Long, fresh: Boolean = false, entering: Boolean = false, modifier: Modifier = Modifier) {
    val flash by animateFloatAsState(
        targetValue = if (fresh) 1f else 0f,
        animationSpec = tween(if (fresh) 180 else 1100, easing = FastOutSlowInEasing),
        label = "run-history-flash",
    )
    val reveal by animateFloatAsState(
        targetValue = if (entering) 1f else 0f,
        animationSpec = tween(if (entering) 160 else 900, easing = FastOutSlowInEasing),
        label = "run-history-reveal",
    )
    val running = run.status == OpsStatusDto.WIP
    val color = run.status.color()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0D141B))
            .background(color.copy(alpha = flash * 0.11f + reveal * 0.045f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.24f + flash * 0.32f + reveal * 0.18f)), RoundedCornerShape(7.dp))
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
                AnimatedVisibility(
                    visible = fresh,
                    enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.86f),
                    exit = fadeOut(tween(320, easing = FastOutSlowInEasing)),
                ) {
                    UpdatePill(color)
                }
                if (running) UpdatePill(color, "running")
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
    "arcana" -> "Unit · Integration · E2E · Benchmarks"
    else -> role
}

private fun RepoHealthDto.classificationTitle() = when (id) {
    "backend" -> "Verification Stack"
    "server_py" -> "Test Stack"
    "arcana" -> "Test Pyramid"
    else -> "Test Evidence"
}

private fun RepoHealthDto.classificationCountLabel() = if (id == "arcana") "layers" else "checks"

private fun String.displayRepoName() = if (this == "server_py") "server_py" else replaceFirstChar { it.uppercase() }

private fun TestRunSummaryDto.ciKey(repo: RepoHealthDto): String {
    url?.let { return "${repo.id}:url:$it" }
    timestampMs?.let { return "${repo.id}:time:$label:$it" }
    return "${repo.id}:live:$label"
}

private fun TestRunSummaryDto.classificationKey(repoId: String) = "$repoId:${arcanaLayerKey() ?: when {
    label.startsWith("deploy ") -> "deploy-gate"
    isLiveE2E() -> "live-e2e"
    else -> label
}}"

private fun TestRunSummaryDto.classificationLabel(repoId: String) = when {
    repoId == "arcana" -> arcanaLayerLabel() ?: label.humanIdentifier()
    label.startsWith("deploy ") || label == "deploy gate" || label == "local preview" -> "Deploy Gate"
    label == "unit tests" -> "Unit Tests"
    label == "full suite" -> "Full Suite Testchain"
    isLiveE2E() -> "Live E2E"
    else -> label.humanIdentifier()
}

private fun TestRunSummaryDto.classificationSubtitle(repoId: String) = when {
    repoId == "arcana" && status == OpsStatusDto.UNKNOWN -> "pending"
    repoId == "arcana" -> when (arcanaLayerKey()) {
        "unit" -> "deterministic contracts"
        "integration" -> "boundary and replay contracts"
        "e2e" -> "live canaries"
        "benchmarks" -> "capability scoring"
        else -> null
    }
    label.startsWith("deploy ") -> label
    label == "unit tests" && repoId == "backend" -> "core + server contracts"
    label == "unit tests" -> "pytest contracts"
    label == "full suite" -> "weekly GitHub umbrella"
    isLiveE2E() -> "browser canary + model selectors"
    else -> null
}

private fun String.historyColor() = when (this) {
    "backend" -> green
    "server_py" -> cyan
    "arcana" -> amber
    else -> muted
}

private val passedCountRegex = Regex("""(\d+)\s+passed""")
private val failedCountRegex = Regex("""(\d+)\s+failed""")
private val errorCountRegex = Regex("""(\d+)\s+errors?""")
private val skippedCountRegex = Regex("""(\d+)\s+skipped""")
private fun String?.testCountLabel(): String? {
    val text = this ?: return null
    val passed = passedCountRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val failed = failedCountRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val errors = errorCountRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val skipped = skippedCountRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val total = passed + failed + errors + skipped
    if (total == 0) return null
    return "$passed/$total"
}

private fun RepoHealthDto.ciStatus(): OpsStatusDto {
    val statuses = ciRuns().filterNot { it.label == "full suite" }.map { it.status }
        .ifEmpty { listOf(if (id == "server_py") selfTest?.status ?: OpsStatusDto.UNKNOWN else latestRun?.status ?: OpsStatusDto.UNKNOWN) }
    return when {
        OpsStatusDto.FAIL in statuses -> OpsStatusDto.FAIL
        OpsStatusDto.WARN in statuses -> OpsStatusDto.WARN
        OpsStatusDto.WIP in statuses -> OpsStatusDto.WIP
        OpsStatusDto.OK in statuses -> OpsStatusDto.OK
        OpsStatusDto.UNKNOWN in statuses -> OpsStatusDto.UNKNOWN
        else -> OpsStatusDto.OK
    }
}

private fun RepoHealthDto.ciRuns(): List<TestRunSummaryDto> = when (id) {
    "backend" -> listOfNotNull(latestRun) + runs.filter { it.label in setOf("unit tests", "full suite") }
    "server_py" -> runs.filter { it.label == "unit tests" || it.isLiveE2E() }
    "arcana" -> arcanaLayerRuns(fillMissing = true)
    else -> runs.ifEmpty { listOfNotNull(latestRun) }
}.distinctBy { it.label to it.timestampMs }

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
