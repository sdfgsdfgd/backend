package net.sdfgsdfg.dashboard

import net.sdfgsdfg.data.model.OpsSummaryDto
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.swing.SwingUtilities

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

private fun fetchOpsSummary(): OpsSummaryDto {
    val endpoint = System.getenv("OPS_API_BASE")?.trim()?.trimEnd('/')
        ?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1"
    val request = HttpRequest.newBuilder()
        .uri(URI.create("$endpoint/api/ops/summary"))
        .GET()
        .build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
        error("GET /api/ops/summary failed with ${response.statusCode()}")
    }
    return dashboardJson.decodeFromString<OpsSummaryDto>(response.body())
}
