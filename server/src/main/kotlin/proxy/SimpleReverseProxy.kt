package proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.copyAndClose
import net.sdfgsdfg.RequestEventRecordedKey
import net.sdfgsdfg.RequestEvents
import org.slf4j.LoggerFactory
import java.net.URISyntaxException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

class SimpleReverseProxy(
    private val httpClient: HttpClient,
    private val targetBaseUrl: Url
) {
    private val i = AtomicInteger(0)
    private val logger = LoggerFactory.getLogger("proxy.SimpleReverseProxy")
    private val publicIpFile = Paths.get("/home/x/Desktop/SCRIPTS/public_ip.txt")
    private val publicIpCacheTtlMs = 30_000L
    @Volatile private var publicIpv4Cache: String? = null
    @Volatile private var publicIpv6Cache: String? = null
    @Volatile private var publicIpCacheAtMs: Long = 0

    /**
     * Hop-by-hop headers (per RFC 2616 section 13.5.1)
     * that we typically do NOT forward from client->server or server->client.
     */
    private val hopByHopHeaders = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade"
    )
    private val authProxyHeader = "x-webauth-user"

    suspend fun proxy(call: ApplicationCall, hostHeader: String? = null, matchedRule: String? = null) {
        val requestId = i.getAndIncrement()
        val startNs = System.nanoTime()
        call.attributes.put(RequestEventRecordedKey, true)
        val originalUri = call.request.uri
        val host = hostHeader ?: call.request.host()
        val methodValue = call.request.httpMethod.value
        val path = call.request.path()
        val rawQuery = call.request.queryString().takeIf { it.isNotBlank() }
        val ua = call.request.headers[HttpHeaders.UserAgent]
        val remote = resolveClientIp(call)
        val countryCode = call.request.headers["CF-IPCountry"]
            ?.takeIf { it.isNotBlank() && it != "XX" }
        val suspicion = detectSuspicious(rawQuery, ua)
        val suspiciousReason = suspicion?.first
        val suspiciousSeverity = suspicion?.second
        val trusted = isTrustedIp(remote)
        val allowlisted = RequestEvents.isAllowlisted(remote)
        if (!trusted && !allowlisted && RequestEvents.isBlacklisted(remote)) {
            RequestEvents.blacklist(remote, reason = null, countryCode = null)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            RequestEvents.record(
                ip = remote,
                host = host,
                method = methodValue,
                path = path,
                rawQuery = rawQuery,
                status = HttpStatusCode.Forbidden.value,
                latencyMs = elapsedMs.toInt(),
                ua = ua,
                matchedRule = matchedRule,
                requestId = requestId.toString(),
                suspiciousReason = "BLACKLISTED",
                severity = 4
            )
            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }
        if (!trusted && !allowlisted) {
            val exploitReason = detectExploitPath(path)
            if (suspiciousReason == "BOT_UA" || exploitReason != null) {
                val reason = exploitReason ?: "BOT_UA"
                RequestEvents.blacklist(remote, reason = reason, countryCode = countryCode)
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                RequestEvents.record(
                    ip = remote,
                    host = host,
                    method = methodValue,
                    path = path,
                    rawQuery = rawQuery,
                    status = HttpStatusCode.Forbidden.value,
                    latencyMs = elapsedMs.toInt(),
                    ua = ua,
                    matchedRule = matchedRule,
                    requestId = requestId.toString(),
                    suspiciousReason = reason,
                    severity = 4
                )
                call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
                return
            }
        }
        val proxiedUrl = try {
            URLBuilder(targetBaseUrl).apply {
                encodedPath = encodedPath.trimEnd('/') + path
                if (!rawQuery.isNullOrBlank()) {
                    encodedParameters.appendAll(parseQueryString(rawQuery, decode = false))
                }
            }.build()
        } catch (e: Exception) {
            if (isIllegalQuery(e)) {
                if (!trusted && !allowlisted) {
                    RequestEvents.blacklist(remote, reason = "ILLEGAL_QUERY_CHAR", countryCode = countryCode)
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                RequestEvents.record(
                    ip = remote,
                    host = host,
                    method = methodValue,
                    path = path,
                    rawQuery = rawQuery,
                    status = HttpStatusCode.BadRequest.value,
                    latencyMs = elapsedMs.toInt(),
                    ua = ua,
                    matchedRule = matchedRule,
                    requestId = requestId.toString(),
                    suspiciousReason = "ILLEGAL_QUERY_CHAR",
                    severity = 4
                )
                logger.warn("Blocked illegal URI: {}", originalUri, e)
                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                return
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            RequestEvents.record(
                ip = remote,
                host = host,
                method = methodValue,
                path = path,
                rawQuery = rawQuery,
                status = HttpStatusCode.BadGateway.value,
                latencyMs = elapsedMs.toInt(),
                ua = ua,
                matchedRule = matchedRule,
                requestId = requestId.toString(),
                suspiciousReason = suspiciousReason,
                severity = suspiciousSeverity
            )
            logger.error("Proxy URL build failed for uri='{}'", originalUri, e)
            call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            return
        }

        logger.info(
            "--> [ {} ] host='{}' matched='{}' method={} uri='{}' -> {}",
            requestId,
            host,
            matchedRule ?: "default",
            methodValue,
            originalUri,
            proxiedUrl
        )
        call.request.headers.forEach { key, values ->
            logger.debug("Incoming header: {} -> {}", key, values)
        }

        val proxiedResponse: HttpResponse = try {
            httpClient.request(proxiedUrl) { // Forward request to the target (Next.js)
                method = call.request.httpMethod
                // Copy all client headers except hop-by-hop
                headers {
                    call.request.headers.forEach { key, values ->
                    val lowerKey = key.lowercase()
                    if (lowerKey !in hopByHopHeaders &&
                        lowerKey != HttpHeaders.ContentLength.lowercase() &&
                        lowerKey != authProxyHeader
                    ) {
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
                    if (matchedRule == "grafana" && isTrustedIp(remote)) {
                        append("X-WEBAUTH-USER", "x")
                    }
                }

                val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                val hasBody = (contentLength != null && contentLength > 0) ||
                    call.request.headers[HttpHeaders.TransferEncoding] != null
                if (hasBody) {
                    setBody(call.receiveChannel())
                }
            }
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            if (isIllegalQuery(e)) {
                if (!trusted && !allowlisted) {
                    RequestEvents.blacklist(remote, reason = "ILLEGAL_QUERY_CHAR", countryCode = countryCode)
                }
                RequestEvents.record(
                    ip = remote,
                    host = host,
                    method = methodValue,
                    path = path,
                    rawQuery = rawQuery,
                    status = HttpStatusCode.BadRequest.value,
                    latencyMs = elapsedMs.toInt(),
                    ua = ua,
                    matchedRule = matchedRule,
                    requestId = requestId.toString(),
                    suspiciousReason = "ILLEGAL_QUERY_CHAR",
                    severity = 4
                )
                logger.warn("Blocked illegal URI: {}", originalUri, e)
                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                return
            }
            RequestEvents.record(
                ip = remote,
                host = host,
                method = methodValue,
                path = path,
                rawQuery = rawQuery,
                status = HttpStatusCode.BadGateway.value,
                latencyMs = elapsedMs.toInt(),
                ua = ua,
                matchedRule = matchedRule,
                requestId = requestId.toString(),
                suspiciousReason = suspiciousReason,
                severity = suspiciousSeverity
            )
            logger.error("Proxy request failed for uri='{}'", originalUri, e)
            call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            return
        }

        val status = proxiedResponse.status
        // Log status and response headers from target
        logger.info("Received response from target: {}", status)
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
        call.respondBytesWriter(status = status) {
            proxiedResponse.bodyAsChannel().copyAndClose(this)
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        val len = proxiedResponse.headers[HttpHeaders.ContentLength] ?: "chunked"
        val uaLog = ua ?: "-"
        if (status.value >= 400) {
            logger.warn(
                "<-- [ {} ] host='{}' matched='{}' method={} uri='{}' status={} len={} elapsed={}ms remote={} ua='{}'",
                requestId,
                host,
                matchedRule ?: "default",
                methodValue,
                originalUri,
                status.value,
                len,
                elapsedMs,
                remote,
                uaLog
            )
        } else {
            logger.info(
                "<-- [ {} ] host='{}' matched='{}' method={} uri='{}' status={} len={} elapsed={}ms remote={} ua='{}'",
                requestId,
                host,
                matchedRule ?: "default",
                methodValue,
                originalUri,
                status.value,
                len,
                elapsedMs,
                remote,
                uaLog
            )
        }

        RequestEvents.record(
            ip = remote,
            host = host,
            method = methodValue,
            path = path,
            rawQuery = rawQuery,
            status = status.value,
            latencyMs = elapsedMs.toInt(),
            ua = ua,
            matchedRule = matchedRule,
            requestId = requestId.toString(),
            suspiciousReason = suspiciousReason,
            severity = suspiciousSeverity
        )
    }

    private fun resolveClientIp(call: ApplicationCall): String {
        val cf = call.request.headers["CF-Connecting-IP"]
        if (!cf.isNullOrBlank()) return cf
        val forwarded = call.request.headers["X-Forwarded-For"]
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
        if (!forwarded.isNullOrBlank()) return forwarded
        return call.request.origin.remoteHost
    }

    private fun detectSuspicious(rawQuery: String?, ua: String?): Pair<String, Int>? {
        val query = rawQuery?.lowercase().orEmpty()
        val uaLower = ua?.lowercase().orEmpty()
        if (query.contains("wget") || query.contains("curl") || query.contains("|") || query.contains(";") || query.contains("cmd=")) {
            return "SUSPICIOUS_QUERY" to 3
        }
        if (uaLower.isBlank()) {
            return "MISSING_UA" to 1
        }
        if (uaLower.contains("bot") || uaLower.contains("spider") || uaLower.contains("crawler") ||
            uaLower.contains("masscan") || uaLower.contains("nmap") || uaLower.contains("zgrab") ||
            uaLower.contains("curl") || uaLower.contains("wget") || uaLower.contains("python-requests") ||
            uaLower.contains("go-http-client")
        ) {
            return "BOT_UA" to 2
        }
        return null
    }

    private fun detectExploitPath(path: String): String? {
        val p = path.lowercase()
        if (p.contains("/wp-") || p.startsWith("/wp-")) return "EXPLOIT_PATH"
        if (p.contains("/.env")) return "EXPLOIT_PATH"
        if (p.contains("/cgi-bin")) return "EXPLOIT_PATH"
        if (p.contains("/phpmyadmin")) return "EXPLOIT_PATH"
        if (p.contains("/.git")) return "EXPLOIT_PATH"
        return null
    }

    private fun isTrustedIp(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "::1") return true
        if (ip.startsWith("192.168.1.")) return true
        val (ipv4, ipv6) = currentPublicIps()
        return if (ip.contains(":")) {
            ipv6 != null && sameIpv6Prefix64(ip, ipv6)
        } else {
            ipv4 != null && ip == ipv4
        }
    }

    private fun currentPublicIps(): Pair<String?, String?> {
        val now = System.currentTimeMillis()
        if (now - publicIpCacheAtMs < publicIpCacheTtlMs) {
            return publicIpv4Cache to publicIpv6Cache
        }
        val raw = runCatching { Files.readString(publicIpFile) }.getOrNull().orEmpty()
        var v4: String? = null
        var v6: String? = null
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                when {
                    line.startsWith("ipv4=") -> v4 = line.removePrefix("ipv4=")
                    line.startsWith("ipv6=") -> v6 = line.removePrefix("ipv6=")
                    line.contains(":") -> v6 = line
                    line.count { it == '.' } == 3 -> v4 = line
                }
            }
        if (v4 != null || v6 != null) {
            publicIpv4Cache = v4
            publicIpv6Cache = v6
            publicIpCacheAtMs = now
        }
        return v4 to v6
    }

    private fun sameIpv6Prefix64(left: String, right: String): Boolean {
        val leftAddr = runCatching { InetAddress.getByName(left) }.getOrNull()
        val rightAddr = runCatching { InetAddress.getByName(right) }.getOrNull()
        val leftBytes = leftAddr?.address ?: return false
        val rightBytes = rightAddr?.address ?: return false
        if (leftBytes.size != 16 || rightBytes.size != 16) return false
        for (i in 0 until 8) {
            if (leftBytes[i] != rightBytes[i]) return false
        }
        return true
    }

    private fun isIllegalQuery(e: Exception): Boolean {
        return e is URISyntaxException || e.message?.contains("Illegal character in query", ignoreCase = true) == true
    }

}
