package net.sdfgsdfg.dashboard.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import net.sdfgsdfg.dashboard.readDashboardPref

private var traceSeq = 0

internal data class FrameWindowSample(
    val deltaMs: Long,
    val frame: Int,
)

internal data class FrameWindowSummary(
    val complete: Boolean,
    val elapsedMs: Long,
    val frames: Int,
    val slowFrames: Int,
    val severeFrames: Int,
    val worstFrameMs: Long,
)

private fun dashboardFlag(key: String, default: Boolean = false) =
    readDashboardPref(key)?.booleanFlag() ?: default

internal fun dashboardFrameTraceEnabled(scope: String) =
    dashboardFlag("ops.$scope.trace") || dashboardFlag("ops.$scope.frameTrace")

internal fun dashboardFrameTrace(scope: String, label: String, detail: () -> String) {
    if (dashboardFrameTraceEnabled(scope)) println("[${scope}-frame ${++traceSeq}] $label ${detail()}")
}

internal fun issueFrameTraceEnabled() = dashboardFrameTraceEnabled("issue")

internal fun issueFrameTrace(label: String, detail: () -> String) =
    dashboardFrameTrace("issue", label, detail)

@Composable
internal fun FrameWindowProfiler(
    enabled: Boolean,
    key: Any?,
    windowMs: Long,
    slowFrameMs: Long = 34L,
    severeFrameMs: Long = 80L,
    onSevereFrame: (FrameWindowSample) -> Unit = {},
    onSummary: (FrameWindowSummary) -> Unit,
) {
    LaunchedEffect(enabled, key, windowMs, slowFrameMs, severeFrameMs) {
        if (!enabled) return@LaunchedEffect
        val start = withFrameNanos { it }
        var last = start
        var frames = 0
        var slow = 0
        var severe = 0
        var worst = 0L
        var complete = false
        try {
            while ((last - start) / 1_000_000L < windowMs) {
                val now = withFrameNanos { it }
                val deltaMs = (now - last) / 1_000_000L
                frames += 1
                if (deltaMs > worst) worst = deltaMs
                if (deltaMs >= slowFrameMs) slow += 1
                if (deltaMs >= severeFrameMs) {
                    severe += 1
                    onSevereFrame(FrameWindowSample(deltaMs, frames))
                }
                last = now
            }
            complete = true
        } finally {
            onSummary(
                FrameWindowSummary(
                    complete = complete,
                    elapsedMs = (last - start) / 1_000_000L,
                    frames = frames,
                    slowFrames = slow,
                    severeFrames = severe,
                    worstFrameMs = worst,
                ),
            )
        }
    }
}

internal fun Modifier.profileFrameWindow(
    enabled: Boolean,
    key: Any?,
    windowMs: Long,
    slowFrameMs: Long = 34L,
    severeFrameMs: Long = 80L,
    onSevereFrame: (FrameWindowSample) -> Unit = {},
    onSummary: (FrameWindowSummary) -> Unit,
): Modifier = composed {
    FrameWindowProfiler(
        enabled = enabled,
        key = key,
        windowMs = windowMs,
        slowFrameMs = slowFrameMs,
        severeFrameMs = severeFrameMs,
        onSevereFrame = onSevereFrame,
        onSummary = onSummary,
    )
    this
}

private fun String.booleanFlag() = when (lowercase()) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> null
}
