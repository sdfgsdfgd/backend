package net.sdfgsdfg

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.io.File

fun Route.githubWebhookRoute() {
    post("/webhook/github") {
        runCatching {
            val payload = call.receiveText()
            println("GitHub payload: $payload")

            call.respondText("🙇 Deployment triggered !", status = HttpStatusCode.Accepted)
            val logFile = File("/tmp/deploy.log").apply { if (!exists()) createNewFile() }
            log("Received GitHub webhook payload:\n$payload", File(resolveLogDir(), "webhook.log"))
        }.onFailure {
            log("❌ Error processing GitHub webhook: ${it.localizedMessage}", File(resolveLogDir(), "webhook.log"))
            call.respondText("❌ Error processing GitHub webhook: ${it.localizedMessage}", status = HttpStatusCode.InternalServerError)
        }

        

        listOf(
            "systemctl --user restart frontend.service",
            "systemctl --user restart backend.service",
        ).forEach { command ->
            runCatching {
                log("Running command: '$command'", File(resolveLogDir(), "webhook.log"))
//                command.shell(logFile = logFile)
                command.shell(logFile = File(resolveLogDir(), "webhook.log"))
            }.onSuccess {
                log("✅ SUCCESS: '$command' executed.", File(resolveLogDir(), "webhook.log"))
            }.onFailure {
                log("❌ FAILURE: '$command' failed.\n⚠️ Error: ${it.localizedMessage}", File(resolveLogDir(), "webhook.log"))
            }
        }
    }
}