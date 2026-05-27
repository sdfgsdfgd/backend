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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

@Composable
internal fun Home(loadState: OpsLoadState) {
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
                repo.runtimeBadges().forEach { PanelBadge(it) }
                Text(
                    repo.role,
                    modifier = Modifier.weight(1f),
                    color = muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                repo.statusBadge()?.let { PanelBadge(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PanelBadge(repo.issues.badgeSpec())
                repo.testBadges().forEach { PanelBadge(it) }
            }
        }
    }
    if (repo.id != "backend" && repo.id != "server_py") repo.latestRun?.let { RunSignal(it, generatedAtMs) }
    val visibleSignals = if (repo.id == "server_py") repo.signals.filterNot { it.label == "transport" } else repo.signals
    visibleSignals.takeIf { it.isNotEmpty() }?.let {
        if (repo.id == "arcana") ArcanaSignalStack(it, generatedAtMs) else SignalStack(it, generatedAtMs)
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
    val summary = signals.firstOrNull { it.isActiveProcessSummary() }
    val processRows = signals.filterNot { it.isActiveProcessSummary() }
    val processKeys = processRows.map { "${it.label}-${it.meta}-${it.timestampMs}-${it.detail}" }
    val freshKeys = rememberFreshKeys(processKeys)
    var expanded by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.animateContentSize(animationSpec = tween(320, easing = FastOutSlowInEasing)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        summary?.let {
            ActiveSignalRow(
                signal = it,
                generatedAtMs = generatedAtMs,
                expanded = expanded,
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
private fun ActiveSignalRow(signal: OpsSignalDto, generatedAtMs: Long, expanded: Boolean, onClick: () -> Unit) {
    val groups = signal.activeProcessGroups()
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
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(host, color = muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            badges.forEach { PanelBadge(it) }
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
