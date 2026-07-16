package net.sdfgsdfg

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.host
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.sdfgsdfg.data.model.OPS_AUTH_GITHUB_DEVICE_POLL_PATH
import net.sdfgsdfg.data.model.OPS_AUTH_GITHUB_DEVICE_START_PATH
import net.sdfgsdfg.data.model.OPS_AUTH_GITHUB_CALLBACK_PATH
import net.sdfgsdfg.data.model.OPS_AUTH_GITHUB_START_PATH
import net.sdfgsdfg.data.model.OPS_AUTH_LOGOUT_PATH
import net.sdfgsdfg.data.model.OpsGithubDevicePollDto
import net.sdfgsdfg.data.model.OpsGithubDeviceStartDto
import net.sdfgsdfg.data.model.OpsGithubTokenDto
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val opsGithubSessionCookie = "ops_session"

private const val stateCookie = "ops_github_state"
private const val stateCookiePath = "/api"
private const val returnToParam = "returnTo"
private const val popupParam = "popup"
private const val apiParam = "api"
private const val stateTtlMs = 10 * 60 * 1_000L
private const val sessionTtlMs = 7 * 24 * 60 * 60 * 1_000L
private const val sessionMaxAgeSeconds = (sessionTtlMs / 1_000L).toInt()
private const val stateMaxAgeSeconds = (stateTtlMs / 1_000L).toInt()
private const val bearerCacheMs = 60_000L
private val githubOauthJson = Json { ignoreUnknownKeys = true }
private val secureRandom = SecureRandom()
private val githubBearerCache = mutableMapOf<String, CachedGithubBearerSession>()

internal data class OpsGithubSession(val login: String, val displayName: String, val avatarUrl: String? = null)
private data class CachedGithubBearerSession(val expiresAtMs: Long, val session: OpsGithubSession?)
private data class VerifiedGithubState(val returnTo: String?, val popup: Boolean)

