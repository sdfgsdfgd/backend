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
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import net.sdfgsdfg.data.model.OpsSummaryDto

internal enum class DashboardTab(val label: String) {
    Home("Home"),
    Ci("CI Results"),
    Issues("Issues"),
    Arcana("Arcana Sessions");

    companion object {
        fun fromStoredName(value: String) = entries.firstOrNull { it.name == value }
    }
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
    // CMP-10297 workaround: cumulative raw WheelEvent delta from wasmJsMain.
    externalScrollPx: Float = 0f,
) {
    var selectedTab by remember { mutableStateOf(readDashboardPref("ops.tab")?.let(DashboardTab::fromStoredName) ?: DashboardTab.Home) }
    var loadState by remember { mutableStateOf<OpsLoadState>(OpsLoadState.Loading) }
    var socketState by remember { mutableStateOf(OpsSocketState()) }
    var issueEditorActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val mounted = remember { booleanArrayOf(true) }
    var handledArrowShiftSignal by remember { mutableStateOf(arrowShiftSignal) }
    var handledExternalScrollPx by remember { mutableStateOf(externalScrollPx) }

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
                message.summary?.let { loadState = OpsLoadState.Ready(it) }
            },
            onState = { if (mounted[0]) socketState = it },
        )
        onDispose { close() }
    }
    LaunchedEffect(socketState.status) {
        if (socketState.status != OpsSocketStatus.DISCONNECTED) return@LaunchedEffect
        while (true) {
            loadOpsSummary(
                onLoaded = { if (mounted[0]) loadState = OpsLoadState.Ready(it) },
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
                // TODO(CMP-10297): remove externalScrollPx, handledExternalScrollPx,
                // scrollBy, and the wasmJsMain wheel bridge when Compose/Wasm
                // preserves Chrome/macOS precision-trackpad fling deltas internally.
                // https://youtrack.jetbrains.com/issue/CMP-10297
                LaunchedEffect(externalScrollPx) {
                    val delta = externalScrollPx - handledExternalScrollPx
                    handledExternalScrollPx = externalScrollPx
                    if (delta != 0f && !issueEditorActive) listState.scrollBy(delta)
                }
                //endregion
                val ciSummary = if (selectedTab == DashboardTab.Ci) (loadState as? OpsLoadState.Ready)?.summary else null
                val ciHistoryState = rememberCiHistoryState(ciSummary, atPageBottom = !listState.canScrollForward)
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
                                    onSummary = { loadState = OpsLoadState.Ready(it) },
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
