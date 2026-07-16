package net.sdfgsdfg.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val OPS_CAPABILITY_ISSUES_WRITE = "issues:write"
const val OPS_CAPABILITY_SESSIONS_RUN = "sessions:run"
const val ARCANA_PYRAMID_RUN_LABEL = "q arcana full pyramid"

val arcanaTestLayerKeys = listOf("unit", "integration", "e2e", "benchmarks")

fun arcanaLayerDisplayName(layer: String) = when (layer) {
    "pyramid" -> "Pyramid"
    "unit" -> "Unit"
    "integration" -> "Integration"
    "e2e" -> "E2E"
    "benchmarks" -> "Benchmarks"
    else -> null
}

fun arcanaLayerArtifactName(layer: String) = "arcana-$layer.json"

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
    @SerialName("workspace_command") val workspaceCommand: OpsWorkspaceCommandDto? = null,
    @SerialName("workspace_event") val workspaceEvent: OpsWorkspaceEventDto? = null,
    @SerialName("session_command") val sessionCommand: OpsSessionCommandDto? = null,
    @SerialName("session_event") val sessionEvent: OpsSessionEventDto? = null,
    val message: String? = null,
)

@Serializable
data class OpsWorkspaceCommandDto(
    @SerialName("request_id") val requestId: String,
    val action: OpsWorkspaceActionDto,
    @SerialName("repository_id") val repositoryId: Long? = null,
)

@Serializable
enum class OpsWorkspaceActionDto {
    @SerialName("list_repositories")
    LIST_REPOSITORIES,

    @SerialName("select_repository")
    SELECT_REPOSITORY,
}

@Serializable
data class OpsWorkspaceEventDto(
    @SerialName("request_id") val requestId: String,
    val kind: OpsWorkspaceEventKindDto,
    val status: OpsWorkspaceEventStatusDto,
    val message: String? = null,
    val progress: Int? = null,
    val repositories: List<OpsRepositoryDto> = emptyList(),
    @SerialName("repository_id") val repositoryId: Long? = null,
    @SerialName("workspace_id") val workspaceId: String? = null,
)

@Serializable
enum class OpsWorkspaceEventKindDto {
    @SerialName("repositories")
    REPOSITORIES,

    @SerialName("sync")
    SYNC,
}

@Serializable
enum class OpsWorkspaceEventStatusDto {
    @SerialName("loading")
    LOADING,

    @SerialName("ready")
    READY,

    @SerialName("initializing")
    INITIALIZING,

    @SerialName("syncing")
    SYNCING,

    @SerialName("synchronized")
    SYNCHRONIZED,

    @SerialName("error")
    ERROR,
}

@Serializable
data class OpsRepositoryDto(
    val id: Long,
    val name: String,
    val owner: String,
    @SerialName("full_name") val fullName: String,
    val description: String? = null,
    val language: String? = null,
    val stars: Int = 0,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("default_branch") val defaultBranch: String,
    @SerialName("private") val isPrivate: Boolean = false,
)

@Serializable
enum class OpsArcanaModeDto {
    @SerialName("workspace")
    WORKSPACE,

    @SerialName("issues")
    ISSUES,

    @SerialName("general")
    GENERAL,
}

@Serializable
data class OpsSessionCommandDto(
    @SerialName("request_id") val requestId: String,
    val action: OpsSessionActionDto,
    @SerialName("workspace_id") val workspaceId: String? = null,
    val agent: OpsAgentDto? = null,
    @SerialName("runtime_id") val runtimeId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val text: String? = null,
    @SerialName("after_sequence") val afterSequence: Long? = null,
    val model: String? = null,
    @SerialName("no_pace") val noPace: Boolean? = null,
    @SerialName("pace_min_seconds") val paceMinSeconds: Double? = null,
    @SerialName("pace_max_seconds") val paceMaxSeconds: Double? = null,
    val auto: Boolean? = null,
    @SerialName("index_sync") val indexSync: Boolean? = null,
    @SerialName("arcana_mode") val arcanaMode: OpsArcanaModeDto? = null,
)

@Serializable
enum class OpsSessionActionDto {
    @SerialName("list_sessions")
    LIST_SESSIONS,

    @SerialName("create_session")
    CREATE_SESSION,

    @SerialName("resume_session")
    RESUME_SESSION,

    @SerialName("attach_session")
    ATTACH_SESSION,

    @SerialName("input")
    INPUT,

    @SerialName("interrupt")
    INTERRUPT,

    @SerialName("stop")
    STOP,

    @SerialName("pacing_profile")
    PACING_PROFILE,
}

@Serializable
enum class OpsAgentDto {
    @SerialName("arcana")
    ARCANA,

    @SerialName("codex")
    CODEX,
}

@Serializable
data class OpsSessionEventDto(
    @SerialName("request_id") val requestId: String? = null,
    val kind: OpsSessionEventKindDto,
    @SerialName("runtime_id") val runtimeId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("workspace_id") val workspaceId: String? = null,
    val agent: OpsAgentDto? = null,
    val sequence: Long? = null,
    @SerialName("timestamp_ms") val timestampMs: Long? = null,
    val state: OpsSessionStateDto? = null,
    val channel: OpsSessionChannelDto? = null,
    val text: String? = null,
    val structured: OpsStructuredEventDto? = null,
    val sessions: List<OpsSessionSummaryDto> = emptyList(),
    val pacing: OpsPacingProfileDto? = null,
    val replay: Boolean = false,
    @SerialName("exit_code") val exitCode: Int? = null,
)