internal fun Route.opsGithubAuthRoutes(http: HttpClient, allowed: (ApplicationCall) -> Boolean) {
    val callbackPath = opsGithubCallbackPath()

    get(OPS_AUTH_GITHUB_START_PATH) {
        if (!allowed(call)) {
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
            return@get
        }
        val config = call.opsGithubAuthConfig() ?: run {
            call.respondText("GitHub OAuth is not configured", status = HttpStatusCode.ServiceUnavailable)
            return@get
        }
        val secret = opsGithubSessionSecret(config.clientSecret) ?: run {
            call.respondText("GitHub OAuth session secret is not configured", status = HttpStatusCode.ServiceUnavailable)
            return@get
        }
        val state = randomToken()
        val returnTo = call.opsGithubReturnTo()
        val popup = call.request.queryParameters[popupParam] == "1"
        call.setOpsCookie(
            stateCookie,
            signOpsGithubState(state, secret, returnTo, popup),
            stateMaxAgeSeconds,
            path = stateCookiePath,
        )
        call.respondRedirect(githubAuthorizeUrl(config.clientId, config.redirectUri(), state))
    }

    post(OPS_AUTH_GITHUB_DEVICE_START_PATH) {
        if (!allowed(call)) {
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
            return@post
        }
        val config = opsGithubDeviceConfig() ?: run {
            call.respondText("GitHub device auth is not configured", status = HttpStatusCode.ServiceUnavailable)
            return@post
        }
        val start = startGithubDevice(http, config.clientId).getOrElse {
            call.respondText(it.message ?: "GitHub device auth failed", status = HttpStatusCode.BadGateway)
            return@post
        }
        call.respond(start)
    }

    post(OPS_AUTH_GITHUB_DEVICE_POLL_PATH) {
        if (!allowed(call)) {
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
            return@post
        }
        val config = opsGithubDeviceConfig() ?: run {
            call.respondText("GitHub device auth is not configured", status = HttpStatusCode.ServiceUnavailable)
            return@post
        }
        val poll = runCatching { githubOauthJson.decodeFromString<OpsGithubDevicePollDto>(call.receiveText()) }.getOrElse {
            call.respondText("Invalid GitHub device poll JSON", status = HttpStatusCode.BadRequest)
            return@post
        }
        val token = pollGithubDevice(http, config.clientId, poll.deviceCode).getOrElse {
            call.respondText(it.message ?: "GitHub device auth failed", status = HttpStatusCode.BadGateway)
            return@post
        } ?: run {
            call.respondText("authorization_pending", status = HttpStatusCode.Accepted)
            return@post
        }
        fetchGithubSession(http, token).getOrElse {
            call.respondText("GitHub user lookup failed", status = HttpStatusCode.BadGateway)
            return@post
        }
        call.respond(OpsGithubTokenDto(accessToken = token))
    }

    suspend fun ApplicationCall.callback() {
        if (!allowed(this)) {
            respondText("Not Found", status = HttpStatusCode.NotFound)
            return
        }
        val config = opsGithubAuthConfig() ?: return redirectOpsAuthError("not_configured")
        val secret = opsGithubSessionSecret(config.clientSecret) ?: return redirectOpsAuthError("not_configured")
        val code = request.queryParameters["code"]?.takeIf { it.isNotBlank() }
            ?: return redirectOpsAuthError("missing_code")
        val state = request.queryParameters["state"]?.takeIf { it.isNotBlank() }
            ?: return redirectOpsAuthError("missing_state")
        val verifiedState = verifyOpsGithubState(request.cookies[stateCookie], state, secret) ?: run {
            return redirectOpsAuthError("bad_state")
        }
        val returnTo = verifiedState.returnTo
        val token = exchangeGithubCode(http, config, code).getOrElse {
            return redirectOpsAuthError("token_exchange_failed", returnTo, verifiedState.popup)
        }
        val session = fetchGithubSession(http, token).getOrElse {
            return redirectOpsAuthError("user_fetch_failed", returnTo, verifiedState.popup)
        }
        clearOpsCookie(stateCookie, path = stateCookiePath)
        setOpsCookie(opsGithubSessionCookie, signOpsGithubSession(session, secret), sessionMaxAgeSeconds)
        if (verifiedState.popup) {
            respondOpsGithubPopup(ok = true, returnTo = returnTo, login = session.login)
        } else {
            respondRedirect(returnTo ?: opsGithubDefaultReturnTo() ?: "/")
        }
    }
    get(OPS_AUTH_GITHUB_CALLBACK_PATH) { call.callback() }
    if (callbackPath != OPS_AUTH_GITHUB_CALLBACK_PATH) get(callbackPath) { call.callback() }

    suspend fun ApplicationCall.logout() {
        if (!allowed(this)) {
            respondText("Not Found", status = HttpStatusCode.NotFound)
            return
        }
        val returnTo = opsGithubReturnTo()
        clearOpsCookie(opsGithubSessionCookie)
        if (request.queryParameters[apiParam] == "1") {
            respond(HttpStatusCode.NoContent)
        } else {
            respondRedirect(returnTo ?: opsGithubDefaultReturnTo() ?: "/")
        }
    }
    get(OPS_AUTH_LOGOUT_PATH) { call.logout() }
    post(OPS_AUTH_LOGOUT_PATH) { call.logout() }
}

internal fun ApplicationCall.opsGithubSession(
    secret: String? = opsGithubSessionSecret(),
    nowMs: Long = System.currentTimeMillis(),
): OpsGithubSession? = verifyOpsGithubSession(request.cookies[opsGithubSessionCookie], secret, nowMs)

internal suspend fun ApplicationCall.opsGithubBearerSession(http: HttpClient, nowMs: Long = System.currentTimeMillis()): OpsGithubSession? {
    val token = opsGithubBearerToken() ?: return null
    synchronized(githubBearerCache) {
        githubBearerCache[token]?.takeIf { it.expiresAtMs > nowMs }?.let { return it.session }
    }
    val session = fetchGithubSession(http, token).getOrNull()
    synchronized(githubBearerCache) {
        githubBearerCache[token] = CachedGithubBearerSession(nowMs + bearerCacheMs, session)
    }
    return session
}

internal fun ApplicationCall.opsGithubBearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization]?.trim() ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substringAfter(' ').trim().takeIf { it.isNotBlank() }
}

internal fun signOpsGithubSession(
    session: OpsGithubSession,
    secret: String,
    nowMs: Long = System.currentTimeMillis(),
    ttlMs: Long = sessionTtlMs,
): String = signOpsToken(
    "github-session",
    secret,
    session.login,
    session.displayName,
    session.avatarUrl.orEmpty(),
    (nowMs + ttlMs).toString(),
)

