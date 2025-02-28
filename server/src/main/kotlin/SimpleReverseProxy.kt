import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
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

/**
 * Minimal Ktor Reverse Proxy
 *
 * Usage example in your routes:
 *
 *    val proxy = SimpleReverseProxy(httpClient, Url("http://localhost:3000"))
 *    routing {
 *        route("/{...}") {
 *            handle {
 *                proxy.proxy(call)
 *            }
 *        }
 *    }
 */
class SimpleReverseProxy(
    private val httpClient: HttpClient,
    private val targetBaseUrl: Url
) {
    suspend fun proxy(call: ApplicationCall) {
        val proxiedUrl = URLBuilder(targetBaseUrl).apply {
            encodedPath += call.request.uri
        }.build()

        val proxiedResponse = httpClient.request(proxiedUrl) {
            method = call.request.httpMethod
            headers {
                call.request.headers.forEach { key, values ->
                    if (key.lowercase() !in hopByHopHeaders) appendAll(key, values)
                }
                append(HttpHeaders.Accept, ContentType.Text.Html.toString())
                append(HttpHeaders.Accept, ContentType.Application.Json.toString())
                append(HttpHeaders.Accept, "*/*") // Allow any response type
            }
        }

        // ✅ Preserve Content-Encoding to avoid browser misinterpreting compressed data
        proxiedResponse.headers[HttpHeaders.ContentEncoding]?.let {
            call.response.header(HttpHeaders.ContentEncoding, it)
        }

        // ✅ Preserve Transfer-Encoding (chunked responses)
        proxiedResponse.headers[HttpHeaders.TransferEncoding]?.let {
            call.response.header(HttpHeaders.TransferEncoding, it)
        }

        // ✅ Preserve Content-Length (Prevents broken responses)
        proxiedResponse.headers[HttpHeaders.ContentLength]?.let {
            call.response.header(HttpHeaders.ContentLength, it)
        }

        // ✅ Preserve Content-Type
        call.response.header(HttpHeaders.ContentType, proxiedResponse.contentType()?.toString() ?: "text/html")

        call.respondBytesWriter {
            proxiedResponse.bodyAsChannel().copyAndClose(this)
        }
    }

    private val hopByHopHeaders = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade"
    )
}
