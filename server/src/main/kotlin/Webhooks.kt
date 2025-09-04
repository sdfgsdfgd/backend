package net.sdfgsdfg

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private val webhookDeployMutex = Mutex()

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

        webhookDeployMutex.withLock {
            val commands = listOf(
                // Leave frontend as-is until we decide; may fail and be logged correctly
                "systemctl --user restart frontend.service",
                // Backend is managed by a system unit with ExecReload mapped to deploy
                "systemctl reload backend.service",
            )

            val log = File(resolveLogDir(), "webhook.log")
            for (command in commands) {
                log("Running command: '$command'", log)
                val exit = command.shell(logFile = log)
                if (exit == 0) {
                    log("✅ SUCCESS: '$command' executed (exit=$exit).", log)
                } else {
                    log("❌ FAILURE: '$command' failed (exit=$exit).", log)
                }
            }
        }
    }
}
