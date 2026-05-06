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
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.encodeBase64
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.sdfgsdfg.data.model.AskReplyDto
import net.sdfgsdfg.data.model.AskRequestDto
import net.sdfgsdfg.data.model.SelfTestCaseDto
import net.sdfgsdfg.data.model.SelfTestRequestDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import rpc.BotGrpcKt
import rpc.BotOuterClass.AskRequest
import rpc.BotOuterClass.SelfTestRequest
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds


/* ---------- single gRPC channel reused by all requests ---------- */

// ---
// todo: remove, if below channel works on both platforms TODO: Test on OSX for debugging [ WIP ] & Linux for prod [ Done ]
//private val channel_old = ManagedChannelBuilder
//    .forAddress("localhost", 1453)
//    .usePlaintext()
//    .build()
// ---

val isLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
private val linuxGroup by lazy { EpollEventLoopGroup() }   // reuse one pool

val channel: ManagedChannel = if (isLinux) {
    NettyChannelBuilder
        .forAddress(DomainSocketAddress("/tmp/server_py/server_py.sock"))
        .eventLoopGroup(linuxGroup)
        .channelType(EpollDomainSocketChannel::class.java)
        .usePlaintext()
        .build()
} else { // We're on OSX
    NettyChannelBuilder
        .forAddress("127.0.0.1", 1453)
        .usePlaintext()
        .build()
}

// Reuse the same JSON shape the ContentNegotiation plugin uses
private val heartbeatJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val botStub = BotGrpcKt.BotCoroutineStub(channel)
private val selfTestResultFile = File(resolveLogDir(), "server-py-selftest.json")
private val zenAutofixStatusFile = File("/home/x/Desktop/py/server_py/artifacts/zen/autofix-status.json")
private const val DEFAULT_SELFTEST_PROMPT = "respond with zitchdog"
private const val DEFAULT_SELFTEST_EXPECT = "zitchdog"

/*
 * ---------- REST → gRPC unary bridge ----------
 */
