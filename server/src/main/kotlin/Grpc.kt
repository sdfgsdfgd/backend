package net.sdfgsdfg

import net.sdfgsdfg.data.model.AskReplyDto
import net.sdfgsdfg.data.model.AskRequestDto
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.encodeBase64
import io.ktor.util.reflect.TypeInfo
import rpc.BotGrpcKt
import rpc.BotOuterClass.AskRequest

val isLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)

/* ---------- single gRPC channel reused by all requests ---------- */
// todo: _________________________________________________
// todo: remove, if below channel works on both platforms
private val channel_old = ManagedChannelBuilder
    .forAddress("localhost", 1453)
    .usePlaintext()
    .build()
// todo: _________________________________________________

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
            .setWantTts(body.wantTts)
            .build()
        val reply = botStub.ask(req)
        call.respond(
            message = AskReplyDto(
                text = reply.text,
                ttsMp3 = if (body.wantTts) reply.ttsMp3.toByteArray().encodeBase64() else null
            ),
            typeInfo = TypeInfo(AskReplyDto::class)
        )
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
