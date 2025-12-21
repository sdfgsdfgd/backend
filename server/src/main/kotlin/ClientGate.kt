package net.sdfgsdfg

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.net.InetAddress

private val clientInfoKey = AttributeKey<ClientInfo>("client-info")
private val gateLogger = LoggerFactory.getLogger("proxy.Gatekeeper")
private val ansiRed = "\u001B[31m"
private val ansiCyan = "\u001B[36m"
private val ansiDim = "\u001B[2m"
private val ansiReset = "\u001B[0m"
private val cfSecret = System.getenv("CF_ORIGIN_VERIFY_SECRET")?.trim().takeIf { !it.isNullOrEmpty() }
private val cfSecretHeader = System.getenv("CF_ORIGIN_VERIFY_HEADER")?.trim()
    .takeIf { !it.isNullOrEmpty() } ?: "X-Origin-Verify"
private const val cfConnectingIpHeader = "CF-Connecting-IP"

private val cloudflareCidrs = listOf(
    "173.245.48.0/20",
    "103.21.244.0/22",
    "103.22.200.0/22",
    "103.31.4.0/22",
    "141.101.64.0/18",
    "108.162.192.0/18",
    "190.93.240.0/20",
    "188.114.96.0/20",
    "197.234.240.0/22",
    "198.41.128.0/17",
    "162.158.0.0/15",
    "104.16.0.0/13",
    "104.24.0.0/14",
    "172.64.0.0/13",
    "131.0.72.0/22",
    "2400:cb00::/32",
    "2606:4700::/32",
    "2803:f800::/32",
    "2405:b500::/32",
    "2405:8100::/32",
    "2a06:98c0::/29",
    "2c0f:f248::/32"
).mapNotNull { Cidr.parse(it) }

