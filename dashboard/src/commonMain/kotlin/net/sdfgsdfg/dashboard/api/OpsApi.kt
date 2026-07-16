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

internal expect fun loadOpsText(
    path: String,
    onLoaded: (String) -> Unit,
    onFailed: (String) -> Unit,
)

internal expect fun connectOpsSocket(
    onMessage: (OpsSocketMessageDto) -> Unit,
    onState: (OpsSocketState) -> Unit,
): OpsSocketConnection

internal class OpsSocketConnection(
    private val sendMessage: (OpsSocketMessageDto) -> Boolean,
    private val closeSocket: () -> Unit,
) {
    fun send(message: OpsSocketMessageDto) = sendMessage(message)
    fun close() = closeSocket()
}

internal expect fun mutateIssue(
    request: IssueMutationRequestDto,
    onLoaded: (OpsIssuePatchDto) -> Unit,
    onFailed: (String) -> Unit,
)

internal expect fun openOpsUrl(url: String)

internal expect fun startOpsGithubAuth(onComplete: () -> Unit)

internal expect fun endOpsGithubAuth(onComplete: () -> Unit)

internal expect fun cancelOpsGithubAuth()

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
