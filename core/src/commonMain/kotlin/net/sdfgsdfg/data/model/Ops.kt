package net.sdfgsdfg.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpsSummaryDto(
    @SerialName("generated_at_ms") val generatedAtMs: Long,
    val repos: List<RepoHealthDto>,
)

@Serializable
data class OpsSocketMessageDto(
    val type: String,
    @SerialName("client_timestamp") val clientTimestamp: Long? = null,
    @SerialName("server_timestamp") val serverTimestamp: Long? = null,
    val summary: OpsSummaryDto? = null,
    @SerialName("issue_patch") val issuePatch: OpsIssuePatchDto? = null,
    @SerialName("run_event") val runEvent: OpsRunEventDto? = null,
    val message: String? = null,
)

@Serializable
data class OpsIssuePatchDto(
    @SerialName("generated_at_ms") val generatedAtMs: Long,
    val repos: List<RepoIssuePatchDto>,
)

@Serializable
data class RepoIssuePatchDto(
    val id: String,
    val issues: IssueSummaryDto,
)

fun IssueItemDto.isFreshForIssuePatch(nowMs: Long) =
    listOfNotNull(updatedAtMs, createdAtMs, completedAtMs).maxOrNull()?.let { nowMs - it in 0..15_000L } == true

@Serializable
data class OpsRunEventDto(
    @SerialName("repo_id") val repoId: String,
    val run: TestRunSummaryDto,
)

@Serializable
data class RepoHealthDto(
    val id: String,
    val name: String,
    val role: String,
    val status: OpsStatusDto,
    @SerialName("runtime_label") val runtimeLabel: String? = null,
    @SerialName("runtime_labels") val runtimeLabels: List<String> = emptyList(),
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
    @SerialName("coverage_pct") val coveragePct: Double? = null,
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
    @SerialName("server_py_self_test") val serverPySelfTest: SelfTestSummaryDto? = null,
    @SerialName("server_py_unit_test") val serverPyUnitTest: TestRunSummaryDto? = null,
    @SerialName("arcana_signals") val arcanaSignals: List<OpsSignalDto> = emptyList(),
)

@Serializable
data class ArcanaIngestDto(
    val status: OpsStatusDto = OpsStatusDto.WIP,
    val label: String = "arcana publisher",
    @SerialName("timestamp_ms") val timestampMs: Long? = null,
    @SerialName("duration_ms") val durationMs: Double? = null,
    @SerialName("coverage_pct") val coveragePct: Double? = null,
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
    val trash: Int = 0,
    val sources: List<IssueSourceSummaryDto> = emptyList(),
    val items: List<IssueItemDto> = emptyList(),
    val events: List<IssueEventDto> = emptyList(),
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
    val trash: Int = 0,
) {
    val active: Int
        get() = todo + wip + blocked + review
}

@Serializable
data class IssueItemDto(
    val id: String,
    val title: String = "",
    val status: String = "todo",
    val source: String = "arcana",
    @SerialName("source_label") val sourceLabel: String = "Arcana issues",
    val url: String? = null,
    val description: String = "",
    val notes: String = "",
    @SerialName("created_at_ms") val createdAtMs: Long? = null,
    @SerialName("updated_at_ms") val updatedAtMs: Long? = null,
    @SerialName("completed_at_ms") val completedAtMs: Long? = null,
)

@Serializable
data class IssueMutationRequestDto(
    val op: String,
    val repo: String,
    val id: String? = null,
    val status: String = "todo",
    val body: String? = null,
)

@Serializable
data class IssueEventDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("ts_ms") val tsMs: Long? = null,
    val event: String = "",
    val id: String = "",
    val title: String = "",
    val status: String = "",
    val actor: String? = null,
    val host: String? = null,
    val source: String = "arcana",
    @SerialName("source_label") val sourceLabel: String = "Arcana issues",
    val changes: Map<String, IssueEventChangeDto> = emptyMap(),
)

@Serializable
data class IssueEventChangeDto(
    @SerialName("from") val fromValue: String? = null,
    @SerialName("to") val toValue: String? = null,
)
