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
import net.sdfgsdfg.dashboard.DashboardApp
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)
fun main() {
    ComposeViewport(viewportContainerId = "dashboard") {
        var arrowShiftSignal by remember { mutableStateOf(0) }
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
        DashboardApp(arrowShiftSignal = arrowShiftSignal, focusedArrowKeys = false)
    }
    window.setTimeout({
        document.getElementById("boot")?.classList?.add("boot-done")
        null
    }, 900)
}
