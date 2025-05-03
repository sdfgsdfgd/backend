package net.sdfgsdfg

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json

val httpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 7500
        connectTimeoutMillis = 5000
    }
}

// *** *** TODO:  Add  2 sequence within 15 secs    `port-knocking`    authentication
fun Application.cfg() {
    // [ Configure ]

    // 1. Configure: Serialization
    // xx if ContentNegotiation plugin allows you to automatically convert Kotlin objects to JSON and vice versa using Gson, what the fk is Gson good for ?
    //  🤯 🤯 🤯


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
                encodeDefaults   = true
            }
        )
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }

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
