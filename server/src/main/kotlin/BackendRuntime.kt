package net.sdfgsdfg

import io.ktor.client.HttpClient
import java.net.http.HttpClient as JavaHttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Process-owned resources: one construction seam, one deterministic shutdown seam. */
internal class BackendRuntime(
    val proxyHttp: HttpClient = proxyHttpClient(),
    val proxyWebSocket: HttpClient = proxyWebSocketClient(),
    // Requests retain their tighter deadlines; this client owns pooling and the outer connect ceiling.
    val opsHttp: JavaHttpClient = JavaHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    val requestEvents: RequestEvents = RequestEvents(),
    val serverPy: ServerPyBridge = ServerPyBridge(),
    val opsSocket: OpsSocketHub = OpsSocketHub(OpsSessionService(pacing = serverPy::pacingProfile)),
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        opsSocket.close()
        serverPy.close()
        requestEvents.close()
        opsHttp.close()
        proxyWebSocket.close()
        proxyHttp.close()
    }
}
