package net.sdfgsdfg.dashboard.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import net.sdfgsdfg.dashboard.DashboardApp
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)
fun main() {
    ComposeViewport(viewportContainerId = "dashboard") {
        DashboardApp()
    }
    window.setTimeout({
        document.getElementById("boot")?.classList?.add("boot-done")
        null
    }, 900)
}
