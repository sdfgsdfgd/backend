package net.sdfgsdfg.dashboard.web

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.channels.Channel
import net.sdfgsdfg.dashboard.DashboardApp
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.WheelEvent
import kotlin.js.ExperimentalWasmJsInterop

//region CMP-10297 scroll workaround
// Compose/Wasm currently caps Chrome/macOS precision-trackpad fling velocity,
// so this wasm-only bridge forwards raw WheelEvent deltas to the LazyColumn.
// TODO(CMP-10297): remove this region, wheelScrollDeltas, the wheel listener,
// and the externalScrollDeltas bridge when Compose/Wasm preserves fling deltas itself.
// https://youtrack.jetbrains.com/issue/CMP-10297
private const val wheelDeltaLinePx = 16.0
private const val wheelScrollGain = 1.35
//endregion

@OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)
fun main() {
    ComposeViewport(viewportContainerId = "dashboard") {
        var arrowShiftSignal by remember { mutableStateOf(0) }
        val wheelScrollDeltas = remember { Channel<Float>(Channel.UNLIMITED) }
        DisposableEffect(Unit) {
            val listener = { event: Event ->
                when ((event as? KeyboardEvent)?.key) {
                    "ArrowLeft" -> {
                        event.preventDefault()
                        arrowShiftSignal -= 1
                    }
                    "ArrowRight" -> {
                        event.preventDefault()
                        arrowShiftSignal += 1
                    }
                }
            }
            document.addEventListener("keydown", listener)
            onDispose { document.removeEventListener("keydown", listener) }
        }
        //region CMP-10297 scroll workaround
        DisposableEffect(Unit) {
            val target = document.getElementById("dashboard") ?: document
            val listener = { event: Event ->
                val wheel = event as? WheelEvent
                if (wheel != null && !wheel.ctrlKey) {
                    event.preventDefault()
                    event.stopPropagation()
                    val modeScale = when (wheel.deltaMode) {
                        1 -> wheelDeltaLinePx
                        2 -> window.innerHeight.toDouble()
                        else -> 1.0
                    }
                    wheelScrollDeltas.trySend((wheel.deltaY * modeScale * wheelScrollGain).toFloat())
                }
            }
            target.addEventListener("wheel", listener, true)
            onDispose {
                target.removeEventListener("wheel", listener, true)
                wheelScrollDeltas.close()
            }
        }
        //endregion
        DashboardApp(
            arrowShiftSignal = arrowShiftSignal,
            focusedArrowKeys = false,
            externalScrollDeltas = wheelScrollDeltas,
        )
    }
    window.setTimeout({
        document.getElementById("boot")?.classList?.add("boot-done")
        null
    }, 900)
}
