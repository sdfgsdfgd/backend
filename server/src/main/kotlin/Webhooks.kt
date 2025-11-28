package net.sdfgsdfg

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val webhookDeployMutex = Mutex()

fun Route.githubWebhookRoute() {
    post("/webhook/github") { call.processGitHubWebhook(targetOverride = null) }
    post("/webhook/github/{target}") { call.processGitHubWebhook(targetOverride = call.parameters["target"]) }
}

private val json = Json { ignoreUnknownKeys = true }

private data class DeploymentProfile(
    val commands: List<String>,
    val repoFullName: String? = null,
)

// Register GitHub repositories that should trigger deployments here.
private val deploymentProfiles: Map<String, DeploymentProfile> = mapOf(
    "default" to DeploymentProfile(
        repoFullName = "sdfgsdfgd/backend",
        commands = listOf(
            // Restart the JVM backend when the backend repo pushes.
            "systemctl restart backend.service --no-block --no-ask-password",
        ),
    ),
    "frontend-next" to DeploymentProfile(
        repoFullName = "sdfgsdfgd/frontend-next",
        commands = listOf(
            "sudo systemctl restart frontend.service --no-ask-password --no-block",
        ),
    ),
    "server-py" to DeploymentProfile(
        repoFullName = "sdfgsdfgd/server_py",
        commands = listOf(
            "XDG_RUNTIME_DIR=/run/user/1000 systemctl --user restart server_py.service",
            "timeout 180 bash -c 'until [ -S /tmp/server_py/server_py.sock ]; do sleep 3; done'"
        ),
    ),
    "server-py-selftest" to DeploymentProfile(
        commands = listOf(
            "curl -fsS -H 'Content-Type: application/json' -H 'X-GitHub-Event: manual-selftest' -d '{}' http://127.0.0.1/api/selftest/run"
        ),
    ),
)

private val repoToSlug: Map<String, String> = deploymentProfiles
    .mapNotNull { (slug, profile) ->
        profile.repoFullName?.lowercase()?.let { it to slug }
    }
    .toMap()

private suspend fun ApplicationCall.processGitHubWebhook(targetOverride: String?) {
    val deploymentLog = File(resolveLogDir(), "webhook.log")
    val payload = runCatching { receiveText() }.getOrElse { error ->
        log(
            "❌ Error reading GitHub webhook payload: ${error.localizedMessage}",
            deploymentLog
        )
        respondText(
            "❌ Error processing GitHub webhook: ${error.localizedMessage}",
            status = HttpStatusCode.InternalServerError
        )
        return
    }

    val eventType = request.headers["X-GitHub-Event"] ?: "unknown"
    val senderLogin = extractSenderLogin(payload)
    val repoFullName = extractRepoFullName(payload)
    val requestedSlug = targetOverride?.lowercase()
    val matchedSlug = when {
        requestedSlug != null && deploymentProfiles.containsKey(requestedSlug) -> requestedSlug
        repoFullName != null -> repoToSlug[repoFullName.lowercase()]
        else -> null
    } ?: "default"
    if (matchedSlug == "server-py" && eventType == "push" && senderLogin == "github-actions[bot]") {
        log("ℹ️ Skipping server-py deployment for GitHub Actions bot push.", deploymentLog)
        respondText("🙇 Deployment skipped for GitHub Actions bot push", status = HttpStatusCode.Accepted)
        return
    }

    val profile = deploymentProfiles.getValue(matchedSlug)

    val commandsToRun = if (matchedSlug == "server-py-selftest") {
        val newChatFlag = runCatching {
            json.parseToJsonElement(payload)
                .jsonObject["new_chat"]
                ?.jsonPrimitive
                ?.booleanOrNull
        }.getOrNull()

        val payloadBody = """{"new_chat": ${newChatFlag ?: false}}"""
        val selfTestCmd =
            """curl -fsS -H 'Content-Type: application/json' -H 'X-GitHub-Event: manual-selftest' -d '$payloadBody' http://127.0.0.1/api/selftest/run"""
        listOf(selfTestCmd)
    } else {
        profile.commands
    }

    if (requestedSlug != null && requestedSlug != matchedSlug) {
        log("ℹ️ Unregistered webhook slug '$requestedSlug' - using $matchedSlug.", deploymentLog)
    }
    if (repoFullName != null && repoToSlug[repoFullName.lowercase()] == null) {
        log("ℹ️ Repository '$repoFullName' not registered - using $matchedSlug.", deploymentLog)
    }

    println("GitHub payload ($eventType -> $matchedSlug): $payload")
    log(
        "📬 GitHub webhook event='$eventType', repo='${repoFullName ?: "unknown"}', target='$matchedSlug', route='${targetOverride ?: "default"}'.",
        deploymentLog
    )

    respondText(
        "🙇 Deployment triggered for $matchedSlug!",
        status = HttpStatusCode.Accepted
    )

    webhookDeployMutex.withLock {
        for (command in commandsToRun) {
            log("Running command for $matchedSlug: '$command'", deploymentLog)
            val exit = command.shell(logFile = deploymentLog)
            if (exit == 0) {
                log("✅ SUCCESS ($matchedSlug): '$command' executed (exit=$exit).", deploymentLog)
            } else {
                log("❌ FAILURE ($matchedSlug): '$command' failed (exit=$exit).", deploymentLog)
            }
        }
    }
}

private fun extractSenderLogin(payload: String): String? = runCatching {
    json.parseToJsonElement(payload)
        .jsonObject["sender"]
        ?.jsonObject
        ?.get("login")
        ?.jsonPrimitive
        ?.content
}.getOrNull()

private fun extractRepoFullName(payload: String): String? = runCatching {
    json.parseToJsonElement(payload)
        .jsonObject["repository"]
        ?.jsonObject
        ?.get("full_name")
        ?.jsonPrimitive
        ?.content
}.getOrNull()