internal fun verifyOpsGithubSession(
    value: String?,
    secret: String?,
    nowMs: Long = System.currentTimeMillis(),
): OpsGithubSession? {
    val parts = verifyOpsToken(value, "github-session", secret) ?: return null
    if (parts.size != 3 && parts.size != 4) return null
    val expiresAt = parts.last().toLongOrNull() ?: return null
    if (expiresAt <= nowMs) return null
    val login = parts[0].trim().lowercase().takeIf { it.isNotBlank() } ?: return null
    val avatarUrl = if (parts.size == 4) parts[2].takeIf { it.isNotBlank() } else null
    return OpsGithubSession(login, parts[1].ifBlank { login }, avatarUrl)
}

private data class OpsGithubAuthConfig(
    val clientId: String,
    val clientSecret: String,
    val publicBaseUrl: String,
    val callbackPath: String,
) {
    fun redirectUri() = "${publicBaseUrl.trimEnd('/')}$callbackPath"
}

private data class OpsGithubDeviceConfig(val clientId: String)

private fun opsGithubDeviceConfig(): OpsGithubDeviceConfig? =
    githubDeviceClientId()?.let(::OpsGithubDeviceConfig)

private fun ApplicationCall.opsGithubAuthConfig(): OpsGithubAuthConfig? {
    val clientId = githubOauthClientId()
    val clientSecret = githubOauthClientSecret()
    val publicBaseUrl = env("OPS_AUTH_PUBLIC_BASE_URL") ?: env("AUTH_PROXY_URL") ?: defaultOpsAuthBaseUrl()
    val callbackPath = opsGithubCallbackPath()
    if (clientId == null || clientSecret == null) return null
    return OpsGithubAuthConfig(clientId, clientSecret, publicBaseUrl, callbackPath)
}

private fun githubOauthClientId() =
    env("OPS_GITHUB_OAUTH_CLIENT_ID") ?: env("OPS_GITHUB_CLIENT_ID") ?: env("GITHUB_CLIENT_ID") ?: env("NEXT_PUBLIC_GITHUB_CLIENT_ID")

private fun githubOauthClientSecret() =
    env("OPS_GITHUB_OAUTH_CLIENT_SECRET") ?: env("OPS_GITHUB_CLIENT_SECRET") ?: env("GITHUB_CLIENT_SECRET")

private fun githubDeviceClientId() =
    env("OPS_GITHUB_DEVICE_CLIENT_ID") ?: env("OPS_GITHUB_CLIENT_ID") ?: env("GITHUB_CLIENT_ID") ?: env("NEXT_PUBLIC_GITHUB_CLIENT_ID")

private fun opsGithubSessionSecret(fallback: String? = null) =
    env("OPS_AUTH_SESSION_SECRET") ?: fallback ?: githubOauthClientSecret()

private fun opsGithubCallbackPath() = env("OPS_AUTH_CALLBACK_PATH") ?: OPS_AUTH_GITHUB_CALLBACK_PATH

private fun ApplicationCall.defaultOpsAuthBaseUrl(): String {
    val host = request.host().substringBefore(':')
    return if (host == "ops.sdfgsdfg.net") "https://ops.sdfgsdfg.net" else "http://${request.host()}"
}

private fun githubAuthorizeUrl(clientId: String, redirectUri: String, state: String) =
    "https://github.com/login/oauth/authorize" +
        "?client_id=${url(clientId)}" +
        "&redirect_uri=${url(redirectUri)}" +
        "&scope=${url("read:user repo")}" +
        "&state=${url(state)}"

private suspend fun exchangeGithubCode(http: HttpClient, config: OpsGithubAuthConfig, code: String) = http.ioResult {
    val body = formBody(
        "client_id" to config.clientId,
        "client_secret" to config.clientSecret,
        "code" to code,
        "redirect_uri" to config.redirectUri(),
    )
    val request = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
        .timeout(Duration.ofSeconds(8))
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) error("GitHub token exchange failed with ${response.statusCode()}")
    githubOauthJson.parseToJsonElement(response.body()).jsonObject.text("access_token")
        ?: error("GitHub token exchange returned no access token")
}

