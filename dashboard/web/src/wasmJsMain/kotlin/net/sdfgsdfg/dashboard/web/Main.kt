package net.sdfgsdfg.dashboard.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import net.sdfgsdfg.dashboard.DashboardApp
import org.w3c.dom.HTMLElement
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)
fun main() {
    ComposeViewport(viewportContainerId = "dashboard") {
        DashboardApp()
    }
    window.setTimeout({
        document.getElementById("boot")?.classList?.add("boot-done")
        focusDashboard()
        window.setTimeout({ focusDashboard(); null }, 150)
        null
    }, 900)
}

private fun focusDashboard() {
    val host = document.getElementById("dashboard") as? HTMLElement ?: return
    val target = host.firstElementChild as? HTMLElement ?: host
    target.tabIndex = -1
    target.focus()
}
