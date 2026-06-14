package net.sdfgsdfg.dashboard.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import net.sdfgsdfg.dashboard.readDashboardPref

/*
 * Dashboard profiling ladder
 *
 * These tools live near the UI because animation jank needs the exact state
 * transition that opened the bad frame window.
 *
 * 1. Semantic trace:
 *    dashboardFrameTrace()/issueFrameTrace() logs state events such as summary
 *    apply/skip, issue patch merge, lane key changes, and removed keys.
 *    Example flag: DASHBOARD_OPS_ISSUE_TRACE=1.
 *
 * 2. Frame-window profiling:
 *    FrameWindowProfiler/profileFrameWindow() samples withFrameNanos over a
 *    focused window and reports slow/severe frames. This proves whether a
 *    transition is actually smooth.
 *
 * 3. Local layout counters:
 *    Callers can add tiny layout modifiers around suspected items and include
 *    measure/place counts in the frame summary. This separates layout churn
 *    from draw/render stalls.
 *
 * 4. JVM/JFR attribution:
 *    Optional DashboardJfrProfile captures a short Java Flight Recorder file
 *    for the same frame window on desktop/JVM. This is what tied the issue
 *    removal stutter to decoration paint: SimpleInnerShadowNode,
 *    SimpleDropShadowNode, ShadowRenderer, Canvas.drawRRect, and SkiaLayer.draw.
 *    Example flag: DASHBOARD_OPS_ISSUE_JFR=1. Combine with
 *    DASHBOARD_OPS_ISSUE_TRACE=1 when you also want semantic/frame log lines.
 *
 * 5. Wasm/browser attribution:
 *    The common API compiles on wasm, but JFR is a no-op there. For web, pair
 *    these frame-window logs with Chrome/DevTools performance traces when the
 *    question is browser main-thread/render/compositor behavior.
 */

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

internal data class DashboardJfrProfile(
    val scope: String,
    val label: String,
    val detail: String = "",
    val enabled: Boolean = dashboardJfrTraceEnabled(scope),
)

internal interface DashboardJfrWindow {
    fun stop(): String?
}

private fun dashboardFlag(key: String, default: Boolean = false) =
    readDashboardPref(key)?.booleanFlag() ?: default

internal fun dashboardFrameTraceEnabled(scope: String) =
    dashboardFlag("ops.$scope.trace") || dashboardFlag("ops.$scope.frameTrace")

internal fun dashboardJfrTraceEnabled(scope: String) =
    dashboardFlag("ops.$scope.jfr") || dashboardFlag("ops.$scope.profile")

internal fun dashboardFrameTrace(scope: String, label: String, detail: () -> String) {
    if (dashboardFrameTraceEnabled(scope)) println("[${scope}-frame ${++traceSeq}] $label ${detail()}")
}

internal fun dashboardJfrTrace(scope: String, label: String, detail: () -> String) {
    if (dashboardJfrTraceEnabled(scope) || dashboardFrameTraceEnabled(scope)) println("[${scope}-jfr] $label ${detail()}")
}

internal fun issueFrameTraceEnabled() = dashboardFrameTraceEnabled("issue")

internal fun issueProfileEnabled() = issueFrameTraceEnabled() || dashboardJfrTraceEnabled("issue")

internal fun issueFrameTrace(label: String, detail: () -> String) =
    dashboardFrameTrace("issue", label, detail)

internal fun issueJfrProfile(label: String, detail: String = "") =
    DashboardJfrProfile(scope = "issue", label = label, detail = detail)

@Composable
internal fun FrameWindowProfiler(
    enabled: Boolean,
    key: Any?,
    windowMs: Long,
    slowFrameMs: Long = 34L,
    severeFrameMs: Long = 80L,
    jfr: DashboardJfrProfile? = null,
    onSevereFrame: (FrameWindowSample) -> Unit = {},
    onSummary: (FrameWindowSummary) -> Unit,
) {
    val jfrProfile = jfr?.takeIf { it.enabled }
    LaunchedEffect(enabled, key, windowMs, slowFrameMs, severeFrameMs, jfrProfile) {
        if (!enabled) return@LaunchedEffect
        val jfrWindow = jfrProfile?.let { startDashboardJfrWindow(it, windowMs) }
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
            jfrWindow?.stop()?.let { path ->
                dashboardJfrTrace(jfrProfile.scope, "saved") { "label=${jfrProfile.label} path=$path" }
            }
        }
    }
}

internal fun Modifier.profileFrameWindow(
    enabled: Boolean,
    key: Any?,
    windowMs: Long,
    slowFrameMs: Long = 34L,
    severeFrameMs: Long = 80L,
    jfr: DashboardJfrProfile? = null,
    onSevereFrame: (FrameWindowSample) -> Unit = {},
    onSummary: (FrameWindowSummary) -> Unit,
): Modifier = composed {
    FrameWindowProfiler(
        enabled = enabled,
        key = key,
        windowMs = windowMs,
        slowFrameMs = slowFrameMs,
        severeFrameMs = severeFrameMs,
        jfr = jfr,
        onSevereFrame = onSevereFrame,
        onSummary = onSummary,
    )
    this
}

internal expect fun startDashboardJfrWindow(profile: DashboardJfrProfile, windowMs: Long): DashboardJfrWindow?

private fun String.booleanFlag() = when (lowercase()) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> null
}
