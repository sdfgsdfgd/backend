package net.sdfgsdfg.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SelfTestRequestDto(
    val prompt: String? = null,
    @SerialName("expect_substr") val expectSubstr: String? = null,
    val model: String? = null,
    @SerialName("new_chat") val newChat: Boolean = false,
    @SerialName("workflow_url") val workflowUrl: String? = null,
    @SerialName("head_sha") val headSha: String? = null,
    @SerialName("zen_run_id") val zenRunId: String? = null,
)

@Serializable
data class SelfTestResultDto(
    val ok: Boolean,
    @SerialName("text_excerpt") val textExcerpt: String,
    @SerialName("raw_error") val rawError: String? = null,
    @SerialName("latency_ms") val latencyMs: Double = 0.0,
    @SerialName("ask_latency_ms") val askLatencyMs: Double = 0.0,
    @SerialName("audit_latency_ms") val auditLatencyMs: Double = 0.0,
    @SerialName("satisfied_expectation") val satisfiedExpectation: Boolean = false,
    val retried: Boolean = false,
    val cases: List<SelfTestCaseDto> = emptyList(),
    val zen: JsonObject? = null,
    @SerialName("workflow_url") val workflowUrl: String? = null,
    @SerialName("head_sha") val headSha: String? = null,
    @SerialName("timestamp_ms") val timestampMs: Long = 0L,
    @SerialName("model_audit") val modelAudit: ModelAuditResultDto? = null,
    @SerialName("zen_run_id") val zenRunId: String? = null,
) {
    val status: OpsStatusDto get() {
        val zenState = zen?.takeIf { !zenRunId.isNullOrBlank() && it["run_id"]?.jsonPrimitive?.contentOrNull == zenRunId }
            ?.get("state")?.jsonPrimitive?.contentOrNull
        return when {
            zenState in setOf("queued", "in_progress", "verifying", "committing", "pushing") -> OpsStatusDto.WIP
            !ok || !satisfiedExpectation || modelAudit?.status == "ERROR" -> OpsStatusDto.FAIL
            !zenRunId.isNullOrBlank() && zenState !in setOf("verified", "verified_and_pushed") -> if (zenState == null) OpsStatusDto.UNKNOWN else OpsStatusDto.FAIL
            modelAudit == null -> OpsStatusDto.UNKNOWN
            modelAudit.status == "WARN" || modelAudit.unknown.isNotEmpty() || modelAudit.discoveryErrors.isNotEmpty() -> OpsStatusDto.WARN
            modelAudit.status == "OK" -> OpsStatusDto.OK
            else -> OpsStatusDto.UNKNOWN
        }
    }
}

@Serializable
data class ModelAuditResultDto(
    val status: String,
    val unknown: List<String> = emptyList(),
    @SerialName("discovery_errors") val discoveryErrors: List<String> = emptyList(),
)

@Serializable
data class SelfTestCaseDto(
    val name: String,
    val ok: Boolean,
    @SerialName("latency_ms") val latencyMs: Double = 0.0,
    val note: String? = null,
)
