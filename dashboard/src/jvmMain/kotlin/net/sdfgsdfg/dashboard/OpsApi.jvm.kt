package net.sdfgsdfg.dashboard

import net.sdfgsdfg.data.model.OpsSummaryDto
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
