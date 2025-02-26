package net.sdfgsdfg

import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureSerialization() {
    // xx This installs Ktor’s ContentNegotiation plugin,
    //  allowing you to automatically convert Kotlin objects to JSON and vice versa using Gson.
    //  🤯 🤯 🤯
    install(ContentNegotiation) {
        gson {
//            setPrettyPrinting()  // xx keep minified instead
        }
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


//    allows you to get       Eazy peazy JSON ser
//    :
//routing {
//    get("/json/complex") {
//        call.respond(
//            mapOf(
//                "status" to "success",
//                "meta" to mapOf(
//                    "timestamp" to System.currentTimeMillis(),
//                    "requestId" to "req-${(1000..9999).random()}"
//                ),
//                "data" to listOf(
//                    mapOf(
//                        "id" to 1,
//                        "name" to "John Doe",
//                        "contacts" to listOf(
//                            mapOf("type" to "email", "value" to "john.doe@example.com"),
//                            mapOf("type" to "phone", "value" to "+1234567890")
//                        ),
//                        "preferences" to mapOf(
//                            "notifications" to true,
//                            "theme" to "dark"
//                        )
//                    ),
//                    mapOf(
//                        "id" to 2,
//                        "name" to "Jane Smith",
//                        "contacts" to listOf(
//                            mapOf("type" to "email", "value" to "jane.smith@example.com"),
//                            mapOf("type" to "phone", "value" to "+9876543210")
//                        ),
//                        "preferences" to mapOf(
//                            "notifications" to false,
//                            "theme" to "light"
//                        ),
//                        "history" to listOf(
//                            mapOf("action" to "login", "timestamp" to System.currentTimeMillis() - 3600000),
//                            mapOf("action" to "purchase", "timestamp" to System.currentTimeMillis() - 7200000)
//                        )
//                    )
//                )
//            )
//        )
//    }
//}
