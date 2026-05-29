package net.sdfgsdfg.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    Arcana("Arcana Sessions"),
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
) {
    var selectedTab by remember { mutableStateOf(DashboardTab.Home) }
    var loadState by remember { mutableStateOf<OpsLoadState>(OpsLoadState.Loading) }
    var socketState by remember { mutableStateOf(OpsSocketState()) }
    val focusRequester = remember { FocusRequester() }
    val mounted = remember { booleanArrayOf(true) }
    var handledArrowShiftSignal by remember { mutableStateOf(arrowShiftSignal) }

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
        if (shift != 0) selectedTab = selectedTab.shift(shift)
        handledArrowShiftSignal = arrowShiftSignal
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
                    when (it.key) {
                        Key.DirectionLeft -> { selectedTab = selectedTab.shift(-1); true }
                        Key.DirectionRight -> { selectedTab = selectedTab.shift(1); true }
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
                OpsWallpaper()
                val listState = rememberLazyListState()
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
                            onTabSelected = { selectedTab = it },
                            socketState = socketState,
                        )
                        if (loadState is OpsLoadState.Loading) {
                            TopLoadTrace()
                        }
                    }
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                        ) {
                            when (selectedTab) {
                                DashboardTab.Home -> Home(loadState)
                                DashboardTab.Ci -> CiResults(loadState, atPageBottom = !listState.canScrollForward)
                                DashboardTab.Issues -> Issues(loadState)
                                DashboardTab.Arcana -> WorkSurface(
                                    title = "Arcana Sessions",
                                    detail = "WIP. Ask the user what to steal from frontend-compose and frontend-next before this tab is implemented.",
                                    items = listOf("session chat", "patch review", "local artifacts", "desktop-only actions"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
