package net.sdfgsdfg

import io.grpc.ManagedChannelBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.util.reflect.TypeInfo
import kotlinx.serialization.Serializable
import rpc.BotGrpcKt
import rpc.BotOuterClass

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

        val req = BotOuterClass.AskRequest.newBuilder()
            .setPrompt(body.prompt)
            .setModel(body.model ?: "gpt-4")
            .build()

        val reply = botStub.ask(req)

        call.response.status(HttpStatusCode.OK)
        call.respond(
            message = AskReplyDto(reply.text),
            typeInfo = TypeInfo(String::class)
        )
    }

    // ---- TODO:   next up ----------------------------------
    // post("/api/ask/stream") { … }     ← server‑streaming GPT chunks
    // webSocket("/api/audio") { … }     ← bidirectional AudioChat frames
}
