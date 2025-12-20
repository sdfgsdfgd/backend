package net.sdfgsdfg

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json

val httpClient = HttpClient(Apache) {
    followRedirects = false
    engine {
        followRedirects = false
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 7500
        connectTimeoutMillis = 5000
    }
}

private val RequestEventPlugin = createApplicationPlugin("RequestEventPlugin") {
    onCall { call ->
        call.attributes.put(RequestEventStartKey, System.nanoTime())
    }
    onCallRespond { call, _ ->
        if (call.attributes.contains(RequestEventRecordedKey)) return@onCallRespond
        if (!call.attributes.contains(RequestEventStartKey)) return@onCallRespond
        val start = call.attributes[RequestEventStartKey]
        val elapsedMs = ((System.nanoTime() - start) / 1_000_000).toInt()
        val rawQuery = call.request.queryString().takeIf { it.isNotBlank() }
        val ua = call.request.headers[HttpHeaders.UserAgent]
        val suspicion = detectSuspicious(rawQuery, ua)
        RequestEvents.record(
            ip = resolveClientIp(call),
            host = call.request.host(),
            method = call.request.httpMethod.value,
            path = call.request.path(),
            rawQuery = rawQuery,
            status = call.response.status()?.value ?: 200,
            latencyMs = elapsedMs,
            ua = ua,
            matchedRule = null,
            requestId = null,
            suspiciousReason = suspicion?.first,
            severity = suspicion?.second
        )
        call.attributes.put(RequestEventRecordedKey, true)
    }
}

// *** *** TODO:  Add  2 sequence within 15 secs    `port-knocking`    authentication
fun Application.cfg() {
    // [ Configure ]
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    // todo: Remove, unnecessary ( test w/ auth after removal, we temporarily added this to resolve OAUTH2.0 callback issues,
    //  but we would only need this if OAUTH logic lived completely on Ktor rather than via proxy )
    install(CORS) {
        anyHost()
        allowCredentials = true
        allowNonSimpleContentTypes = true
        allowHeaders { true }
        allowSameOrigin = true
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
    }

    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        )
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }

    install(RequestEventPlugin)

    routing {
        get("/example") {
            call.respond(
                mapOf(
                    "status" to "success",
                    "data" to listOf(
                        mapOf("id" to 1, "name" to "John Doe"),
                        mapOf("id" to 2, "name" to "Jane Smith")
                    )
                )
            )
        }
    }
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
