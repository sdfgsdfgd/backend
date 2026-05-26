package net.sdfgsdfg.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.document
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

@Composable
internal actual fun PlatformArrowKeys(onShift: (Int) -> Unit) {
    DisposableEffect(onShift) {
        val listener = { event: Event ->
            when ((event as? KeyboardEvent)?.key) {
                "ArrowLeft" -> {
                    event.preventDefault()
                    onShift(-1)
                }
                "ArrowRight" -> {
                    event.preventDefault()
                    onShift(1)
                }
            }
        }
        document.addEventListener("keydown", listener)
        onDispose { document.removeEventListener("keydown", listener) }
    }
}