private data class Cidr(val network: ByteArray, val prefix: Int) {
    fun contains(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != network.size) return false
        val fullBytes = prefix / 8
        val remBits = prefix % 8
        for (i in 0 until fullBytes) {
            if (bytes[i] != network[i]) return false
        }
        if (remBits == 0) return true
        val mask = (0xFF shl (8 - remBits)) and 0xFF
        return (bytes[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    companion object {
        fun parse(cidr: String): Cidr? {
            val parts = cidr.split('/')
            if (parts.size != 2) return null
            val addr = parseIp(parts[0]) ?: return null
            val prefix = parts[1].toIntOrNull() ?: return null
            return Cidr(addr.address, prefix)
        }
    }
}

data class ClientInfo(
    val clientIp: String,
    val remoteIp: String,
    val cfIp: String?,
    val source: String,
    val trustedProxy: Boolean,
    val isLocal: Boolean,
    val allowed: Boolean
)

fun ApplicationCall.clientInfo(): ClientInfo {
    attributes.getOrNull(clientInfoKey)?.let { return it }
    val info = resolveClientInfo(this)
    attributes.put(clientInfoKey, info)
    return info
}

fun Application.installEdgeGatekeeper() {
    intercept(ApplicationCallPipeline.Setup) {
        val info = call.clientInfo()
        if (info.allowed) return@intercept
        val ua = call.request.headers[HttpHeaders.UserAgent] ?: "-"
        val host = call.request.host()
        val path = call.request.path()
        val rawQuery = call.request.queryString().takeIf { it.isNotBlank() }
        val (clientPlain, clientColor) = clientTag(info)
        val plain = buildString {
            append("[BLOCK][BYPASS_NO_CF] ")
            append(clientPlain).append(' ')
            append("host".kv(host)).append(' ')
            append("method".kv(call.request.httpMethod.value, quote = false)).append(' ')
            append("path".kv(path)).append(' ')
            append("ua".kv(ua))
        }
        val color = buildString {
            append("${ansiRed}[BLOCK][BYPASS_NO_CF]${ansiReset} ")
            append(clientColor).append(' ')
            append("host".kvDim(host)).append(' ')
            append("method".kvDim(call.request.httpMethod.value, quote = false)).append(' ')
            append("path".kvDim(path)).append(' ')
            append("ua".kvDim(ua))
        }
        logGate(plain, color, warn = true)
        RequestEvents.record(
            ip = info.clientIp,
            host = host,
            method = call.request.httpMethod.value,
            path = path,
            rawQuery = rawQuery,
            status = null,
            latencyMs = null,
            ua = ua,
            matchedRule = null,
            requestId = null,
            suspiciousReason = "BYPASS_NO_CF",
            severity = 4
        )
        call.attributes.put(RequestEventRecordedKey, true)
        call.silentDrop()
        finish()
    }
}

fun ApplicationCall.silentDrop() {
    (this as? NettyApplicationCall)?.context?.close()
}

private fun resolveClientInfo(call: ApplicationCall): ClientInfo {
    val host = call.request.host()
    val remote = call.request.origin.remoteHost
    val remoteIp = remote.takeIf { it.isNotBlank() } ?: "unknown"
    val remoteAddr = parseIp(remoteIp)
    val isLocal = remoteAddr?.let { isLocalAddr(it) } == true
    if (isLocal && !requiresCfHeader(host)) {
        return ClientInfo(
            clientIp = remoteIp,
            remoteIp = remoteIp,
            cfIp = null,
            source = "local",
            trustedProxy = false,
            isLocal = true,
            allowed = true
        )
    }
    val cfHeader = call.request.headers[cfConnectingIpHeader]?.trim().orEmpty()
    val cfIp = cfHeader.takeIf { it.isNotBlank() && parseIp(it) != null }
    val secretOk = cfSecret != null && call.request.headers[cfSecretHeader]?.trim() == cfSecret
    val trustedProxy = secretOk || remoteAddr?.let { isCloudflareAddr(it) } == true
    val allowed = if (cfSecret != null) secretOk && cfIp != null else trustedProxy && cfIp != null
    val clientIp = if (allowed && cfIp != null) cfIp else remoteIp
    val source = when {
        allowed && secretOk -> "cf-secret"
        allowed -> "cf"
        isLocal -> "local"
        else -> "remote"
    }
    return ClientInfo(
        clientIp = clientIp,
        remoteIp = remoteIp,
        cfIp = cfIp,
        source = source,
        trustedProxy = trustedProxy,
        isLocal = false,
        allowed = allowed
    )
}

private fun isCloudflareAddr(addr: InetAddress): Boolean = cloudflareCidrs.any { it.contains(addr) }

private fun isLocalAddr(addr: InetAddress): Boolean =
    addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress

private fun parseIp(ip: String): InetAddress? = runCatching { InetAddress.getByName(ip) }.getOrNull()

private fun requiresCfHeader(host: String): Boolean {
    val h = host.lowercase()
    if (h == "localhost" || h == "127.0.0.1" || h == "::1") return false
    val hostIp = parseIp(h) ?: return true
    return !isLocalAddr(hostIp)
}

private fun clientTag(info: ClientInfo): Pair<String, String> {
    val plain = buildString {
        append("[CLIENT] ip=").append(info.clientIp)
        append(" remote=").append(info.remoteIp)
        info.cfIp?.let { append(" cf=").append(it) }
        append(" src=").append(info.source)
    }
    val color = buildString {
        append("${ansiCyan}[CLIENT]${ansiReset} ip=${ansiDim}").append(info.clientIp).append(ansiReset)
        append(" remote=${ansiDim}").append(info.remoteIp).append(ansiReset)
        info.cfIp?.let { append(" cf=${ansiDim}").append(it).append(ansiReset) }
        append(" src=${ansiDim}").append(info.source).append(ansiReset)
    }
    return plain to color
}

private fun String.kv(value: String, quote: Boolean = true): String =
    if (quote) "$this='$value'" else "$this=$value"

private fun String.kvDim(value: String, quote: Boolean = true): String =
    if (quote) "$this=${ansiDim}'$value'${ansiReset}" else "$this=${ansiDim}$value${ansiReset}"

private fun logGate(plain: String, color: String, warn: Boolean) {
    MDC.put("log_prefix", plain)
    MDC.put("log_prefix_color", color)
    try {
        if (warn) gateLogger.warn("") else gateLogger.info("")
    } finally {
        MDC.remove("log_prefix")
        MDC.remove("log_prefix_color")
    }
}
