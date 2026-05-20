package net.sdfgsdfg.dashboard.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import net.sdfgsdfg.dashboard.DashboardApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "dashboard") {
        DashboardApp()
    }
}
