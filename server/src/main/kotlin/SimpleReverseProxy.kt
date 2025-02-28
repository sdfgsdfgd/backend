import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
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
            }
            if (call.request.httpMethod in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
                setBody(call.receiveChannel())
            }
        }

        // Preserve headers to ensure correct rendering
        call.response.headers.append(HttpHeaders.ContentType, proxiedResponse.contentType()?.toString() ?: "text/html")
        proxiedResponse.headers[HttpHeaders.ContentEncoding]?.let {
            call.response.headers.append(HttpHeaders.ContentEncoding, it)
        }

        call.respondBytesWriter {
            proxiedResponse.bodyAsChannel().copyAndClose(this)
        }
    }

    private val hopByHopHeaders = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade"
    )
}
