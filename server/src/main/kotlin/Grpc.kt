package net.sdfgsdfg

import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.sdfgsdfg.data.model.AskReplyDto
import net.sdfgsdfg.data.model.AskRequestDto
import net.sdfgsdfg.data.model.OpsPacingProfileDto
import net.sdfgsdfg.data.model.OpsPacingRangeDto
import net.sdfgsdfg.data.model.SelfTestCaseDto
import net.sdfgsdfg.data.model.SelfTestRequestDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import rpc.BotGrpcKt
import rpc.BotOuterClass.AskRequest
import rpc.BotOuterClass.PacingRequest
import rpc.BotOuterClass.SelfTestRequest
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds


/* ---------- single gRPC channel reused by all requests ---------- */

// ---
// todo: remove, if below channel works on both platforms TODO: Test on OSX for debugging [ WIP ] & Linux for prod [ Done ]
//private val channel_old = ManagedChannelBuilder
//    .forAddress("localhost", 1453)
//    .usePlaintext()
//    .build()
// ---

// Reuse the same JSON shape the ContentNegotiation plugin uses
private val heartbeatJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val selfTestResultFile = File(resolveLogDir(), "server-py-selftest.json")
private val webhookRuntimeLockFile = File(resolveLogDir(), "webhook-runtime.lock")
private val zenAutofixStatusFile = File("/home/x/Desktop/py/server_py/artifacts/zen/autofix-status.json")
private const val DEFAULT_SELFTEST_PROMPT = "respond with zitchdog"
private const val DEFAULT_SELFTEST_EXPECT = "zitchdog"
private const val ASK_JOB_RESULT_TTL_MS = 24L * 60 * 60 * 1000
private const val ASK_JOB_RUNNING_TTL_MS = 2L * 60 * 60 * 1000
private const val ZEN_STATUS_ASSOCIATION_WINDOW_MS = 2L * 60 * 60 * 1000
private const val ARCANA_RPC_ID_HEADER = "X-Arcana-Rpc-Id"

/** One process bridge; request-id jobs remain claimable after an HTTP writer disconnects. */
internal class ServerPyBridge : AutoCloseable {
    private val linuxGroup = if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) EpollEventLoopGroup() else null
    private val channel: ManagedChannel = linuxGroup?.let { group ->
        NettyChannelBuilder
            .forAddress(DomainSocketAddress("/tmp/server_py/server_py.sock"))
            .eventLoopGroup(group)
            .channelType(EpollDomainSocketChannel::class.java)
            .usePlaintext()
            .build()
    } ?: NettyChannelBuilder
        .forAddress("127.0.0.1", 1453)
        .usePlaintext()
        .build()
    internal val botStub = BotGrpcKt.BotCoroutineStub(channel)
    internal val askJobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal val askJobs = ConcurrentHashMap<String, AskJob>()
    private val closed = AtomicBoolean()

    internal data class AskJob(
        val rpcId: String,
        val backendId: String,
        val startedAtMs: Long,
        val deferred: Deferred<AskJobResult>,
    ) {
        @Volatile
        var completedAtMs: Long? = null
    }

    internal data class AskJobResult(
        val status: HttpStatusCode,
        val payload: String,
        val textChars: Int = 0,
    )

    internal suspend fun pacingProfile(): OpsPacingProfileDto = botStub
        .getPacing(PacingRequest.getDefaultInstance())
        .let { profile ->
            OpsPacingProfileDto(
                profile.rangesList.map { range ->
                    OpsPacingRangeDto(range.provider, range.minSeconds, range.maxSeconds)
                },
            )
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        askJobScope.cancel()
        askJobs.clear()
        try {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        } finally {
            linuxGroup?.shutdownGracefully()?.awaitUninterruptibly(5, TimeUnit.SECONDS)
        }
    }
}

private fun cleanRpcId(value: String?): String? =
    value?.trim()?.take(96)?.takeIf { it.isNotBlank() && it.all { ch -> ch.isLetterOrDigit() || ch in "._-" } }

private fun statusPayload(status: String, rpcId: String): String =
    heartbeatJson.encodeToString(mapOf("status" to status, "request_id" to rpcId))

