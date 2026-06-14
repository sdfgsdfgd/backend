package net.sdfgsdfg.dashboard

import kotlinx.serialization.json.Json
import net.sdfgsdfg.data.model.IssueMutationRequestDto
import net.sdfgsdfg.data.model.OpsIssuePatchDto
import net.sdfgsdfg.data.model.OpsSocketMessageDto
import net.sdfgsdfg.data.model.OpsSummaryDto

internal val dashboardJson = Json {
    ignoreUnknownKeys = true
}

internal expect fun loadOpsSummary(
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
)

internal enum class OpsSocketStatus { CONNECTING, CONNECTED, DISCONNECTED }

internal data class OpsSocketState(
    val status: OpsSocketStatus = OpsSocketStatus.CONNECTING,
    val latencyMs: Long? = null,
)

internal expect fun connectOpsSocket(
    onMessage: (OpsSocketMessageDto) -> Unit,
    onState: (OpsSocketState) -> Unit,
): () -> Unit

internal expect fun mutateIssue(
    request: IssueMutationRequestDto,
    onLoaded: (OpsIssuePatchDto) -> Unit,
    onFailed: (String) -> Unit,
)

internal expect fun openOpsUrl(url: String)

internal expect fun readDashboardPref(key: String): String?

internal expect fun writeDashboardPref(key: String, value: String?)
