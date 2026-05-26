package net.sdfgsdfg.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
    var bounds by remember { mutableStateOf<StatusLightBounds?>(null) }

    Canvas(
        modifier = Modifier
            .padding(top = 6.dp)
            .size(36.dp)
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                val next = StatusLightBounds(
                    x = position.x / density,
                    y = position.y / density,
                    width = coordinates.size.width / density,
                    height = coordinates.size.height / density,
                )
                bounds = next
                syncStatusLight(id, status, next)
            },
    ) {}

    SideEffect {
        syncStatusLight(id, status, bounds)
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

private fun syncStatusLight(id: String, status: OpsStatusDto, bounds: StatusLightBounds?) {
    val element = statusLight(id)
    element.className = "ops-status-light ${status.lightClass()}"
    if (bounds == null) {
        element.style.left = "-9999px"
        element.style.top = "-9999px"
    } else {
        val slot = minOf(bounds.width, bounds.height)
        val size = slot * 1.9f
        element.style.left = "${bounds.x + bounds.width / 2f - size / 2f}px"
        element.style.top = "${bounds.y + bounds.height / 2f - size / 2f}px"
        element.style.width = "${size}px"
        element.style.height = "${size}px"
    }
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
