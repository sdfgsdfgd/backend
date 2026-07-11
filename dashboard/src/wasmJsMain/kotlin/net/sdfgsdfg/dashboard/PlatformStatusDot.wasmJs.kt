package net.sdfgsdfg.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.browser.document
import net.sdfgsdfg.data.model.OpsStatusDto
import org.w3c.dom.HTMLElement

private var statusLightSeed = 0

private data class StatusLightBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

@Composable
internal actual fun PlatformStatusDot(status: OpsStatusDto) {
    val id = remember { "ops-status-light-${statusLightSeed++}" }
    val density = LocalDensity.current.density

    Canvas(
        modifier = Modifier
            .padding(top = 6.dp)
            .size(36.dp)
            .onGloballyPositioned { coordinates ->
                // DOM lights bypass Compose clipping; cached/offscreen LazyColumn slots must stay hidden.
                val bounds = coordinates.boundsInWindow(clipBounds = false)
                val clipped = coordinates.boundsInWindow(clipBounds = true)
                val next = StatusLightBounds(
                    x = bounds.left / density,
                    y = bounds.top / density,
                    width = bounds.width / density,
                    height = bounds.height / density,
                )
                syncStatusLightBounds(
                    id,
                    next,
                    clipped.width >= bounds.width - 0.5f && clipped.height >= bounds.height - 0.5f,
                )
            },
    ) {}

    SideEffect {
        statusLight(id).className = "ops-status-light ${status.lightClass()}"
    }
    DisposableEffect(id) {
        statusLight(id)
        onDispose {
            document.getElementById(id)?.let { element ->
                element.parentNode?.removeChild(element)
            }
        }
    }
}

private fun syncStatusLightBounds(id: String, bounds: StatusLightBounds, fullyVisible: Boolean) {
    val element = statusLight(id)
    element.style.visibility = if (fullyVisible) "visible" else "hidden"
    if (!fullyVisible) return
    val slot = minOf(bounds.width, bounds.height)
    val size = slot * 1.9f
    val x = bounds.x + bounds.width / 2f - size / 2f
    val y = bounds.y + bounds.height / 2f - size / 2f
    element.style.width = "${size}px"
    element.style.height = "${size}px"
    element.style.transform = "translate3d(${x}px, ${y}px, 0)"
}

private fun statusLight(id: String): HTMLElement {
    val existing = document.getElementById(id) as? HTMLElement
    if (existing != null) return existing
    return (document.createElement("span") as HTMLElement).also { element ->
        element.id = id
        element.innerHTML = "<span class=\"ops-status-light-core\"></span>"
        document.body?.appendChild(element)
    }
}

private fun OpsStatusDto.lightClass(): String = when (this) {
    OpsStatusDto.OK -> "ok"
    OpsStatusDto.FAIL -> "fail"
    OpsStatusDto.WARN -> "warn"
    OpsStatusDto.WIP -> "wip"
    OpsStatusDto.UNKNOWN -> "unknown"
}
