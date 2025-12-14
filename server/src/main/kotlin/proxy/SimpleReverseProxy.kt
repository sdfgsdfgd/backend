package proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.copyAndClose
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

class SimpleReverseProxy(
    private val httpClient: HttpClient,
    private val targetBaseUrl: Url
) {
    private val i = AtomicInteger(0)
    private val logger = LoggerFactory.getLogger("proxy.SimpleReverseProxy")

    /**
     * Hop-by-hop headers (per RFC 2616 section 13.5.1)
     * that we typically do NOT forward from client->server or server->client.
     */
    private val hopByHopHeaders = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade"
    )

    suspend fun proxy(call: ApplicationCall, hostHeader: String? = null) {
        val originalUri = call.request.uri
        val proxiedUrl = URLBuilder(targetBaseUrl).apply {
            encodedPath += originalUri
        }.build()

        logger.info(
            "--> [ {} ] host='{}' method={} uri='{}' -> {}",
            i.getAndIncrement(),
            hostHeader ?: call.request.host(),
            call.request.httpMethod.value,
            originalUri,
            proxiedUrl
        )
        call.request.headers.forEach { key, values ->
            logger.debug("Incoming header: {} -> {}", key, values)
        }

        val proxiedResponse: HttpResponse = httpClient.request(proxiedUrl) { // Forward request to the target (Next.js)
            method = call.request.httpMethod
            // Copy all client headers except hop-by-hop
            headers {
                call.request.headers.forEach { key, values ->
                    if (key.lowercase() !in hopByHopHeaders) {
                        appendAll(key, values)
                    }
                }
                // (Optionally) to explicitly add Accept:
                // accept(ContentType.Text.Html)
                // accept(ContentType.Application.Json)
                // accept(ContentType.Any)

                append("X-Forwarded-Host", call.request.host())
                append("X-Forwarded-Proto", if (call.request.origin.scheme == "https") "https" else "http")
                append("X-Forwarded-For", call.request.origin.remoteHost)
            }
        }

        // Log status and response headers from target
        logger.info("Received response from Next.js: ${proxiedResponse.status}")
        proxiedResponse.headers.forEach { key, values ->
            logger.debug("Response header from Next.js: {} -> {}", key, values)
        }

        // Copy selected response headers to the client
        // We'll do a simple approach: copy everything except hop-by-hop.
        // We also skip "Content-Length" and "Transfer-Encoding" so Ktor can re-chunk or compute length as needed.
        // If you want a true pass-through for chunked responses, see the notes below.
        proxiedResponse.headers.names().forEach { headerName ->
            val lowerHeader = headerName.lowercase()
            if (lowerHeader !in hopByHopHeaders &&
                lowerHeader != HttpHeaders.ContentLength.lowercase() &&
                lowerHeader != HttpHeaders.TransferEncoding.lowercase()
            ) {
                val values = proxiedResponse.headers.getAll(headerName).orEmpty()
                // Ktor's response.header() sets a single value at a time.
                // If there's multiple, append them.
                values.forEach { singleValue ->
                    call.response.header(headerName, singleValue)
                }
            }
        }

        // Content-Type fallback
        val contentTypeFromServer = proxiedResponse.contentType()
        val finalContentType = contentTypeFromServer?.toString() ?: "application/octet-stream"
        call.response.header(HttpHeaders.ContentType, finalContentType)

        // Write the response body - Ktor handles chunking or content-length automatically
        call.respondBytesWriter(status = proxiedResponse.status) {
            proxiedResponse.bodyAsChannel().copyAndClose(this)
        }
    }
}
