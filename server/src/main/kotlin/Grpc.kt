package net.sdfgsdfg

import io.grpc.ManagedChannelBuilder
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.reflect.TypeInfo
import kotlinx.serialization.Serializable
import rpc.BotGrpcKt
import rpc.BotOuterClass.AskRequest

/* ---------- DTOs exposed on the public REST surface ---------- */
@Serializable
data class AskRequestDto(val prompt: String, val model: String? = null)

@Serializable
data class AskReplyDto(val text: String)

/* ---------- single gRPC channel reused by all requests ---------- */
private val channel = ManagedChannelBuilder
    .forAddress("localhost", 1453)
    .usePlaintext()
    .build()
private val botStub = BotGrpcKt.BotCoroutineStub(channel)

/*
 * ---------- REST → gRPC unary bridge ----------
 */
fun Route.grpc() {
    post("/api/ask") {
        val body = call.receive<AskRequestDto>()
        val req = AskRequest.newBuilder()
            .setPrompt(body.prompt)
            .setModel(body.model ?: "")
            .build()
        val reply = botStub.ask(req)
        call.respond(AskReplyDto(reply.text), TypeInfo(AskReplyDto::class))
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
}
