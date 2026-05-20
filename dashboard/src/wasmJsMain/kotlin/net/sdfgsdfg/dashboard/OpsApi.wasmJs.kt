package net.sdfgsdfg.dashboard

import kotlinx.browser.window
import kotlin.js.ExperimentalWasmJsInterop
import net.sdfgsdfg.data.model.OpsSummaryDto
import org.w3c.fetch.Response

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun loadOpsSummary(
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
) {
    window.fetch(opsSummaryUrl()).then(
        onFulfilled = { response: Response ->
            if (!response.ok) {
                onFailed("GET /api/ops/summary failed with ${response.status}")
            } else {
                response.text().then(
                    onFulfilled = { body ->
                        runCatching { dashboardJson.decodeFromString<OpsSummaryDto>("$body") }
                            .fold(
                                onSuccess = onLoaded,
                                onFailure = { onFailed(it.message ?: "Failed to decode ops summary") },
                            )
                        null
                    },
                    onRejected = {
                        onFailed("GET /api/ops/summary response body failed")
                        null
                    },
                )
            }
            null
        },
        onRejected = {
            onFailed("GET /api/ops/summary failed")
            null
        },
    )
}

internal actual fun openOpsUrl(url: String) {
    window.open(opsUrl(url), "_blank")
}

private fun opsSummaryUrl(): String {
    return opsUrl("/api/ops/summary")
}

private fun opsUrl(pathOrUrl: String): String {
    if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl
    val port = window.location.port
    return if (port.isBlank() || port == "80" || port == "443") {
        pathOrUrl
    } else {
        "http://127.0.0.1$pathOrUrl"
    }
}