private fun ServerPyBridge.pruneAskJobs() {
    val now = System.currentTimeMillis()
    askJobs.entries.removeIf { entry ->
        val job = entry.value
        val completedAt = job.completedAtMs
        (completedAt != null && now - completedAt > ASK_JOB_RESULT_TTL_MS) ||
            (completedAt == null && now - job.startedAtMs > ASK_JOB_RUNNING_TTL_MS)
    }
}

private fun ServerPyBridge.startAskJob(
    rpcId: String,
    backendId: String,
    req: AskRequest,
    wantTts: Boolean,
    log: org.slf4j.Logger,
): ServerPyBridge.AskJob {
    val startedAtMs = System.currentTimeMillis()
    val startedAtNs = System.nanoTime()
    fun elapsedMs() = (System.nanoTime() - startedAtNs) / 1_000_000
    val deferred = askJobScope.async {
        try {
            log.info("[gRPC][$backendId][$rpcId] job started")
            val reply = botStub.ask(req)
            log.info("[gRPC][$backendId][$rpcId] grpc completed elapsed_ms=${elapsedMs()} text_chars=${reply.text.length}")
            val payload = heartbeatJson.encodeToString(
                AskReplyDto(
                    text = reply.text,
                    ttsMp3 = if (wantTts) Base64.Default.encode(reply.ttsMp3.toByteArray()) else null
                )
            )
            log.info("[gRPC][$backendId][$rpcId] job result ready elapsed_ms=${elapsedMs()} bytes=${payload.toByteArray().size}")
            ServerPyBridge.AskJobResult(HttpStatusCode.OK, payload, reply.text.length)
        } catch (err: io.grpc.StatusException) {
            val (status, code) = when (err.status.code) {
                io.grpc.Status.Code.DEADLINE_EXCEEDED -> HttpStatusCode.GatewayTimeout to "timeout"
                io.grpc.Status.Code.UNAVAILABLE       -> HttpStatusCode.BadGateway to "unavailable"
                io.grpc.Status.Code.CANCELLED         -> HttpStatusCode.BadRequest to "cancelled"
                else                                  -> HttpStatusCode.BadGateway to "grpc_error"
            }
            val payload = heartbeatJson.encodeToString(
                mapOf(
                    "error" to code,
                    "detail" to (err.status.description ?: "gRPC ${err.status.code}"),
                    "status" to err.status.code.name,
                )
            )
            log.warn("[gRPC][$backendId][$rpcId] job grpc failed status=${err.status.code} elapsed_ms=${elapsedMs()} desc=${err.status.description}")
            ServerPyBridge.AskJobResult(status, payload)
        } catch (err: Throwable) {
            val payload = heartbeatJson.encodeToString(
                mapOf(
                    "error" to "proxy_error",
                    "detail" to (err.message ?: "unknown")
                )
            )
            log.error("[gRPC][$backendId][$rpcId] job failed elapsed_ms=${elapsedMs()}", err)
            ServerPyBridge.AskJobResult(HttpStatusCode.BadGateway, payload)
        }
    }
    val job = ServerPyBridge.AskJob(rpcId, backendId, startedAtMs, deferred)
    deferred.invokeOnCompletion { cause ->
        job.completedAtMs = System.currentTimeMillis()
        if (cause == null) {
            log.info("[gRPC][$backendId][$rpcId] job completed elapsed_ms=${elapsedMs()}")
        } else {
            log.warn("[gRPC][$backendId][$rpcId] job ended with coroutine cause=${cause::class.qualifiedName}: ${cause.message}")
        }
    }
    return job
}

/*
 * ---------- REST → gRPC unary bridge ----------
 */
