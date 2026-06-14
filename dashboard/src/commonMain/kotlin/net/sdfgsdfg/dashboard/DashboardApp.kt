package net.sdfgsdfg.dashboard

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import net.sdfgsdfg.data.model.OpsIssuePatchDto
import net.sdfgsdfg.data.model.OpsRunEventDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.dashboard.tools.issueFrameTrace

internal enum class DashboardTab(val label: String) {
    Home("Home"),
    Ci("CI Results"),
    Issues("Issues"),
    Arcana("Arcana Sessions");

    companion object {
        fun fromStoredName(value: String) = if (value == "IssuesNew") Issues else entries.firstOrNull { it.name == value }
    }
}

private fun OpsSummaryDto.issueTraceShape() =
    "repos=${repos.joinToString { "${it.id}:${it.issues.active}/${it.issues.items.size}" }}"

private fun String.runLifecycleLabel() = when {
    startsWith("deploy ") -> "deploy"
    this == "live e2e selftest" || this == "live selftest" -> "live selftest"
    else -> this
}

internal sealed interface OpsLoadState {
    data object Loading : OpsLoadState
    data class Ready(val summary: OpsSummaryDto) : OpsLoadState
    data class Failed(val message: String) : OpsLoadState
}

@Composable
fun DashboardApp(
    arrowShiftSignal: Int = 0,
    focusedArrowKeys: Boolean = true,
    // CMP-10297 workaround: raw WheelEvent deltas from wasmJsMain.
    externalScrollDeltas: ReceiveChannel<Float>? = null,
) {
    var selectedTab by remember { mutableStateOf(readDashboardPref("ops.tab")?.let(DashboardTab::fromStoredName) ?: DashboardTab.Home) }
    var loadState by remember { mutableStateOf<OpsLoadState>(OpsLoadState.Loading) }
    var socketState by remember { mutableStateOf(OpsSocketState()) }
    var activeRunEvents by remember { mutableStateOf(emptyList<OpsRunEventDto>()) }
    var issueEditorActive by remember { mutableStateOf(false) }
    var lastAppliedSummary by remember { mutableStateOf<OpsSummaryDto?>(null) }
    val focusRequester = remember { FocusRequester() }
    val mounted = remember { booleanArrayOf(true) }
    var handledArrowShiftSignal by remember { mutableStateOf(arrowShiftSignal) }
    val issueEditorActiveState = rememberUpdatedState(issueEditorActive)

    fun applySummary(summary: OpsSummaryDto, source: String = "summary") {
        val previous = lastAppliedSummary
        if (
            previous != null &&
            previous.repos == summary.repos &&
            summary.generatedAtMs - previous.generatedAtMs in -1_000L..1_000L
        ) {
            issueFrameTrace("summary-skip") { "source=$source generatedAt=${summary.generatedAtMs} ${summary.issueTraceShape()}" }
            return
        }
        activeRunEvents = activeRunEvents.filterNot { event ->
            val activeLabel = event.run.label.runLifecycleLabel()
            summary.repos.firstOrNull { it.id == event.repoId }
                ?.let { repo ->
                    (repo.history + repo.runs + listOfNotNull(repo.latestRun)).any { run ->
                        run.status != OpsStatusDto.WIP &&
                            (run.label == event.run.label || run.label.runLifecycleLabel() == activeLabel) &&
                            (event.run.timestampMs == null || (run.timestampMs ?: 0L) >= event.run.timestampMs!!)
                    }
                } == true
        }
        lastAppliedSummary = summary
        loadState = OpsLoadState.Ready(summary)
        issueFrameTrace("summary-apply") { "source=$source generatedAt=${summary.generatedAtMs} ${summary.issueTraceShape()}" }
    }

    fun applyIssuePatch(patch: OpsIssuePatchDto, source: String = "issue-patch") {
        val previous = lastAppliedSummary ?: return
        val issuesByRepo = patch.repos.associateBy { it.id }
        issueFrameTrace("patch-apply") { "source=$source generatedAt=${patch.generatedAtMs} repos=${patch.repos.joinToString { "${it.id}:${it.issues.active}/${it.issues.items.size}" }}" }
        applySummary(
            previous.copy(
                generatedAtMs = patch.generatedAtMs,
                repos = previous.repos.map { repo ->
                    issuesByRepo[repo.id]?.let { repo.copy(issues = it.issues.mergeIssuePatch(repo.issues, patch.generatedAtMs)) } ?: repo
                },
            ),
            source = "$source->merge",
        )
    }

    DisposableEffect(Unit) {
        onDispose { mounted[0] = false }
    }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    DisposableEffect(Unit) {
        val close = connectOpsSocket(
            onMessage = { message ->
                if (!mounted[0]) return@connectOpsSocket
                message.runEvent?.let { event ->
                    val key = "${event.repoId}:${event.run.label.runLifecycleLabel()}"
                    activeRunEvents = (activeRunEvents.filterNot { "${it.repoId}:${it.run.label.runLifecycleLabel()}" == key } + event).takeLast(20)
                }
                val summary = message.summary
                val issuePatch = message.issuePatch
                when {
                    summary != null -> applySummary(summary, "socket-summary")
                    issuePatch != null -> applyIssuePatch(issuePatch, "socket-issue-patch")
                }
            },
            onState = { if (mounted[0]) socketState = it },
        )
        onDispose { close() }
    }
    LaunchedEffect(socketState.status) {
        if (socketState.status != OpsSocketStatus.DISCONNECTED) return@LaunchedEffect
        while (true) {
            loadOpsSummary(
                onLoaded = { if (mounted[0]) applySummary(it, "poll-summary") },
                onFailed = { if (mounted[0]) loadState = OpsLoadState.Failed(it.ifBlank { "Failed to load ops summary" }) },
            )
            delay(OPS_SUMMARY_REFRESH_MS)
        }
    }
    LaunchedEffect(arrowShiftSignal) {
        val shift = arrowShiftSignal - handledArrowShiftSignal
        if (shift != 0 && !issueEditorActive) selectedTab = selectedTab.shift(shift).also { writeDashboardPref("ops.tab", it.name) }
        handledArrowShiftSignal = arrowShiftSignal
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab != DashboardTab.Issues) issueEditorActive = false
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
                    if (issueEditorActive) return@onPreviewKeyEvent false
                    when (it.key) {
                        Key.DirectionLeft -> { selectedTab = selectedTab.shift(-1); writeDashboardPref("ops.tab", selectedTab.name); true }
                        Key.DirectionRight -> { selectedTab = selectedTab.shift(1); writeDashboardPref("ops.tab", selectedTab.name); true }
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
                val pageWidth = maxWidth
                OpsWallpaper()
                val listState = rememberLazyListState()
                //region CMP-10297 scroll workaround
                // TODO(CMP-10297): remove externalScrollDeltas, scrollBy, and the
                // wasmJsMain wheel bridge when Compose/Wasm preserves Chrome/macOS
                // precision-trackpad fling deltas internally.
                // https://youtrack.jetbrains.com/issue/CMP-10297
                LaunchedEffect(externalScrollDeltas, listState) {
                    val deltas = externalScrollDeltas ?: return@LaunchedEffect
                    while (true) {
                        var pending = deltas.receiveCatching().getOrNull() ?: break
                        while (true) {
                            pending += deltas.tryReceive().getOrNull() ?: break
                        }
                        if (pending != 0f && !issueEditorActiveState.value) listState.scrollBy(pending)
                        withFrameNanos { }
                    }
                }
                //endregion
                val ciSummary = if (selectedTab == DashboardTab.Ci) (loadState as? OpsLoadState.Ready)?.summary else null
                val ciHistoryState = rememberCiHistoryState(ciSummary, activeRunEvents, atPageBottom = !listState.canScrollForward)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(bottom = pageBottomGutter),
                ) {
                    item {
                        Header(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it; writeDashboardPref("ops.tab", it.name) },
                            socketState = socketState,
                        )
                        if (loadState is OpsLoadState.Loading) {
                            TopLoadTrace()
                        }
                    }
                    item(key = "tab-content-top") {
                        Spacer(Modifier.height(14.dp))
                    }
                    when (selectedTab) {
                        DashboardTab.Home -> homeItems(loadState, pageWidth)
                        DashboardTab.Ci -> ciItems(loadState, pageWidth, ciHistoryState)
                        DashboardTab.Issues -> item(key = "issues") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                            ) {
                                Issues(
                                    loadState = loadState,
                                    pageWidth = pageWidth,
                                    onIssuePatch = { applyIssuePatch(it, "issues-mutation") },
                                    onEditorActiveChanged = { issueEditorActive = it },
                                )
                            }
                        }
                        DashboardTab.Arcana -> item(key = "arcana") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                            ) {
                                WorkSurface(
                                    title = "Arcana Sessions",
                                    detail = "WIP. Ask the user what to steal from frontend-compose and frontend-next before this tab is implemented.",
                                    items = listOf("session chat", "patch review", "local artifacts", "desktop-only actions"),
                                )
                            }
                        }
                    }
                    item(key = "tab-content-bottom") {
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
    }
}
