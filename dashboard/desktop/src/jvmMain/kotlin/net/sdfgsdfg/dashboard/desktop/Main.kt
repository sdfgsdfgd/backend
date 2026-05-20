package net.sdfgsdfg.dashboard.desktop

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.singleWindowApplication
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import net.sdfgsdfg.dashboard.DashboardApp
import java.awt.Toolkit
import kotlin.math.roundToInt

fun main() = singleWindowApplication(
    title = "Trio Ops Cockpit",
    state = WindowState(
        position = WindowPosition.Aligned(Alignment.Center),
        size = desktopInitialSize(),
    ),
) {
    DashboardApp()
}

private fun desktopInitialSize(): DpSize {
    val screen = Toolkit.getDefaultToolkit().screenSize
    return DpSize((screen.width * 0.9).roundToInt().dp, (screen.height * 0.9).roundToInt().dp)
}