private suspend fun startGithubDevice(http: HttpClient, clientId: String) = http.ioResult {
    val body = formBody("client_id" to clientId, "scope" to "read:user repo")
    val request = HttpRequest.newBuilder(URI.create("https://github.com/login/device/code"))
        .timeout(Duration.ofSeconds(8))
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) error("GitHub device start failed with ${response.statusCode()}")
    val json = githubOauthJson.parseToJsonElement(response.body()).jsonObject
    OpsGithubDeviceStartDto(
        deviceCode = json.text("device_code") ?: error("GitHub device response has no device_code"),
        userCode = json.text("user_code") ?: error("GitHub device response has no user_code"),
        verificationUri = json.text("verification_uri") ?: error("GitHub device response has no verification_uri"),
        verificationUriComplete = json.text("verification_uri_complete"),
        expiresIn = json.text("expires_in")?.toIntOrNull() ?: 900,
        interval = json.text("interval")?.toIntOrNull() ?: 5,
    )
}

private suspend fun pollGithubDevice(http: HttpClient, clientId: String, deviceCode: String) = http.ioResult {
    val body = formBody(
        "client_id" to clientId,
        "device_code" to deviceCode,
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
    )
    val request = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
        .timeout(Duration.ofSeconds(8))
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) error("GitHub device poll failed with ${response.statusCode()}")
    val json = githubOauthJson.parseToJsonElement(response.body()).jsonObject
    when (val error = json.text("error")) {
        null -> json.text("access_token") ?: error("GitHub device poll returned no access token")
        "authorization_pending", "slow_down" -> null
        else -> error(error)
    }
}

private suspend fun fetchGithubSession(http: HttpClient, token: String) = http.ioResult {
    val request = HttpRequest.newBuilder(URI.create("https://api.github.com/user"))
        .timeout(Duration.ofSeconds(8))
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $token")
        .header("User-Agent", "ops-dashboard")
        .GET()
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) error("GitHub user lookup failed with ${response.statusCode()}")
    val user = githubOauthJson.parseToJsonElement(response.body()).jsonObject
    val login = user.text("login")?.lowercase() ?: error("GitHub user response has no login")
    OpsGithubSession(
        login = login,
        displayName = user.text("name").takeUnless { it.isNullOrBlank() } ?: login,
        avatarUrl = user.text("avatar_url").takeUnless { it.isNullOrBlank() },
    )
}

private suspend fun <T> HttpClient.ioResult(block: HttpClient.() -> T): Result<T> =
    withContext(Dispatchers.IO) {
        runCatching { this@ioResult.block() }
    }

private fun signOpsGithubState(
    state: String,
    secret: String,
    returnTo: String?,
    popup: Boolean,
    nowMs: Long = System.currentTimeMillis(),
) = signOpsToken("github-state", secret, state, returnTo.orEmpty(), if (popup) "popup" else "", (nowMs + stateTtlMs).toString())

private fun ApplicationCall.verifyOpsGithubState(
    value: String?,
    state: String,
    secret: String,
    nowMs: Long = System.currentTimeMillis(),
): VerifiedGithubState? {
    val parts = verifyOpsToken(value, "github-state", secret) ?: return null
    if (parts.size !in 2..4 || parts[0] != state) return null
    if ((parts.last().toLongOrNull() ?: return null) <= nowMs) return null
    val returnTo = parts.getOrNull(1)
        ?.takeIf { parts.size >= 3 && it.isNotBlank() }
        ?.takeIf(::allowsOpsGithubReturnTo)
    return VerifiedGithubState(
        returnTo = returnTo,
        popup = parts.size == 4 && parts[2] == "popup",
    )
}

private fun signOpsToken(kind: String, secret: String, vararg parts: String): String {
    val payload = (listOf(kind) + parts).joinToString(".") { it.base64Url() }
    return "$payload.${payload.hmacSha256(secret)}"
}

private fun verifyOpsToken(value: String?, kind: String, secret: String?): List<String>? {
    if (value == null || secret.isNullOrBlank()) return null
    val payload = value.substringBeforeLast('.', missingDelimiterValue = "")
    val signature = value.substringAfterLast('.', missingDelimiterValue = "")
    if (payload.isBlank() || signature.isBlank()) return null
    if (!MessageDigest.isEqual(signature.toByteArray(), payload.hmacSha256(secret).toByteArray())) return null
    val parts = payload.split('.').map { it.fromBase64Url() ?: return null }
    if (parts.firstOrNull() != kind) return null
    return parts.drop(1)
}

