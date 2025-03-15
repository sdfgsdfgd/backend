package net.sdfgsdfg

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.serialization.gson.gson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
    // xx This installs Ktor’s ContentNegotiation plugin,
    //  allowing you to automatically convert Kotlin objects to JSON and vice versa using Gson.
    //  🤯 🤯 🤯


    install(ContentNegotiation) {
        gson {
//                setPrettyPrinting()
            disableHtmlEscaping()
            serializeNulls()
        }

        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(CORS) {
        anyHost()
        allowCredentials = true
        allowNonSimpleContentTypes = true
        allowHeaders { true }
        allowSameOrigin = true
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
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
