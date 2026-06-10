package net.sdfgsdfg.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsSignalDto
import net.sdfgsdfg.data.model.RepoHealthDto
import net.sdfgsdfg.data.model.TestRunSummaryDto

internal fun LazyListScope.homeItems(loadState: OpsLoadState, pageWidth: Dp) {
    when (loadState) {
        OpsLoadState.Loading -> item(key = "home-loading") {
            LoadingPanel(pageWidth, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }
        is OpsLoadState.Failed -> item(key = "home-failed") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                WorkSurface(
                    title = "Ops Summary Unavailable",
                    detail = loadState.message,
                    items = listOf("/api/ops/summary", "backend service", "local preview route"),
                )
            }
        }
        is OpsLoadState.Ready -> {
            item(key = "home-summary") {
                SummaryStrip(loadState.summary, pageWidth, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
            }
            repoItems(loadState.summary.repos, loadState.summary.generatedAtMs, pageWidth)
        }
    }
}

@Composable
private fun SummaryStrip(summary: OpsSummaryDto, pageWidth: Dp, modifier: Modifier = Modifier) {
    val repos = summary.repos
    val metrics = remember(repos) {
        val ok = repos.count { it.status == OpsStatusDto.OK }
        val activeIssues = repos.sumOf { it.issues.active }
        val alerts = repos.count { it.status in setOf(OpsStatusDto.WARN, OpsStatusDto.FAIL, OpsStatusDto.UNKNOWN) }
        val wip = repos.count { it.status == OpsStatusDto.WIP }
        listOf(
            FieldSpec("repos", summary.repos.size.toString()),
            FieldSpec("healthy", ok.toString()),
            FieldSpec("alerts", alerts.toString()),
            FieldSpec("wip", wip.toString()),
            FieldSpec("active issues", activeIssues.toString(), issueSourceBreakdown(repos)),
        )
    }
    if (pageWidth < 760.dp) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            metrics.forEach { metric ->
                key(metric.name) { MetricCard(metric) }
            }
        }
    } else {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.forEach { metric ->
                key(metric.name) {
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(metric)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.repoItems(repos: List<RepoHealthDto>, generatedAtMs: Long, pageWidth: Dp) {
    val stacked = pageWidth < 980.dp
    if (stacked) {
        itemsIndexed(repos, key = { _, repo -> "home-repo-${repo.id}" }) { index, repo ->
            val bottom = if (index == repos.lastIndex) 0.dp else 10.dp
            RepoCard(
                repo = repo,
                generatedAtMs = generatedAtMs,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = bottom),
            )
        }
    } else {
        item(key = "home-repo-grid") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repos.forEach { repo ->
                    key(repo.id) {
                        RepoCard(repo, generatedAtMs, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoCard(repo: RepoHealthDto, generatedAtMs: Long, modifier: Modifier = Modifier) {
    val status = repo.homeStatus()
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .glassSurface(shape, status.color(), glowAlpha = 0.11f, borderAlpha = 0.42f)
            .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RepoCardContent(repo, generatedAtMs, status)
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
private fun RepoCardContent(repo: RepoHealthDto, generatedAtMs: Long, status: OpsStatusDto) {
    val runtimeBadges = remember(repo) { repo.runtimeBadges() }
    val testBadges = remember(repo) { repo.testBadges() }
    val visibleSignals = remember(repo) {
        if (repo.id == "server_py") repo.signals.filterNot { it.label == "transport" } else repo.signals
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(repo.name, color = text, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                runtimeBadges.forEach { badge ->
                    key(badge.label) { PanelBadge(badge) }
                }
                Text(
                    repo.role,
                    modifier = Modifier.weight(1f),
                    color = muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (repo.id != "arcana") PanelBadge(BadgeSpec(status.name, status.color(), strong = true))
            }
            if (testBadges.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                testBadges.forEach { badge ->
                    key(badge.label) { PanelBadge(badge) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PanelBadge(repo.issues.badgeSpec())
            }
        }
    }
    visibleSignals.takeIf { it.isNotEmpty() }?.let {
        if (repo.id == "arcana") ArcanaSignalStack(it, generatedAtMs) else SignalStack(it, generatedAtMs)
    }
}

private fun RepoHealthDto.homeStatus() = (listOf(runtimeStatus()) + testStatuses()).maxBy { it.homeSeverity() }

private fun RepoHealthDto.runtimeStatus() = when (id) {
    "backend" -> signals.map { it.status }.ifEmpty { listOf(OpsStatusDto.OK) }.maxBy { it.homeSeverity() }
    "server_py", "arcana" -> signals.map { it.status }.ifEmpty { listOf(OpsStatusDto.UNKNOWN) }.maxBy { it.homeSeverity() }
    else -> status
}

private fun RepoHealthDto.testStatuses(): List<OpsStatusDto> = when (id) {
    "backend" -> listOfNotNull(
        runs.firstOrNull { it.label == "unit tests" }?.status,
        runs.firstOrNull { it.label == "full suite" }?.status,
    )
    "server_py" -> listOfNotNull(
        runs.firstOrNull { it.label == "unit tests" }?.status,
        runs.firstOrNull { it.label == "live e2e selftest" }?.status ?: selfTest?.status,
    )
    "arcana" -> listOfNotNull(
        (latestRun?.takeIf { it.isArcanaTestRun() } ?: runs.firstOrNull { it.isArcanaTestRun() })?.status,
    )
    else -> emptyList()
}

private fun OpsStatusDto.homeSeverity() = when (this) {
    OpsStatusDto.FAIL -> 5
    OpsStatusDto.WARN -> 4
    OpsStatusDto.WIP -> 3
    OpsStatusDto.OK -> 2
    OpsStatusDto.UNKNOWN -> 1
}

@Composable
private fun SignalStack(signals: List<OpsSignalDto>, generatedAtMs: Long) {
    Column(
        modifier = Modifier.animateContentSize(animationSpec = tween(260, easing = FastOutSlowInEasing)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        signals.forEach { signal ->
            key(signal.signalKey()) { SignalRow(signal, generatedAtMs) }
        }
    }
}

@Composable
private fun ArcanaSignalStack(signals: List<OpsSignalDto>, generatedAtMs: Long) {
    val summary = remember(signals) { signals.firstOrNull { it.isActiveProcessSummary() } }
    val processRows = remember(signals) { signals.filterNot { it.isActiveProcessSummary() } }
    val processKeys = remember(processRows) { processRows.map { it.signalKey() } }
    val freshKeys = rememberFreshKeys(processKeys)
    var expanded by remember { mutableStateOf(readDashboardPref("ops.home.activeExpanded")?.toBooleanStrictOrNull() ?: true) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        summary?.let {
            ActiveSignalRow(
                signal = it,
                generatedAtMs = generatedAtMs,
                expanded = expanded,
                onClick = {
                    val next = !expanded
                    expanded = next
                    writeDashboardPref("ops.home.activeExpanded", next.toString())
                },
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun ActiveSignalRow(signal: OpsSignalDto, generatedAtMs: Long, expanded: Boolean, onClick: () -> Unit) {
    val groups = remember(signal.detail, signal.meta) { signal.activeProcessGroups() }
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.038f))
            .border(BorderStroke(1.dp, signal.status.color().copy(alpha = 0.18f)), shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FreshRail(signal.timestampMs, generatedAtMs, height = if (groups.size > 1) 48.dp else 34.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Active", color = text, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (expanded) "hide" else "show", color = cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (groups.isEmpty()) {
                Text("no live arcana or codex processes", color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.forEach { (host, badges) ->
                        key(host) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(host, color = muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                badges.forEach { badge ->
                                    key(badge.label) { PanelBadge(badge) }
                                }
                            }
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
    val command = remember(signal.label, signal.detail) {
        signal.detail?.takeIf { signal.label == "arcana" }?.arcanaCommandParts()
    }
    val expandable = remember(command, signal.detail) {
        command != null || (signal.detail?.let { it.length > 130 || "\n" in it } == true)
    }
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
                    val knobRows = remember(knobs) { knobs.chunked(3) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        knobRows.forEach { row ->
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

private fun OpsSignalDto.activeProcessGroups(): List<Pair<String, List<BadgeSpec>>> = detail
    .orEmpty()
    .split(" / ")
    .mapNotNull { segment ->
        val host = segment.substringBefore(": ", missingDelimiterValue = meta.orEmpty())
            .ifBlank { meta.orEmpty().ifBlank { "local" } }
        val body = segment.substringAfter(": ", segment)
        val badges = liveProcessCount.findAll(body).mapNotNull { match ->
            val count = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val kind = match.groupValues[2]
            count.takeIf { it > 0 }?.let { BadgeSpec("$kind $it", if (kind == "arcana") green else cyan, strong = true) }
        }.toList()
        badges.takeIf { it.isNotEmpty() }?.let { host to it }
    }

private fun OpsSignalDto.signalKey() = "$label-$meta-$timestampMs-$detail"

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
private fun LoadingPanel(pageWidth: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loading-skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "loading-skeleton-pulse",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LoadingHero(pulse)
        LoadingRepoGrid(pulse, pageWidth)
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
private fun LoadingRepoGrid(pulse: Float, pageWidth: Dp) {
    val repos = remember { listOf("backend", "server_py", "arcana") }
    if (pageWidth < 980.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repos.forEachIndexed { index, repo ->
                key(repo) {
                    LoadingRepoCard(repo, pulse = (pulse + index * 0.12f).coerceAtMost(1f), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repos.forEachIndexed { index, repo ->
                key(repo) {
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
