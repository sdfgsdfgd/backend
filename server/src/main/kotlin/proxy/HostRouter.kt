package proxy

import io.ktor.client.HttpClient
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.uri
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
        logger.info(
            "Routing host='{}' path='{}' matched='{}' -> target={}",
            hostHeader,
            call.request.uri,
            matchedRule?.name ?: "default",
            target
        )
        if (matchedRule == null) {
            logger.debug("No explicit host match for '{}', using default {}", hostHeader, defaultTarget)
        }
        proxies.getValue(target).proxy(call, hostHeader, matchedRule?.name ?: "default")
    }
}