private fun randomToken(bytes: Int = 24): String =
    ByteArray(bytes).also(secureRandom::nextBytes).base64Url()

private fun String.base64Url(): String =
    toByteArray(StandardCharsets.UTF_8).base64Url()

private fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.fromBase64Url(): String? = runCatching {
    Base64.getUrlDecoder().decode(this).toString(StandardCharsets.UTF_8)
}.getOrNull()

private fun String.hmacSha256(secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(toByteArray(StandardCharsets.UTF_8)).base64Url()
}

private fun ApplicationCall.setOpsCookie(name: String, value: String, maxAge: Int, path: String = "/") {
    response.cookies.append(
        Cookie(
            name = name,
            value = value,
            maxAge = maxAge,
            path = path,
            secure = secureOpsCookie(),
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        )
    )
}

private fun ApplicationCall.clearOpsCookie(name: String, path: String = "/") {
    response.cookies.append(
        Cookie(
            name = name,
            value = "",
            maxAge = 0,
            path = path,
            secure = secureOpsCookie(),
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        )
    )
}

private fun ApplicationCall.secureOpsCookie() =
    request.host().substringBefore(':') == "ops.sdfgsdfg.net"

private fun ApplicationCall.opsGithubReturnTo() =
    request.queryParameters[returnToParam]
        ?.trim()
        ?.takeIf { it.length in 1..2048 }
        ?.takeIf(::allowsOpsGithubReturnTo)

private fun ApplicationCall.opsGithubDefaultReturnTo() =
    env("OPS_AUTH_DEFAULT_RETURN_TO")
        ?.takeIf { it.length <= 2048 }
        ?.takeIf(::allowsOpsGithubReturnTo)

