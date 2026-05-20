package proxy

import io.ktor.client.HttpClient
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.uri
import net.sdfgsdfg.clientInfo
import org.slf4j.LoggerFactory

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
        val (clientPlain, clientColor) = ProxyLog.clientTag(call.clientInfo())
        val plain = buildString {
            append("[ROUTE] ").append(clientPlain).append(' ')
            append("Routing ")
            append("host".kv(hostHeader)).append(' ')
            append("path".kv(call.request.uri)).append(' ')
            append("matched".kv(matchedRule?.name ?: "default")).append(' ')
            append("target".kv(target.toString()))
        }
        val color = buildString {
            append("${ProxyLog.cyan}[ROUTE]${ProxyLog.reset} ").append(clientColor).append(' ')
            append("Routing ")
            append("host".kvDim(hostHeader)).append(' ')
            append("path".kvDim(call.request.uri)).append(' ')
            append("matched".kvDim(matchedRule?.name ?: "default")).append(' ')
            append("target".kvDim(target.toString()))
        }
        ProxyLog.write(logger, plain, color)
        if (matchedRule == null) {
            logger.debug("No explicit host match for '{}', using default {}", hostHeader, defaultTarget)
        }
        proxies.getValue(target).proxy(call, hostHeader, matchedRule?.name ?: "default")
    }
}