fun Route.grpc() {
    post("/api/ask") {
        val heartbeatSeconds = 20.seconds
        val body = call.receive<AskRequestDto>()
        val requestId = "ask-${UUID.randomUUID().toString().take(8)}"
        val startedAt = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
        application.log.info(
            "[gRPC][$requestId] init POST /api/ask model=${body.model.orEmpty()} " +
                "new_chat=${body.newChat} want_tts=${body.wantTts} deepseek=${body.deepseek} " +
                "deepseek_search=${body.deepseekSearch} prompt_chars=${body.prompt.length} " +
                "prompt=${body.prompt.take(30)}..."
        )
        val req = AskRequest.newBuilder()
            .setPrompt(body.prompt)
            .setModel(body.model.orEmpty())
            .setNewChat(body.newChat)
            .setWantTts(body.wantTts)
            .setDeepseek(body.deepseek)
            .setDeepseekSearch(body.deepseekSearch)
            .build()

        call.respondTextWriter(contentType = ContentType.Application.Json) {
            var heartbeatCount = 0
            try {
                coroutineScope {
                    val replyDeferred = async { botStub.ask(req) }
                    replyDeferred.invokeOnCompletion { cause ->
                        when (cause) {
                            null -> application.log.info("[gRPC][$requestId] grpc completed elapsed_ms=${elapsedMs()}")
                            is CancellationException -> application.log.warn(
                                "[gRPC][$requestId] grpc cancelled elapsed_ms=${elapsedMs()} " +
                                    "cause=${cause::class.simpleName}: ${cause.message}"
                            )
                            else -> application.log.warn(
                                "[gRPC][$requestId] grpc failed elapsed_ms=${elapsedMs()} " +
                                    "cause=${cause::class.qualifiedName}: ${cause.message}",
                                cause
                            )
                        }
                    }

                    while (replyDeferred.isActive) {
                        heartbeatCount += 1
                        try {
                            application.log.info("[gRPC][$requestId] heartbeat#$heartbeatCount write elapsed_ms=${elapsedMs()}")
                            write(" \n")        // legal JSON whitespace keeps Cloudflare happy
                            flush()
                            application.log.info("[gRPC][$requestId] heartbeat#$heartbeatCount flushed elapsed_ms=${elapsedMs()}")
                        } catch (err: CancellationException) {
                            application.log.warn(
                                "[gRPC][$requestId] heartbeat#$heartbeatCount cancelled elapsed_ms=${elapsedMs()} " +
                                    "cause=${err::class.simpleName}: ${err.message}"
                            )
                            throw err
                        } catch (err: Throwable) {
                            application.log.error(
                                "[gRPC][$requestId] heartbeat#$heartbeatCount write/flush failed elapsed_ms=${elapsedMs()} " +
                                    "cause=${err::class.qualifiedName}: ${err.message}",
                                err
                            )
                            throw err
                        }
                        delay(heartbeatSeconds)  // anything <<100s works
                    }

                    application.log.info("[gRPC][$requestId] awaiting grpc reply elapsed_ms=${elapsedMs()}")
                    val reply = try {
                        replyDeferred.await().also {
                            application.log.info(
                                "[gRPC][$requestId] grpc await returned elapsed_ms=${elapsedMs()} text_chars=${it.text.length}"
                            )
                        }
                    } catch (err: io.grpc.StatusException) {
                        val (status, code) = when (err.status.code) {
                            io.grpc.Status.Code.DEADLINE_EXCEEDED -> HttpStatusCode.GatewayTimeout to "timeout"
                            io.grpc.Status.Code.UNAVAILABLE       -> HttpStatusCode.BadGateway to "unavailable"
                            io.grpc.Status.Code.CANCELLED         -> HttpStatusCode.BadRequest to "cancelled"
                            else                                  -> HttpStatusCode.BadGateway to "grpc_error"
                        }
                        application.log.warn(
                            "[gRPC][$requestId] ask failed status=${err.status.code} " +
                                "desc=${err.status.description} elapsed_ms=${elapsedMs()}"
                        )
                        call.response.status(status)
                        val payload = heartbeatJson.encodeToString(
                            mapOf(
                                "error" to code,
                                "detail" to (err.status.description ?: "gRPC ${err.status.code}"),
                                "status" to err.status.code.name,
                            )
                        )
                        application.log.info("[gRPC][$requestId] grpc error payload write elapsed_ms=${elapsedMs()} bytes=${payload.toByteArray().size}")
                        write(payload)
                        flush()
                        application.log.info("[gRPC][$requestId] grpc error payload flushed elapsed_ms=${elapsedMs()}")
                        return@coroutineScope
                    } catch (err: Exception) {
                        application.log.error("[gRPC][$requestId] ask failed elapsed_ms=${elapsedMs()}", err)
                        call.response.status(HttpStatusCode.BadGateway)
                        val payload = heartbeatJson.encodeToString(
                            mapOf(
                                "error" to "proxy_error",
                                "detail" to (err.message ?: "unknown")
                            )
                        )
                        application.log.info("[gRPC][$requestId] proxy error payload write elapsed_ms=${elapsedMs()} bytes=${payload.toByteArray().size}")
                        write(payload)
                        flush()
                        application.log.info("[gRPC][$requestId] proxy error payload flushed elapsed_ms=${elapsedMs()}")
                        return@coroutineScope
                    }
                    application.log.info("[gRPC][$requestId] response received text_preview=${reply.text.take(30)}...${reply.text.takeLast(30)}")

                    val payload = heartbeatJson.encodeToString(
                        AskReplyDto(
                            text = reply.text,
                            ttsMp3 = if (body.wantTts) reply.ttsMp3.toByteArray().encodeBase64() else null
                        )
                    )
                    application.log.info("[gRPC][$requestId] final payload write elapsed_ms=${elapsedMs()} bytes=${payload.toByteArray().size}")
                    write(payload)
                    flush()
                    application.log.info("[gRPC][$requestId] final payload flushed elapsed_ms=${elapsedMs()}")
                }
            } catch (err: CancellationException) {
                application.log.warn(
                    "[gRPC][$requestId] writer cancelled elapsed_ms=${elapsedMs()} heartbeats=$heartbeatCount " +
                        "cause=${err::class.simpleName}: ${err.message}"
                )
                throw err
            } catch (err: Throwable) {
                application.log.error(
                    "[gRPC][$requestId] writer failed elapsed_ms=${elapsedMs()} heartbeats=$heartbeatCount " +
                        "cause=${err::class.qualifiedName}: ${err.message}",
                    err
                )
                throw err
            } finally {
                application.log.info("[gRPC][$requestId] writer finished elapsed_ms=${elapsedMs()} heartbeats=$heartbeatCount")
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

            botStub.askStream(req).collect { chunk ->
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
        val req = SelfTestRequest.newBuilder()
            .setPrompt(body?.prompt?.takeIf { it.isNotBlank() } ?: DEFAULT_SELFTEST_PROMPT)
            .setExpectSubstr(body?.expectSubstr?.takeIf { it.isNotBlank() } ?: DEFAULT_SELFTEST_EXPECT)
            .setModel(body?.model.orEmpty())
            .setNewChat(body?.newChat ?: false)
            .build()

        val result = runCatching { botStub.selfTest(req) }
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
                    timestampMs = System.currentTimeMillis()
                )
            }
        )

        val enriched = dto.withZenStatus()
        persistSelfTestResult(enriched)
        call.respond(enriched)
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

private fun persistSelfTestResult(result: SelfTestResultDto) {
    runCatching {
        selfTestResultFile.writeText(heartbeatJson.encodeToString(result))
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

private fun loadZenAutofixStatus(): JsonObject? = runCatching {
    if (!zenAutofixStatusFile.exists()) {
        null
    } else {
        heartbeatJson.parseToJsonElement(zenAutofixStatusFile.readText()).jsonObject
    }
}.getOrNull()

private fun SelfTestResultDto.withZenStatus(): SelfTestResultDto {
    val latestZen = loadZenAutofixStatus()
    return if (latestZen == null) this else copy(zen = latestZen)
}