private fun ApplicationCall.allowsOpsGithubReturnTo(target: String): Boolean {
    if (target.startsWith("/") && !target.startsWith("//")) return true
    val uri = runCatching { URI.create(target) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val requestHost = request.host().substringBefore(':').lowercase()
    return scheme == "https" && host == "ops.sdfgsdfg.net" ||
        scheme == "http" && requestHost in localOpsHosts && host in localOpsHosts
}

private suspend fun ApplicationCall.redirectOpsAuthError(reason: String, returnTo: String? = null, popup: Boolean = false) {
    clearOpsCookie(stateCookie, path = stateCookiePath)
    if (popup) {
        respondOpsGithubPopup(ok = false, returnTo = returnTo, reason = reason)
    } else {
        respondRedirect((returnTo ?: opsGithubDefaultReturnTo() ?: "/").withQueryParam("auth_error", reason))
    }
}

private val localOpsHosts = setOf("localhost", "127.0.0.1")

private suspend fun ApplicationCall.respondOpsGithubPopup(
    ok: Boolean,
    returnTo: String?,
    login: String? = null,
    reason: String? = null,
) {
    val target = returnTo ?: opsGithubDefaultReturnTo() ?: "/"
    val pageTitle = if (ok) "GitHub connected" else "GitHub login failed"
    val detail = if (ok) {
        login?.let { "Signed in as $it" } ?: "Returning to Trio Ops Cockpit"
    } else {
        reason ?: "Returning to Trio Ops Cockpit"
    }
    val tone = if (ok) "#5cff95" else "#ffcf62"
    respondHtml {
        head {
            meta { charset = "utf-8" }
            meta {
                name = "viewport"
                content = "width=device-width, initial-scale=1"
            }
            title { +pageTitle }
            style { unsafe { +opsGithubPopupCss(tone) } }
        }
        body {
            main("card") {
                div("mark") { unsafe { +if (ok) "&#10003;" else "!" } }
                h1 { +pageTitle }
                p { +detail }
                div("status")
            }
            script {
                unsafe {
                    +opsGithubPopupScript(
                        event = if (ok) "ok" else "error",
                        target = target,
                        targetOrigin = target.jsOriginOrWildcard(),
                        closeDelayMs = if (ok) 700 else 1_500,
                        fallbackDelayMs = if (ok) 1_200 else 2_200,
                    )
                }
            }
        }
    }
}

private fun opsGithubPopupCss(tone: String) = """
    :root { color-scheme: dark; }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      display: grid;
      place-items: center;
      overflow: hidden;
      font-family: ui-rounded, "SF Pro Rounded", "Avenir Next", Inter, system-ui, sans-serif;
      color: rgba(240, 248, 255, .88);
      background:
        radial-gradient(circle at 14% 16%, rgba(77, 178, 255, .20), transparent 34%),
        radial-gradient(circle at 86% 10%, rgba(255, 64, 120, .18), transparent 36%),
        linear-gradient(135deg, #07111d 0%, #090912 48%, #220515 100%);
    }
    body::before {
      content: "";
      position: fixed;
      inset: -12%;
      background:
        linear-gradient(110deg, rgba(255,255,255,.04), transparent 34%),
        repeating-linear-gradient(165deg, rgba(132,184,255,.055) 0 1px, transparent 1px 42px);
      opacity: .7;
    }
    .card {
      position: relative;
      width: min(92vw, 520px);
      min-height: 330px;
      padding: 34px;
      border: 1px solid rgba(92, 255, 149, .46);
      border-radius: 30px;
      background:
        linear-gradient(145deg, rgba(21, 31, 41, .88), rgba(8, 13, 22, .82)),
        radial-gradient(circle at 0% 0%, rgba(92, 255, 149, .20), transparent 42%);
      box-shadow: inset 0 1px 0 rgba(255,255,255,.07), 0 24px 90px rgba(0,0,0,.46);
      backdrop-filter: blur(22px) saturate(145%);
    }
    .mark {
      width: 78px;
      height: 78px;
      border-radius: 28px;
      display: grid;
      place-items: center;
      color: #07111d;
      background: $tone;
      box-shadow: 0 0 0 10px rgba(92,255,149,.07), 0 0 42px ${tone}66;
      font-size: 34px;
      font-weight: 900;
      margin-bottom: 28px;
    }
    h1 {
      margin: 0 0 10px;
      font-size: clamp(32px, 7vw, 52px);
      line-height: .96;
      letter-spacing: 0;
    }
    p {
      margin: 0;
      max-width: 28rem;
      color: rgba(226, 235, 247, .58);
      font-size: 18px;
      line-height: 1.45;
      font-weight: 650;
    }
    .status {
      position: absolute;
      left: 34px;
      right: 34px;
      bottom: 28px;
      height: 3px;
      overflow: hidden;
      border-radius: 999px;
      background: rgba(255,255,255,.08);
    }
    .status::before {
      content: "";
      display: block;
      height: 100%;
      width: 56%;
      border-radius: inherit;
      background: linear-gradient(90deg, transparent, $tone, transparent);
      animation: sweep 1.1s ease-in-out infinite;
    }
    @keyframes sweep { from { transform: translateX(-120%); } to { transform: translateX(220%); } }
""".trimIndent()

private fun opsGithubPopupScript(
    event: String,
    target: String,
    targetOrigin: String,
    closeDelayMs: Int,
    fallbackDelayMs: Int,
) = """
    const payload = "ops.github.auth:${event.jsEscaped()}";
    if (window.opener && !window.opener.closed) window.opener.postMessage(payload, "${targetOrigin.jsEscaped()}");
    setTimeout(() => window.close(), $closeDelayMs);
    setTimeout(() => { location.replace("${target.jsEscaped()}"); }, $fallbackDelayMs);
""".trimIndent()

private fun String.withQueryParam(name: String, value: String): String {
    val base = substringBefore('#')
    val fragment = substringAfter('#', missingDelimiterValue = "")
    return base +
        (if ('?' in base) "&" else "?") +
        "${url(name)}=${url(value)}" +
        if (fragment.isBlank()) "" else "#$fragment"
}

private fun String.jsOriginOrWildcard(): String =
    runCatching {
        val uri = URI.create(this)
        val scheme = uri.scheme ?: return@runCatching "*"
        val host = uri.host ?: return@runCatching "*"
        val port = if (uri.port == -1) "" else ":${uri.port}"
        "$scheme://$host$port"
    }.getOrDefault("*")

private fun String.jsEscaped() =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("<", "\\u003c")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

private fun formBody(vararg pairs: Pair<String, String>) =
    pairs.joinToString("&") { (key, value) -> "${url(key)}=${url(value)}" }

private fun url(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun env(name: String) = System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
