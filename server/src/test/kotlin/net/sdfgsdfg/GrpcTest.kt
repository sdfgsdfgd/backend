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
import kotlin.test.Test
import kotlin.test.assertEquals

class GrpcTest {
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