@Serializable
data class OpsStructuredEventDto(
    val version: Int = 1,
    val type: String,
    val phase: String,
    val schema: String? = null,
    val round: Int? = null,
    val payload: JsonObject,
)

@Serializable
enum class OpsSessionEventKindDto {
    @SerialName("sessions")
    SESSIONS,

    @SerialName("lifecycle")
    LIFECYCLE,

    @SerialName("stream")
    STREAM,

    @SerialName("structured")
    STRUCTURED,

    @SerialName("error")
    ERROR,

    @SerialName("pacing_profile")
    PACING_PROFILE,
}

@Serializable
data class OpsPacingProfileDto(
    val ranges: List<OpsPacingRangeDto> = emptyList(),
)

@Serializable
data class OpsPacingRangeDto(
    val provider: String,
    @SerialName("min_seconds") val minSeconds: Double,
    @SerialName("max_seconds") val maxSeconds: Double,
)

@Serializable
enum class OpsSessionStateDto {
    @SerialName("starting")
    STARTING,

    @SerialName("ready")
    READY,

    @SerialName("running")
    RUNNING,

    @SerialName("ongoing")
    ONGOING,

    @SerialName("awaiting_acceptance")
    AWAITING_ACCEPTANCE,

    @SerialName("concluded")
    CONCLUDED,

    @SerialName("interrupted")
    INTERRUPTED,

    @SerialName("exited")
    EXITED,

    @SerialName("failed")
    FAILED,

    @SerialName("stopped")
    STOPPED,
}

val OpsSessionStateDto.isActiveRuntime get() = this == OpsSessionStateDto.STARTING ||
    this == OpsSessionStateDto.READY || this == OpsSessionStateDto.RUNNING ||
    this == OpsSessionStateDto.AWAITING_ACCEPTANCE

@Serializable
enum class OpsSessionChannelDto {
    @SerialName("system")
    SYSTEM,

    @SerialName("stdin")
    STDIN,

    @SerialName("stdout")
    STDOUT,

    @SerialName("stderr")
    STDERR,
}

@Serializable
data class OpsSessionSummaryDto(
    @SerialName("session_id") val sessionId: String,
    val agent: OpsAgentDto,
    val title: String,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("repository_id") val repositoryId: Long? = null,
    @SerialName("workspace_name") val workspaceName: String? = null,
    @SerialName("runtime_id") val runtimeId: String? = null,
    val state: OpsSessionStateDto? = null,
    val detail: String? = null,
    @SerialName("changes_known") val changesKnown: Boolean = false,
    @SerialName("has_changes") val hasChanges: Boolean = false,
)

@Serializable
data class OpsIssuePatchDto(
    @SerialName("generated_at_ms") val generatedAtMs: Long,
    val repos: List<RepoIssuePatchDto>,
)

@Serializable
data class OpsViewerDto(
    @SerialName("user_id") val userId: String = "guest",
    @SerialName("display_name") val displayName: String = "guest",
    val role: String = "guest",
    val proofs: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("issue_write") val issueWrite: Boolean = false,
    @SerialName("client_hint") val clientHint: String? = null,
    val source: String? = null,
)

@Serializable
data class OpsGithubDeviceStartDto(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = 5,
)

@Serializable
data class OpsGithubDevicePollDto(
    @SerialName("device_code") val deviceCode: String,
)

@Serializable
data class OpsGithubTokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    val scope: String? = null,
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
    @SerialName("artifact_url") val artifactUrl: String? = null,
    @SerialName("coverage_pct") val coveragePct: Double? = null,
)

@Serializable
enum class TestArtifactKindDto {
    CASES,
    MODEL_SELECTORS,
}

@Serializable
data class TestArtifactDto(
    val label: String,
    val status: OpsStatusDto = OpsStatusDto.UNKNOWN,
    @SerialName("timestamp_ms") val timestampMs: Long? = null,
    @SerialName("duration_ms") val durationMs: Double? = null,
    val detail: String? = null,
    val url: String? = null,
    @SerialName("coverage_pct") val coveragePct: Double? = null,
    val kind: TestArtifactKindDto = TestArtifactKindDto.CASES,
    val paths: String? = null,
    val summary: String? = null,
    @SerialName("output_tail") val outputTail: String? = null,
    @SerialName("source_revision") val sourceRevision: String? = null,
    @SerialName("ledger_sha") val ledgerSha: String? = null,
    val cases: List<TestCaseDto> = emptyList(),
)

@Serializable
data class TestCaseDto(
    val name: String,
    val status: OpsStatusDto,
    val scope: String? = null,
    @SerialName("duration_ms") val durationMs: Double? = null,
    val detail: String? = null,
    val url: String? = null,
    val contracts: List<TestContractRefDto> = emptyList(),
)

@Serializable
data class TestContractRefDto(
    val id: String,
    val subsystem: String? = null,
    @SerialName("subsystem_name") val subsystemName: String? = null,
    @SerialName("subsystem_purpose") val subsystemPurpose: String? = null,
    val capability: String? = null,
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
    val archive: Int = 0,
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
    val archive: Int = 0,
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
