package net.sdfgsdfg.dashboard.tools

import jdk.jfr.Configuration
import jdk.jfr.Recording
import jdk.jfr.RecordingState
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal actual fun startDashboardJfrWindow(profile: DashboardJfrProfile, windowMs: Long): DashboardJfrWindow? {
    if (!profile.enabled) return null
    return runCatching {
        val config = runCatching { Configuration.getConfiguration("profile") }
            .getOrElse { Configuration.getConfiguration("default") }
        val path = Path.of(
            System.getProperty("java.io.tmpdir"),
            "dashboard-${profile.scope.filePart()}-${profile.label.filePart()}-${System.currentTimeMillis()}.jfr",
        )
        JvmDashboardJfrWindow(
            profile = profile,
            path = path,
            recording = Recording(config).apply {
                name = "dashboard-${profile.scope}-${profile.label}"
                setToDisk(true)
                start()
            },
        ).also {
            dashboardJfrTrace(profile.scope, "started") {
                "label=${profile.label} window=${windowMs}ms detail=${profile.detail}"
            }
        }
    }.getOrElse { error ->
        dashboardJfrTrace(profile.scope, "start-failed") {
            "label=${profile.label} error=${error.message.orEmpty()}"
        }
        null
    }
}

private class JvmDashboardJfrWindow(
    private val profile: DashboardJfrProfile,
    private val path: Path,
    private val recording: Recording,
) : DashboardJfrWindow {
    override fun stop(): String? {
        val saved = runCatching {
            if (recording.state == RecordingState.RUNNING) recording.stop()
            Files.createDirectories(path.parent)
            recording.dump(path)
            path.toString()
        }.getOrElse { error ->
            dashboardJfrTrace(profile.scope, "save-failed") {
                "label=${profile.label} error=${error.message.orEmpty()}"
            }
            null
        }
        runCatching { recording.close() }
        return saved
    }
}

private fun String.filePart() =
    lowercase(Locale.US)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { "window" }
        .take(64)
