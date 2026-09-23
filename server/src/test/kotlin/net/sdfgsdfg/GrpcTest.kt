package net.sdfgsdfg

import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.sdfgsdfg.data.model.ModelAuditResultDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import kotlin.test.Test
import kotlin.test.assertEquals

class GrpcTest {
    @Test
    fun discoveryWarningAndMatchingRepairRemainVisibleAcrossConsumers() {
        val result = Json.decodeFromString<SelfTestResultDto>("""{
            "ok":true,"text_excerpt":"reply","satisfied_expectation":true,
            "model_audit":{"status":"WARN","unknown":["Routing"]},
            "zen_run_id":"autofix-20260923-090000",
            "zen":{"run_id":"autofix-20260923-090000","state":"in_progress"}
        }""")
        assertEquals(OpsStatusDto.WIP, result.toOpsSelfTestSummary().status)
        assertEquals(OpsStatusDto.WIP, result.toTestArtifact().status)
        assertEquals(OpsStatusDto.WARN, result.copy(zen = null, zenRunId = null).status)
        val verified = result.copy(modelAudit = ModelAuditResultDto("OK"), zen = buildJsonObject {
            put("run_id", result.zenRunId)
            put("state", "verified")
        })
        assertEquals(OpsStatusDto.OK, verified.status)
        assertEquals(OpsStatusDto.UNKNOWN, verified.copy(zenRunId = "autofix-20260923-090001").status)
        assertEquals(OpsStatusDto.UNKNOWN, verified.copy(modelAudit = null).status)
    }

    private val errorKindKey = Metadata.Key.of("server-py-error-kind", Metadata.ASCII_STRING_MARSHALLER)

    private fun unavailable(kind: String? = null): StatusException = Status.UNAVAILABLE
        .withDescription("DeepSeek remained busy")
        .asException(kind?.let { Metadata().apply { put(errorKindKey, it) } })

    @Test
    fun structuredUnavailableKindBecomesServiceUnavailable() {
        assertEquals(
            HttpStatusCode.ServiceUnavailable to "deepseek_server_busy",
            grpcAskFailure(unavailable("deepseek_server_busy")),
        )
    }

    @Test
    fun unclassifiedUnavailableKeepsGenericBadGatewayMapping() {
        assertEquals(
            HttpStatusCode.BadGateway to "unavailable",
            grpcAskFailure(unavailable()),
        )
    }

    @Test
    fun malformedUnavailableKindKeepsGenericBadGatewayMapping() {
        assertEquals(
            HttpStatusCode.BadGateway to "unavailable",
            grpcAskFailure(unavailable("DeepSeek Server Busy!")),
        )
    }

    @Test
    fun runtimeLockSerializesInProcessCallersBeforeFileLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            withWebhookRuntimeLock({}) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val second = async(Dispatchers.Default) { withWebhookRuntimeLock({}) {} }

        assertEquals(null, withTimeoutOrNull(50) { second.await() })
        release.complete(Unit)
        first.await()
        second.await()
    }
}
