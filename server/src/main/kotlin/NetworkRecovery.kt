package net.sdfgsdfg

import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileSystemException
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/** Public transport failures request the existing DDNS owner; request/job recovery stays with its caller. */
internal class NetworkRecovery : AutoCloseable {
    private val enabled = File("/etc/systemd/system/cloudflare-ddns.service").isFile
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failures = Channel<String>(Channel.CONFLATED)
    private val log = LoggerFactory.getLogger(NetworkRecovery::class.java)

    init {
        scope.launch {
            var lastCheck = TimeSource.Monotonic.markNow() - 30.seconds
            for (failure in failures) {
                if (lastCheck.elapsedNow() < 30.seconds) continue
                lastCheck = TimeSource.Monotonic.markNow()
                try {
                    runInterruptible {
                        val process = ProcessBuilder("sudo", "-n", "systemctl", "start", "--no-block", "cloudflare-ddns.service")
                            .redirectErrorStream(true).start()
                        try {
                            check(process.waitFor(5, TimeUnit.SECONDS)) { "systemctl request timed out" }
                            check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText().take(500) }
                        } finally {
                            if (process.isAlive) process.destroyForcibly()
                        }
                    }
                    log.info("DDNS check requested: {}", failure)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failureToStart: Exception) {
                    log.warn("DDNS check could not be requested: {}", failureToStart.message)
                }
            }
        }
    }

    fun failed(call: ApplicationCall, cause: Throwable, transportTimeout: Boolean = false) {
        if (!enabled) return
        val client = call.clientInfo()
        if (!client.allowed || !client.trustedProxy) return
        val causes = generateSequence(cause) { it.cause }.take(16).toList()
        if (causes.any { it is StatusException || it is StatusRuntimeException || it is FileSystemException || it is FileNotFoundException }) return
        val io = causes.firstOrNull { it is IOException }
        if (io == null && !transportTimeout) return
        val failure = io ?: cause
        failures.trySend("${call.request.httpMethod.value} ${call.request.path()} ${failure::class.simpleName}: ${failure.message.orEmpty().take(160)}")
    }

    override fun close() {
        failures.close()
        scope.cancel()
    }

    companion object {
        val Key = AttributeKey<NetworkRecovery>("NetworkRecovery")
    }
}

internal fun Route.recoveringWebSocket(path: String, handler: suspend DefaultWebSocketServerSession.() -> Unit): Route =
    webSocket(path) {
        try {
            handler()
        } catch (failure: Throwable) {
            call.application.attributes.getOrNull(NetworkRecovery.Key)?.failed(call, failure)
            throw failure
        }
    }