internal fun Route.grpc(serverPyBridge: ServerPyBridge, opsSocketHub: OpsSocketHub) {
    get("/api/ask/result/{id}") {
        serverPyBridge.pruneAskJobs()
        val rpcId = cleanRpcId(call.parameters["id"])
        if (rpcId == null) {
            call.respondText(statusPayload("bad_request", ""), ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val job = serverPyBridge.askJobs[rpcId]
        if (job == null) {
            application.log.info("[gRPC][$rpcId] result claim unknown")
            call.respondText(statusPayload("unknown", rpcId), ContentType.Application.Json, HttpStatusCode.NotFound)
            return@get
        }
        call.response.headers.append(ARCANA_RPC_ID_HEADER, rpcId)
        call.response.headers.append("X-Backend-Ask-Id", job.backendId)
        if (!job.deferred.isCompleted) {
            application.log.info("[gRPC][${job.backendId}][$rpcId] result claim running")
            call.respondText(statusPayload("running", rpcId), ContentType.Application.Json, HttpStatusCode.Accepted)
            return@get
        }
        val result = job.deferred.await()
        application.log.info(
            "[gRPC][${job.backendId}][$rpcId] result claimed status=${result.status.value} " +
                "bytes=${result.payload.toByteArray().size} text_chars=${result.textChars}"
        )
        call.respondText(result.payload, ContentType.Application.Json, result.status)
    }

    post("/api/ask") {
        serverPyBridge.pruneAskJobs()
        val heartbeatSeconds = 20.seconds
        val body = call.receive<AskRequestDto>()
        val requestId = "ask-${UUID.randomUUID().toString().take(8)}"
        val rpcId = cleanRpcId(body.requestId) ?: cleanRpcId(call.request.headers[ARCANA_RPC_ID_HEADER]) ?: "srv-${UUID.randomUUID()}"
        val startedAt = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
        val client = call.clientInfo()
        application.log.info(
            "[gRPC][$requestId][$rpcId] init POST /api/ask model=${body.model.orEmpty()} " +
                "new_chat=${body.newChat} want_tts=${body.wantTts} deepseek=${body.deepseek} " +
                "deepseek_search=${body.deepseekSearch} one_time=${body.oneTime} no_pace=${body.noPace} session_id=${body.sessionId.orEmpty()} " +
                "pace=${body.paceMinSeconds ?: 0}–${body.paceMaxSeconds ?: 0}s " +
                "new_tab=${body.newTab} end_session=${body.endSession} prompt_chars=${body.prompt.length} " +
                "client=${client.clientIp} remote=${client.remoteIp} cf=${call.request.headers["CF-Connecting-IP"].orEmpty()} " +
                "xff=${call.request.headers["X-Forwarded-For"].orEmpty()} ray=${call.request.headers["CF-Ray"].orEmpty()} " +
                "ua=${call.request.headers["User-Agent"].orEmpty()} " +
                "prompt=${body.prompt.take(30)}..."
        )
        val req = AskRequest.newBuilder()
            .setPrompt(body.prompt)
            .setModel(body.model.orEmpty())
            .setNewChat(body.newChat)
            .setWantTts(body.wantTts)
            .setDeepseek(body.deepseek)
            .setDeepseekSearch(body.deepseekSearch)
            .setOneTime(body.oneTime)
            .setNoPace(body.noPace)
            .setPaceMinSeconds(body.paceMinSeconds ?: 0.0)
            .setPaceMaxSeconds(body.paceMaxSeconds ?: 0.0)
            .setSessionId(body.sessionId.orEmpty())
            .setNewTab(body.newTab)
            .setEndSession(body.endSession)
            .build()
        val job = serverPyBridge.askJobs.computeIfAbsent(rpcId) {
            serverPyBridge.startAskJob(rpcId, requestId, req, body.wantTts, application.log)
        }

        call.response.headers.append(ARCANA_RPC_ID_HEADER, rpcId)
        call.response.headers.append("X-Backend-Ask-Id", job.backendId)
        call.respondTextWriter(contentType = ContentType.Application.Json) {
            var heartbeatCount = 0
            try {
                while (!job.deferred.isCompleted) {
                    heartbeatCount += 1
                    try {
                        application.log.info("[gRPC][$requestId][$rpcId] heartbeat#$heartbeatCount write elapsed_ms=${elapsedMs()}")
                        write(" \n")        // legal JSON whitespace keeps Cloudflare happy
                        flush()
                        application.log.info("[gRPC][$requestId][$rpcId] heartbeat#$heartbeatCount flushed elapsed_ms=${elapsedMs()}")
                    } catch (err: CancellationException) {
                        application.log.warn(
                            "[gRPC][$requestId][$rpcId] heartbeat#$heartbeatCount cancelled elapsed_ms=${elapsedMs()} " +
                                "cause=${err::class.simpleName}: ${err.message}"
                        )
                        throw err
                    } catch (err: Throwable) {
                        application.log.error(
                            "[gRPC][$requestId][$rpcId] heartbeat#$heartbeatCount write/flush failed elapsed_ms=${elapsedMs()} " +
                                "cause=${err::class.qualifiedName}: ${err.message}",
                            err
                        )
                        throw err
                    }
                    if (withTimeoutOrNull(heartbeatSeconds.inWholeMilliseconds) { job.deferred.await(); true } == true) {
                        break
                    }
                }
                val result = job.deferred.await()
                application.log.info("[gRPC][$requestId][$rpcId] final payload write elapsed_ms=${elapsedMs()} bytes=${result.payload.toByteArray().size} status=${result.status.value}")
                write(result.payload)
                flush()
                application.log.info("[gRPC][$requestId][$rpcId] final payload flushed elapsed_ms=${elapsedMs()}")
            } catch (err: CancellationException) {
                application.log.warn(
                    "[gRPC][$requestId][$rpcId] writer cancelled elapsed_ms=${elapsedMs()} heartbeats=$heartbeatCount " +
                        "cause=${err::class.simpleName}: ${err.message}"
                )
                throw err
            } catch (err: Throwable) {
                application.log.error(
                    "[gRPC][$requestId][$rpcId] writer failed elapsed_ms=${elapsedMs()} heartbeats=$heartbeatCount " +
                        "cause=${err::class.qualifiedName}: ${err.message}",
                    err
                )
                throw err
            } finally {
                application.log.info("[gRPC][$requestId][$rpcId] writer finished elapsed_ms=${elapsedMs()} heartbeats=$heartbeatCount job_done=${job.deferred.isCompleted}")
            }
        }
    }


    get("/api/ask/stream") {
        application.log.info("[gRPC] [init] streaming request")

        val prompt = call.request.queryParameters["q"] ?: return@get call.respond(HttpStatusCode.BadRequest, TypeInfo(String::class))
        val req = AskRequest.newBuilder().setPrompt(prompt).build()

        @Suppress("BlockingMethodInNonBlockingContext") // Ktor `write/flush` are suspend-based, IDE warning is a false positive
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            write(": keep-alive\n\n")      // first  bytes → resets Netty timer
            flush()

            serverPyBridge.botStub.askStream(req).collect { chunk ->
                application.log.info("[gRPC] streaming collected chunk --> ${chunk.text.take(14)}...${chunk.text.takeLast(14)}")

                write("data:${chunk.text}\n\n")
                flush()
            }

            application.log.info("[gRPC] < streaming COLLECT ENDED >")
        }

        application.log.info("[gRPC] < streaming ENDED >")
    }

    post("/api/selftest/run") {
        val body = call.receiveNullable<SelfTestRequestDto>()
        application.log.info("[gRPC] [selftest] POST /api/selftest/run prompt='${body?.prompt ?: DEFAULT_SELFTEST_PROMPT}'")
        val workflowUrl = body?.workflowUrl?.takeIf { it.isNotBlank() }
        val headSha = body?.headSha?.takeIf { it.isNotBlank() }
        val req = SelfTestRequest.newBuilder()
            .setPrompt(body?.prompt?.takeIf { it.isNotBlank() } ?: DEFAULT_SELFTEST_PROMPT)
            .setExpectSubstr(body?.expectSubstr?.takeIf { it.isNotBlank() } ?: DEFAULT_SELFTEST_EXPECT)
            .setModel(body?.model.orEmpty())
            .setNewChat(body?.newChat ?: false)
            .setWorkflowUrl(workflowUrl.orEmpty())
            .setHeadSha(headSha.orEmpty())
            .build()

        application.log.info("[gRPC] [selftest] waiting for webhook runtime lock path='${webhookRuntimeLockFile.absolutePath}'")
        withWebhookRuntimeLock(onAcquired = { waitedMs ->
            application.log.info("[gRPC] [selftest] webhook runtime lock acquired waited_ms=$waitedMs")
        }) {
            val result = runCatching { serverPyBridge.botStub.selfTest(req) }
            val dto = result.fold(
                onSuccess = {
                    SelfTestResultDto(
                        ok = it.ok,
                        textExcerpt = it.textExcerpt,
                        rawError = it.rawError.takeIf(String::isNotBlank),
                        latencyMs = it.latencyMs,
                        askLatencyMs = it.askLatencyMs,
                        auditLatencyMs = it.auditLatencyMs,
                        satisfiedExpectation = it.satisfiedExpectation,
                        retried = it.retried,
                        cases = it.casesList.map { case ->
                            SelfTestCaseDto(
                                name = case.name,
                                ok = case.ok,
                                latencyMs = case.latencyMs,
                                note = case.note.takeIf(String::isNotBlank)
                            )
                        },
                        workflowUrl = workflowUrl,
                        headSha = headSha,
                        timestampMs = System.currentTimeMillis()
                    )
                },
                onFailure = { err ->
                    application.log.error("[gRPC] [selftest] failed", err)
                    SelfTestResultDto(
                        ok = false,
                        textExcerpt = "",
                        rawError = err.message ?: err::class.simpleName,
                        latencyMs = 0.0,
                        satisfiedExpectation = false,
                        retried = false,
                        workflowUrl = workflowUrl,
                        headSha = headSha,
                        timestampMs = System.currentTimeMillis()
                    )
                }
            )

            val enriched = dto.withZenStatus()
            persistSelfTestResult(enriched, opsSocketHub)
            call.respond(enriched)
        }
    }

    get("/api/selftest/status") {
        val saved = loadLastSelfTestResult()
        if (saved == null) {
            call.respond(HttpStatusCode.NoContent, "No self-test run recorded")
        } else {
            call.respond(saved.withZenStatus())
        }
    }
}

private fun persistSelfTestResult(result: SelfTestResultDto, opsSocketHub: OpsSocketHub) {
    runCatching {
        selfTestResultFile.writeText(heartbeatJson.encodeToString(result))
        appendRunHistory(serverPySelfTestHistoryFile, result.toOpsSelfTestSummary().toRunSummary())
        opsSocketHub.broadcastSummary()
    }.onFailure {
        println("[gRPC] [selftest] failed to persist result: ${it.message}")
    }
}

private fun loadLastSelfTestResult(): SelfTestResultDto? = runCatching {
    if (!selfTestResultFile.exists()) {
        null
    } else {
        heartbeatJson.decodeFromString<SelfTestResultDto>(selfTestResultFile.readText())
    }
}.getOrNull()

private suspend fun <T> withWebhookRuntimeLock(onAcquired: (Long) -> Unit, block: suspend () -> T): T {
    val started = System.nanoTime()
    val file = withContext(Dispatchers.IO) {
        webhookRuntimeLockFile.parentFile?.mkdirs()
        RandomAccessFile(webhookRuntimeLockFile, "rw")
    }
    try {
        val lock = withContext(Dispatchers.IO) { file.channel.lock() }
        try {
            onAcquired((System.nanoTime() - started) / 1_000_000)
            return block()
        } finally {
            withContext(Dispatchers.IO) { lock.release() }
        }
    } finally {
        withContext(Dispatchers.IO) { file.close() }
    }
}

private fun loadZenAutofixStatus(): JsonObject? = runCatching {
    if (!zenAutofixStatusFile.exists()) {
        null
    } else {
        heartbeatJson.parseToJsonElement(zenAutofixStatusFile.readText()).jsonObject
    }
}.getOrNull()

private fun JsonObject.zenTimestampMs(): Long? =
    listOf("finished_at", "started_at").firstNotNullOfOrNull { key ->
        get(key)?.jsonPrimitive?.contentOrNull?.let { value ->
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        }
    }

private fun JsonObject.belongsToSelfTest(timestampMs: Long): Boolean {
    val zenMs = zenTimestampMs() ?: return true
    return timestampMs <= 0L || abs(zenMs - timestampMs) <= ZEN_STATUS_ASSOCIATION_WINDOW_MS
}

private fun SelfTestResultDto.withZenStatus(): SelfTestResultDto {
    val latestZen = loadZenAutofixStatus()?.takeIf { it.belongsToSelfTest(timestampMs) }
    return copy(zen = latestZen)
}
