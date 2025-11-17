package net.sdfgsdfg.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SelfTestRequestDto(
    val prompt: String? = null,
    @SerialName("expect_substr") val expectSubstr: String? = null,
    val model: String? = null,
    @SerialName("new_chat") val newChat: Boolean = false,
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
    @SerialName("timestamp_ms") val timestampMs: Long = System.currentTimeMillis(),
)

@Serializable
data class SelfTestCaseDto(
    val name: String,
    val ok: Boolean,
    @SerialName("latency_ms") val latencyMs: Double = 0.0,
    val note: String? = null,
)
