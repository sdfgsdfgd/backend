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
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.copyAndClose
import org.slf4j.LoggerFactory

class SimpleReverseProxy(
    private val httpClient: HttpClient,
    private val targetBaseUrl: Url
) {
    private val logger = LoggerFactory.getLogger("SimpleReverseProxy")

    /**
     * Hop-by-hop headers (per RFC 2616 section 13.5.1)
     * that we typically do NOT forward from client->server or server->client.
     */
    private val hopByHopHeaders = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade"
    )

    suspend fun proxy(call: ApplicationCall) {
        val originalUri = call.request.uri
        val proxiedUrl = URLBuilder(targetBaseUrl).apply {
            encodedPath += originalUri
        }.build()

        logger.info("=== Proxying request ===")
        logger.info("Incoming request URI: ${call.request.uri}")
        logger.info("Proxy target URL: $proxiedUrl")

        // Log incoming request headers:
        call.request.headers.forEach { key, values ->
            logger.debug("Incoming header: {} -> {}", key, values)
        }

        // Forward request to the target (Next.js)
        val proxiedResponse: HttpResponse = httpClient.request(proxiedUrl) {
            method = call.request.httpMethod
            // Copy all client headers except hop-by-hop
            headers {
                call.request.headers.forEach { key, values ->
                    if (key.lowercase() !in hopByHopHeaders) {
                        appendAll(key, values)
                    }
                }
                // (Optionally) If you want to explicitly add Accept:
                // accept(ContentType.Text.Html)
                // accept(ContentType.Application.Json)
                // accept(ContentType.Any)
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

        // Write the response body
        // This will let Ktor handle chunking or content-length automatically.
        call.respondBytesWriter(status = proxiedResponse.status) {
            proxiedResponse.bodyAsChannel().copyAndClose(this)
        }

        logger.info("=== End proxying request ===")
    }
}