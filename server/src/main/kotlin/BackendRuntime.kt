package net.sdfgsdfg

import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicBoolean

/** Process-owned resources: one construction seam, one deterministic shutdown seam. */
internal class BackendRuntime(
    val proxyHttp: HttpClient = proxyHttpClient(),
    val proxyWebSocket: HttpClient = proxyWebSocketClient(),
    val serverPy: ServerPyBridge = ServerPyBridge(),
    val opsSocket: OpsSocketHub = OpsSocketHub(),
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        opsSocket.close()
        serverPy.close()
        proxyWebSocket.close()
        proxyHttp.close()
    }
}
