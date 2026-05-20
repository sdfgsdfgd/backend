package net.sdfgsdfg.dashboard

import kotlinx.serialization.json.Json
import net.sdfgsdfg.data.model.OpsSummaryDto

internal val dashboardJson = Json {
    ignoreUnknownKeys = true
}

internal expect fun loadOpsSummary(
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
)
