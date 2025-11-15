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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import net.sdfgsdfg.data.model.AskReplyDto
import net.sdfgsdfg.data.model.AskRequestDto
import net.sdfgsdfg.data.model.SelfTestRequestDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import rpc.BotGrpcKt
import rpc.BotOuterClass.AskRequest
import rpc.BotOuterClass.SelfTestRequest
import java.io.File
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
private const val DEFAULT_SELFTEST_PROMPT = "respond with zitchdog"
private const val DEFAULT_SELFTEST_EXPECT = "zitchdog"

/*
 * ---------- REST → gRPC unary bridge ----------
 */
fun Route.grpc() {
    post("/api/ask") {
        val heartbeatSeconds = 20.seconds
        val body = call.receive<AskRequestDto>()
        application.log.info("[gRPC] [init] POST /api/ask with prompt=${body.prompt.take(30)}...")
        val req = AskRequest.newBuilder()
            .setPrompt(body.prompt)
            .setModel(body.model.orEmpty())
            .setNewChat(body.newChat)
            .setWantTts(body.wantTts)
            .build()

        call.respondTextWriter(contentType = ContentType.Application.Json) {
            coroutineScope {
                val replyDeferred = async { botStub.ask(req) }

                while (replyDeferred.isActive) {
                    application.log.info("[gRPC] $heartbeatSeconds heartbeat sent while waiting for response")
                    write(" \n")        // legal JSON whitespace keeps Cloudflare happy
                    flush()
                    delay(heartbeatSeconds)  // anything <<100s works
                }

                val reply = replyDeferred.await()
                application.log.info("[gRPC] response received --> ${reply.text.take(30)}...${reply.text.takeLast(30)}")

                val payload = heartbeatJson.encodeToString(
                    AskReplyDto(
                        text = reply.text,
                        ttsMp3 = if (body.wantTts) reply.ttsMp3.toByteArray().encodeBase64() else null
                    )
                )
                write(payload)
                flush()
                application.log.info("[gRPC] < response flushed to client >")
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
                    satisfiedExpectation = it.satisfiedExpectation,
                    retried = it.retried,
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

        persistSelfTestResult(dto)
        call.respond(dto)
    }

    get("/api/selftest/status") {
        val saved = loadLastSelfTestResult()
        if (saved == null) {
            call.respond(HttpStatusCode.NoContent, "No self-test run recorded")
        } else {
            call.respond(saved)
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
