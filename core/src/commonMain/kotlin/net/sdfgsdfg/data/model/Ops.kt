package net.sdfgsdfg.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpsSummaryDto(
    @SerialName("generated_at_ms") val generatedAtMs: Long,
    val repos: List<RepoHealthDto>,
)

@Serializable
data class RepoHealthDto(
    val id: String,
    val name: String,
    val role: String,
    val status: OpsStatusDto,
    @SerialName("runtime_label") val runtimeLabel: String? = null,
    @SerialName("runtime_labels") val runtimeLabels: List<String> = emptyList(),
    @SerialName("service_name") val serviceName: String? = null,
    @SerialName("latest_run") val latestRun: TestRunSummaryDto? = null,
    val runs: List<TestRunSummaryDto> = emptyList(),
    val history: List<TestRunSummaryDto> = emptyList(),
    val signals: List<OpsSignalDto> = emptyList(),
    @SerialName("self_test") val selfTest: SelfTestSummaryDto? = null,
    val issues: IssueSummaryDto = IssueSummaryDto(),
    val note: String? = null,
)

@Serializable
enum class OpsStatusDto {
    OK,
    WARN,
    FAIL,
    WIP,
    UNKNOWN,
}

@Serializable
data class TestRunSummaryDto(
    val label: String,
    val status: OpsStatusDto,
    @SerialName("timestamp_ms") val timestampMs: Long? = null,
    @SerialName("duration_ms") val durationMs: Double? = null,
    val detail: String? = null,
    val url: String? = null,
)

@Serializable
data class OpsSignalDto(
    val label: String,
    val status: OpsStatusDto,
    @SerialName("timestamp_ms") val timestampMs: Long? = null,
    val detail: String? = null,
    val meta: String? = null,
)

@Serializable
data class OpsHostSnapshotDto(
    @SerialName("generated_at_ms") val generatedAtMs: Long,
    val host: String,
    @SerialName("backend_runtime_label") val backendRuntimeLabel: String,
    @SerialName("server_py_runtime_label") val serverPyRuntimeLabel: String,
    @SerialName("server_py_ready") val serverPyReady: Boolean,
    @SerialName("server_py_transport") val serverPyTransport: String,
    @SerialName("arcana_signals") val arcanaSignals: List<OpsSignalDto> = emptyList(),
)

@Serializable
data class ArcanaIngestDto(
    val status: OpsStatusDto = OpsStatusDto.WIP,
    val label: String = "arcana publisher",
    @SerialName("timestamp_ms") val timestampMs: Long? = null,
    @SerialName("duration_ms") val durationMs: Double? = null,
    val detail: String? = null,
    val url: String? = null,
    val issues: IssueSummaryDto = IssueSummaryDto(),
    val runs: List<TestRunSummaryDto> = emptyList(),
)

@Serializable
data class SelfTestSummaryDto(
    val status: OpsStatusDto,
    val ok: Boolean,
    @SerialName("satisfied_expectation") val satisfiedExpectation: Boolean,
    @SerialName("timestamp_ms") val timestampMs: Long? = null,
    @SerialName("timestamp_label") val timestampLabel: String? = null,
    @SerialName("latency_ms") val latencyMs: Double = 0.0,
    @SerialName("ask_latency_ms") val askLatencyMs: Double = 0.0,
    @SerialName("audit_latency_ms") val auditLatencyMs: Double = 0.0,
    @SerialName("text_excerpt") val textExcerpt: String = "",
    @SerialName("raw_error") val rawError: String? = null,
    val retried: Boolean = false,
    @SerialName("case_count") val caseCount: Int = 0,
    @SerialName("case_pass_count") val casePassCount: Int = 0,
    @SerialName("zen_present") val zenPresent: Boolean = false,
    @SerialName("zen_state") val zenState: String? = null,
    @SerialName("zen_reason") val zenReason: String? = null,
    @SerialName("zen_severity") val zenSeverity: String? = null,
    @SerialName("zen_artifact_path") val zenArtifactPath: String? = null,
    @SerialName("workflow_url") val workflowUrl: String? = null,
    val artifacts: List<OpsArtifactDto> = emptyList(),
    val cases: List<SelfTestCaseSummaryDto> = emptyList(),
)

@Serializable
data class SelfTestCaseSummaryDto(
    val name: String,
    val status: OpsStatusDto,
    @SerialName("latency_ms") val latencyMs: Double = 0.0,
    val note: String? = null,
)

@Serializable
data class OpsArtifactDto(
    val name: String,
    val path: String? = null,
    val url: String? = null,
)

@Serializable
data class IssueSummaryDto(
    val todo: Int = 0,
    val wip: Int = 0,
    val blocked: Int = 0,
    val review: Int = 0,
    val done: Int = 0,
    val sources: List<IssueSourceSummaryDto> = emptyList(),
) {
    val active: Int
        get() = todo + wip + blocked + review
}

@Serializable
data class IssueSourceSummaryDto(
    val id: String,
    val label: String,
    val url: String? = null,
    val todo: Int = 0,
    val wip: Int = 0,
    val blocked: Int = 0,
    val review: Int = 0,
    val done: Int = 0,
) {
    val active: Int
        get() = todo + wip + blocked + review
}
