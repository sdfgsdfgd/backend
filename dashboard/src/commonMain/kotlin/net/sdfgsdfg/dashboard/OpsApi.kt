package net.sdfgsdfg.dashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import net.sdfgsdfg.data.model.IssueMutationRequestDto
import net.sdfgsdfg.data.model.OpsIssuePatchDto
import net.sdfgsdfg.data.model.OpsSocketMessageDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsViewerDto

internal val dashboardJson = Json {
    ignoreUnknownKeys = true
}

internal expect fun loadOpsSummary(
    onLoaded: (OpsSummaryDto) -> Unit,
    onFailed: (String) -> Unit,
)

internal expect fun loadOpsViewer(
    onLoaded: (OpsViewerDto) -> Unit,
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

internal expect fun startOpsGithubAuth(onComplete: () -> Unit)

internal expect fun endOpsGithubAuth(onComplete: () -> Unit)

internal expect fun cancelOpsGithubAuth()

data class OpsGithubAuthWindowState(
    val title: String,
    val code: String? = null,
    val detail: String,
    val status: String,
    val terminal: Boolean = false,
    val success: Boolean = false,
)

private val mutableOpsGithubAuthWindowState = MutableStateFlow<OpsGithubAuthWindowState?>(null)
val opsGithubAuthWindowState: StateFlow<OpsGithubAuthWindowState?> = mutableOpsGithubAuthWindowState.asStateFlow()

fun dismissOpsGithubAuthWindow() {
    cancelOpsGithubAuth()
    hideOpsGithubAuthWindow()
}

internal fun hideOpsGithubAuthWindow() {
    mutableOpsGithubAuthWindowState.value = null
}

internal fun showOpsGithubAuthWindow(state: OpsGithubAuthWindowState?) {
    mutableOpsGithubAuthWindowState.value = state
}

internal expect fun readDashboardPref(key: String): String?

internal expect fun writeDashboardPref(key: String, value: String?)
