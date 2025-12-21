package proxy

import io.ktor.client.HttpClient
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.uri
import org.slf4j.LoggerFactory
import org.slf4j.MDC

data class HostRule(
    val hosts: Set<String>,
    val target: Url,
    val name: String = target.host
)

/**
 * Minimal host-to-target switcher to keep the proxy logic tidy.
 * Add new domains by appending HostRule entries; unknown hosts fall back to defaultTarget.
 */
class HostRouter(
    httpClient: HttpClient,
    rules: List<HostRule>,
    private val defaultTarget: Url
) {
    private val logger = LoggerFactory.getLogger("proxy.HostRouter")
    private val ansiCyan = "\u001B[36m"
    private val ansiDim = "\u001B[2m"
    private val ansiReset = "\u001B[0m"

    private val normalizedRules: List<HostRule> = rules.map { rule ->
        rule.copy(hosts = rule.hosts.map { it.lowercase() }.toSet())
    }

    private val proxies: Map<Url, SimpleReverseProxy> = buildMap {
        (normalizedRules.map { it.target }.toSet() + defaultTarget).forEach { target ->
            put(target, SimpleReverseProxy(httpClient, target))
        }
    }

    suspend fun proxy(call: ApplicationCall) {
        val hostHeader = call.request.host().lowercase()
        val matchedRule = normalizedRules.firstOrNull { hostHeader in it.hosts }
        val target = matchedRule?.target ?: defaultTarget
        val (clientPlain, clientColor) = clientTag(resolveClientIp(call))
        val plain = buildString {
            append("[ROUTE] ").append(clientPlain).append(' ')
            append("Routing ")
            append("host".kv(hostHeader)).append(' ')
            append("path".kv(call.request.uri)).append(' ')
            append("matched".kv(matchedRule?.name ?: "default")).append(' ')
            append("target".kv(target.toString()))
        }
        val color = buildString {
            append("${ansiCyan}[ROUTE]${ansiReset} ").append(clientColor).append(' ')
            append("Routing ")
            append("host".kvDim(hostHeader)).append(' ')
            append("path".kvDim(call.request.uri)).append(' ')
            append("matched".kvDim(matchedRule?.name ?: "default")).append(' ')
            append("target".kvDim(target.toString()))
        }
        log(plain, color)
        if (matchedRule == null) {
            logger.debug("No explicit host match for '{}', using default {}", hostHeader, defaultTarget)
        }
        proxies.getValue(target).proxy(call, hostHeader, matchedRule?.name ?: "default")
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

    private fun clientTag(ip: String): Pair<String, String> {
        val plain = "[CLIENT] ip=$ip"
        val color = "${ansiCyan}[CLIENT]${ansiReset} ip=${ansiDim}$ip${ansiReset}"
        return plain to color
    }

    private fun String.kv(value: String): String = "$this='$value'"

    private fun String.kvDim(value: String): String = "$this=${ansiDim}'$value'${ansiReset}"

    private fun log(plain: String, color: String) {
        MDC.put("log_prefix", plain)
        MDC.put("log_prefix_color", color)
        try {
            logger.info("")
        } finally {
            MDC.remove("log_prefix")
            MDC.remove("log_prefix_color")
        }
    }
}
