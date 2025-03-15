package net.sdfgsdfg

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.io.File

fun Route.githubWebhookRoute() {
    post("/webhook/github") {
        val payload = call.receiveText()
        println("GitHub payload: $payload")

        call.respondText("🙇 Deployment triggered !", status = HttpStatusCode.Accepted)

        val logFile = File("/tmp/deploy.log").apply { if (!exists()) createNewFile() }

        logToFile(logFile, "Received GitHub webhook payload:\n$payload")

        listOf(
            "systemctl restart frontend.service",
            "systemctl restart backend.service",
        ).forEach { command ->
            runCatching {
                logToFile(logFile, "Running command: '$command'")
                command.shell(logFile)
            }.onSuccess {
                logToFile(logFile, "✅ SUCCESS: '$command' executed.")
            }.onFailure {
                logToFile(logFile, "❌ FAILURE: '$command' failed.\n⚠️ Error: ${it.localizedMessage}")
            }
        }
    }
}