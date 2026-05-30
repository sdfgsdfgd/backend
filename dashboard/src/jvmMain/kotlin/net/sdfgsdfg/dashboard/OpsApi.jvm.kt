package net.sdfgsdfg.dashboard

import kotlinx.serialization.encodeToString
import net.sdfgsdfg.data.model.IssueMutationRequestDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsSocketMessageDto
import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.swing.SwingUtilities

private var activeOpsApiBase: String? = null

internal actual fun loadOpsSummary(
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
) {
    Thread({
        runCatching { fetchOpsSummary() }
            .fold(
                onSuccess = { SwingUtilities.invokeLater { onLoaded(it) } },
                onFailure = { error -> SwingUtilities.invokeLater { onFailed(error.message ?: "Failed to load ops summary") } },
            )
    }, "ops-summary-loader").apply {
        isDaemon = true
        start()
    }
}

internal actual fun openOpsUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(opsUrl(url)))
    }
}

internal actual fun readDashboardPref(key: String): String? = null

internal actual fun writeDashboardPref(key: String, value: String?) {
}

internal actual fun connectOpsSocket(
    onMessage: (OpsSocketMessageDto) -> Unit,
    onState: (OpsSocketState) -> Unit,
): () -> Unit {
    onState(OpsSocketState(OpsSocketStatus.DISCONNECTED))
    return {}
}

internal actual fun mutateIssue(
    request: IssueMutationRequestDto,
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
) {
    Thread({
        runCatching {
            val endpoint = activeOpsApiBase ?: configuredOpsApiBase() ?: "http://127.0.0.1"
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(URI.create("$endpoint/api/ops/issues"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(dashboardJson.encodeToString(request)))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() !in 200..299) error("POST $endpoint/api/ops/issues failed with ${response.statusCode()}")
            dashboardJson.decodeFromString<OpsSummaryDto>(response.body())
        }.fold(
            onSuccess = { SwingUtilities.invokeLater { onLoaded(it) } },
            onFailure = { SwingUtilities.invokeLater { onFailed(it.message ?: "Issue mutation failed") } },
        )
    }, "ops-issue-mutator").apply {
        isDaemon = true
        start()
    }
}

private fun fetchOpsSummary(): OpsSummaryDto {
    val endpoints = configuredOpsApiBase()
        ?.let(::listOf)
        ?: listOf("http://127.0.0.1", "https://ops.sdfgsdfg.net")

    val client = HttpClient.newHttpClient()
    var lastError: Throwable? = null
    endpoints.forEach { endpoint ->
        runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$endpoint/api/ops/summary"))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("GET $endpoint/api/ops/summary failed with ${response.statusCode()}")
            }
            activeOpsApiBase = endpoint
            return dashboardJson.decodeFromString<OpsSummaryDto>(response.body())
        }.onFailure { lastError = it }
    }
    throw lastError ?: error("No ops API endpoints configured")
}

private fun opsUrl(pathOrUrl: String): String = when {
    pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
    else -> "${activeOpsApiBase ?: configuredOpsApiBase() ?: "https://ops.sdfgsdfg.net"}$pathOrUrl"
}

private fun configuredOpsApiBase(): String? = System.getenv("OPS_API_BASE")
    ?.trim()
    ?.trimEnd('/')
    ?.takeIf { it.isNotBlank() }
