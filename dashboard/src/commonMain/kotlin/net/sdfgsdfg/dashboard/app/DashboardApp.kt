package net.sdfgsdfg.dashboard

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import net.sdfgsdfg.data.model.OpsIssuePatchDto
import net.sdfgsdfg.data.model.OpsRunEventDto
import net.sdfgsdfg.data.model.OpsSessionCommandDto
import net.sdfgsdfg.data.model.OpsSessionEventDto
import net.sdfgsdfg.data.model.OpsSocketMessageDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsViewerDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventDto
import net.sdfgsdfg.dashboard.tools.issueFrameTrace

private fun OpsSummaryDto.issueTraceShape() =
    "repos=${repos.joinToString { "${it.id}:${it.issues.active}/${it.issues.items.size}" }}"

private fun String.runLifecycleLabel() = when {
    startsWith("deploy ") -> "deploy"
    this == "live e2e selftest" || this == "live selftest" -> "live selftest"
    else -> this
}

class DashboardWindowKeyRouter {
    internal var app: (KeyEvent) -> Boolean = { false }
    internal var x: (KeyEvent) -> Boolean = { false }

    fun dispatch(event: KeyEvent): Boolean = app(event)
}

@Composable
fun DashboardApp(
    windowKeys: DashboardWindowKeyRouter? = null,
    arrowShiftSignal: Int = 0,
    focusedArrowKeys: Boolean = true,
    // CMP-10297 workaround: raw WheelEvent deltas from wasmJsMain.
    externalScrollDeltas: ReceiveChannel<Float>? = null,
    onNativeWheelRegionChanged: ((Boolean) -> Unit)? = null,
) {
    var selectedTab by remember { mutableStateOf(readDashboardPref("ops.tab")?.let(DashboardTab::fromStoredName) ?: DashboardTab.Home) }
    var loadState by remember { mutableStateOf<OpsLoadState>(OpsLoadState.Loading) }
    var socketState by remember { mutableStateOf(OpsSocketState()) }
    var socketConnection by remember { mutableStateOf<OpsSocketConnection?>(null) }
    var workspaceEvent by remember { mutableStateOf<OpsWorkspaceEventDto?>(null) }
    val sessionInbox = remember { Channel<OpsSessionEventDto>(Channel.UNLIMITED) }
    val sessionLedger = remember { XSessionLedger() }
    var viewer by remember { mutableStateOf(OpsViewerDto()) }
    var activeRunEvents by remember { mutableStateOf(emptyList<OpsRunEventDto>()) }
    var issueEditorActive by remember { mutableStateOf(false) }
    var freshXSignal by remember { mutableStateOf(0) }
    var lastAppliedSummary by remember { mutableStateOf<OpsSummaryDto?>(null) }
    val fallbackFocusRequester = remember { FocusRequester() }
    val mounted = remember { booleanArrayOf(true) }
    var handledArrowShiftSignal by remember { mutableStateOf(arrowShiftSignal) }
    val issueEditorActiveState = rememberUpdatedState(issueEditorActive)

    fun refreshViewer() {
        loadOpsViewer(
            onLoaded = { if (mounted[0]) viewer = it },
            onFailed = {
                if (mounted[0]) viewer = OpsViewerDto()
                issueFrameTrace("viewer-load-failed") { it.ifBlank { "Failed to load ops viewer" } }
            },
        )
    }

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

    LaunchedEffect(windowKeys, focusedArrowKeys) {
        if (windowKeys == null && focusedArrowKeys) runCatching { fallbackFocusRequester.requestFocus() }
    }
    LaunchedEffect(Unit) {
        refreshViewer()
    }
    LaunchedEffect(sessionInbox) {
        for (event in sessionInbox) {
            sessionLedger.append(event)
        }
    }
    DisposableEffect(Unit) {
        mounted[0] = true
        val connection = connectOpsSocket(
            onMessage = { message ->
                if (!mounted[0]) return@connectOpsSocket
                message.workspaceEvent?.let { workspaceEvent = it }
                message.sessionEvent?.let { sessionInbox.trySend(it) }
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
        socketConnection = connection
        onDispose {
            mounted[0] = false
            socketConnection = null
            connection.close()
        }
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
    val windowKeyHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else if (event.key == Key.X && event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed && !event.isMetaPressed) {
            selectedTab = DashboardTab.Arcana
            writeDashboardPref("ops.tab", selectedTab.name)
            freshXSignal++
            true
        } else if (issueEditorActive) {
            false
        } else if (selectedTab == DashboardTab.Arcana) {
            windowKeys?.x?.invoke(event) ?: false
        } else if (focusedArrowKeys) {
            when (event.key) {
                Key.DirectionLeft -> { selectedTab = selectedTab.shift(-1); writeDashboardPref("ops.tab", selectedTab.name); true }
                Key.DirectionRight -> { selectedTab = selectedTab.shift(1); writeDashboardPref("ops.tab", selectedTab.name); true }
                else -> false
            }
        } else {
            false
        }
    }
    DisposableEffect(windowKeys) {
        val router = windowKeys
        val route: (KeyEvent) -> Boolean = { windowKeyHandler.value(it) }
        if (router != null) router.app = route
        onDispose {
            if (router?.app === route) router.app = { false }
        }
    }
    val surfaceModifier = if (windowKeys == null && focusedArrowKeys) {
        Modifier.fillMaxSize().focusRequester(fallbackFocusRequester).focusable()
    } else {
        Modifier.fillMaxSize()
    }

    CompositionLocalProvider(LocalNativeWheelRegionChanged provides onNativeWheelRegionChanged) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = cyan,
                secondary = green,
                surface = panel,
                background = background,
            ),
        ) {
            Surface(
                modifier = if (windowKeys == null && focusedArrowKeys) {
                    surfaceModifier.onPreviewKeyEvent { windowKeyHandler.value(it) }
                } else {
                    surfaceModifier
                },
                color = Color.Transparent,
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val pageBottomGutter = maxHeight * 0.28f
                    val pageWidth = maxWidth
                    val pageHeight = maxHeight
                    Crossfade(
                        targetState = selectedTab == DashboardTab.Arcana,
                        animationSpec = tween(760),
                        label = "dashboard-background-crossfade",
                    ) { xSelected ->
                        if (xSelected) XBackdrop() else OpsWallpaper()
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(selectedTab, listState) {
                        if (selectedTab == DashboardTab.Arcana) listState.scrollToItem(0)
                    }
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
                    val ciSummary = (loadState as? OpsLoadState.Ready)?.summary
                    val ciHistoryState = rememberCiHistoryState(ciSummary, activeRunEvents, atPageBottom = !listState.canScrollForward)
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = selectedTab != DashboardTab.Arcana,
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
                                viewer = viewer,
                                onViewerChanged = ::refreshViewer,
                            )
                            if (loadState is OpsLoadState.Loading) {
                                TopLoadTrace()
                            }
                        }
                        item(key = "tab-content-top") {
                            Spacer(Modifier.height(14.dp))
                        }
                        item(key = "tab-content") {
                            Crossfade(
                                targetState = selectedTab,
                                modifier = Modifier.fillMaxWidth(),
                                label = "dashboard-tab-crossfade",
                            ) { tab ->
                                when (tab) {
                                    DashboardTab.Home -> HomeTab(loadState, pageWidth)
                                    DashboardTab.Ci -> CiTab(loadState, pageWidth)
                                    DashboardTab.Issues -> Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp),
                                    ) {
                                        Issues(
                                            loadState = loadState,
                                            pageWidth = pageWidth,
                                            pageHeight = pageHeight,
                                            canWriteIssues = viewer.issueWrite,
                                            onIssuePatch = { applyIssuePatch(it, "issues-mutation") },
                                            onEditorActiveChanged = { issueEditorActive = it },
                                        )
                                    }
                                    DashboardTab.Arcana -> ArcanaSessionsTab(
                                        windowKeys = windowKeys,
                                        freshXSignal = freshXSignal,
                                        viewer = viewer,
                                        socketState = socketState,
                                        pageHeight = pageHeight,
                                        workspaceEvent = workspaceEvent,
                                        sessionLedger = sessionLedger,
                                        sendWorkspaceCommand = { command ->
                                            socketConnection?.send(
                                                OpsSocketMessageDto("workspace_command", workspaceCommand = command),
                                            ) == true
                                        },
                                        sendSessionCommand = { command: OpsSessionCommandDto ->
                                            socketConnection?.send(
                                                OpsSocketMessageDto("session_command", sessionCommand = command),
                                            ) == true
                                        },
                                    )
                                }
                            }
                        }
                        if (selectedTab == DashboardTab.Ci && ciSummary != null && ciHistoryState != null) {
                            ciHistoryItems(ciHistoryState, ciSummary.generatedAtMs)
                        }
                        item(key = "tab-content-bottom") {
                            Spacer(Modifier.height(14.dp))
                        }
                    }
                }
            }
        }
    }
}
