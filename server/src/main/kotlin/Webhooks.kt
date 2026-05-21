package net.sdfgsdfg

import io.ktor.http.ContentType
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val webhookDeployMutex = Mutex()
private val webhookSelfTestMutex = Mutex()
private val webhookArcanaSmokeMutex = Mutex()

fun Route.githubWebhookRoute() {
    post("/webhook/github") { call.processGitHubWebhook(targetOverride = null) }
    post("/webhook/github/{target}") { call.processGitHubWebhook(targetOverride = call.parameters["target"]) }
}

private val json = Json { ignoreUnknownKeys = true }
private val arcanaSmokeWebhookSecret = System.getenv("ARCANA_SMOKE_WEBHOOK_SECRET")?.trim().takeIf { !it.isNullOrEmpty() }
private val arcanaSmokeWebhookHeader = System.getenv("ARCANA_SMOKE_WEBHOOK_HEADER")?.trim()
    .takeIf { !it.isNullOrEmpty() } ?: "X-Arcana-Smoke-Secret"
private const val SERVER_PY_REPO_DIR = "/home/x/Desktop/py/server_py"
private const val SERVER_PY_READY_CMD = "timeout 180 bash -c 'until [ -S /tmp/server_py/server_py.sock ]; do sleep 3; done'"
private val gitShaRegex = Regex("[0-9a-f]{40}")

private data class DeploymentProfile(
    val commands: List<String>,
    val repoFullName: String? = null,
)

// Register GitHub repositories that should trigger deployments here.
private val deploymentProfiles: Map<String, DeploymentProfile> = mapOf(
    "default" to DeploymentProfile(
        repoFullName = "sdfgsdfgd/backend",
        commands = listOf(
            // Schedule deploy outside backend.service so the live backend stays up while the new build verifies.
            "sudo systemd-run --unit=backend-deploy-${'$'}(date +%s%N) --collect --property=User=x --property=Group=x --working-directory=/home/x/Desktop/kotlin/backend --setenv=JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 --setenv=PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:/home/x/.sdkman/candidates/kotlin/current/bin:/usr/bin:/bin /home/x/.sdkman/candidates/kotlin/current/bin/kotlin /home/x/Desktop/kotlin/backend/0_scripts/deploy.main.kts deploy 2>&1",
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
            SERVER_PY_READY_CMD
        ),
    ),
    "server-py-selftest" to DeploymentProfile(
        commands = listOf(
            "curl -fsS -H 'Content-Type: application/json' -H 'X-GitHub-Event: manual-selftest' -d '{}' http://127.0.0.1/api/selftest/run"
        ),
    ),
    "arcana-smoke" to DeploymentProfile(
        commands = listOf(
            "cd /home/x/Desktop/kotlin/backend && /home/x/.sdkman/candidates/kotlin/current/bin/kotlin /home/x/Desktop/kotlin/backend/0_scripts/deploy.main.kts arcana-smoke"
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
    val repoFullName = extractRepoFullName(payload)
    val requestedSlug = targetOverride?.lowercase()
    val matchedSlug = when {
        requestedSlug != null && deploymentProfiles.containsKey(requestedSlug) -> requestedSlug
        repoFullName != null -> repoToSlug[repoFullName.lowercase()]
        else -> null
    } ?: "default"
    if (matchedSlug !in setOf("server-py-selftest", "arcana-smoke") && eventType != "push") {
        log("ℹ️ Ignoring GitHub webhook event='$eventType' for target='$matchedSlug'; deployments only run on push.", deploymentLog)
        respondText("🙇 Deployment ignored for non-push GitHub event '$eventType'", status = HttpStatusCode.Accepted)
        return
    }
    val profile = deploymentProfiles.getValue(matchedSlug)

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

    if (matchedSlug == "server-py-selftest") {
        /*
         * Exact-commit selftest gate
         * --------------------------
         * A push to server_py creates two independent webhook paths:
         *
         * 1. server-py deploy: restart server_py.service; its systemd pre-start
         *    syncs /home/x/Desktop/py/server_py to origin/main, then the deploy
         *    waits for /tmp/server_py/server_py.sock.
         * 2. server-py-selftest: GitHub Actions asks this backend to run the
         *    live browser/gRPC selftest.
         *
         * The selftest is only meaningful if it proves the exact commit that
         * triggered the workflow. Reachability alone is too weak: a fast selftest
         * can otherwise hit the previous Python process, or hit the new process
         * before its Unix socket exists.
         *
         * Lock order is deliberate:
         * - wait for the requested git HEAD before taking webhookDeployMutex, so
         *   an early selftest cannot block the deploy that will produce that HEAD;
         * - take webhookDeployMutex, which queues behind any in-flight deploy;
         * - take webhookSelfTestMutex, so multiple live audits never overlap;
         * - recheck HEAD under the lock, then wait for the UDS socket and call
         *   /api/selftest/run.
         */
        val selfTestPayload = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
        val newChatFlag = selfTestPayload?.get("new_chat")?.jsonPrimitive?.booleanOrNull
        val workflowUrl = selfTestPayload?.get("workflow_url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val headSha = selfTestPayload?.get("head_sha")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
        if (headSha != null && !gitShaRegex.matches(headSha)) {
            respondText(
                text = """{"ok":false,"raw_error":"invalid head_sha"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.BadRequest,
            )
            return
        }
        val payloadBody = buildString {
            append("""{"new_chat": ${newChatFlag ?: false}""")
            workflowUrl?.let { append(""", "workflow_url": "${it.escapeJson()}"""") }
            headSha?.let { append(""", "head_sha": "$it"""") }
            append("}")
        }
        val quotedPayloadBody = "'${payloadBody.replace("'", "'\\''")}'"
        val selfTestCmd =
            """$SERVER_PY_READY_CMD && curl -fsS -H 'Content-Type: application/json' -H 'X-GitHub-Event: manual-selftest' -d $quotedPayloadBody http://127.0.0.1/api/selftest/run"""

        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()
        suspend fun run(command: String) = runWebhookCommand(matchedSlug, command, deploymentLog, stdoutLines, stderrLines)

        var rawError = "selftest webhook command failed"
        val exit = headSha?.let { sha ->
            val waitExit = run(serverPyHeadCmd(sha, wait = true))
            if (waitExit != 0) {
                rawError = "server_py checkout did not reach requested head"
                waitExit
            } else webhookDeployMutex.withLock {
                webhookSelfTestMutex.withLock {
                    val checkExit = run(serverPyHeadCmd(sha, wait = false))
                    if (checkExit != 0) {
                        rawError = "server_py checkout changed before selftest"
                        checkExit
                    } else {
                        run(selfTestCmd)
                    }
                }
            }
        } ?: webhookDeployMutex.withLock {
            webhookSelfTestMutex.withLock { run(selfTestCmd) }
        }

        val stdoutBody = stdoutLines.joinToString("\n").trim()
        if (exit == 0) {
            val parsed = runCatching { json.parseToJsonElement(stdoutBody) }.getOrNull()
            if (parsed != null) {
                respondText(
                    text = stdoutBody,
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                )
            } else {
                val preview = stdoutBody.ifBlank { "(empty)" }.escapeJson()
                respondText(
                    text = """{"ok":false,"raw_error":"non-json selftest webhook response","preview":"$preview"}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadGateway,
                )
            }
            return
        }

        val stderrBody = stderrLines.joinToString("\n").ifBlank { "(empty)" }.escapeJson()
        val stdoutPreview = stdoutBody.ifBlank { "(empty)" }.escapeJson()
        val headShaField = headSha?.let { ",\"head_sha\":\"$it\"" } ?: ""
        respondText(
            text = """{"ok":false,"raw_error":"$rawError","exit_code":$exit$headShaField,"stderr":"$stderrBody","stdout":"$stdoutPreview"}""",
            contentType = ContentType.Application.Json,
            status = if (rawError == "server_py checkout did not reach requested head") HttpStatusCode.GatewayTimeout else HttpStatusCode.InternalServerError,
        )
        return
    }

    if (matchedSlug == "arcana-smoke") {
        if (arcanaSmokeWebhookSecret.isNullOrBlank() || request.headers[arcanaSmokeWebhookHeader]?.trim() != arcanaSmokeWebhookSecret) {
            log("⚠️ Rejected unauthorized arcana-smoke webhook.", deploymentLog)
            respondText(
                text = """{"ok":false,"raw_error":"unauthorized arcana-smoke webhook"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Unauthorized,
            )
            return
        }

        val command = profile.commands.single()
        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()
        val exit = webhookArcanaSmokeMutex.withLock {
            runWebhookCommand(matchedSlug, command, deploymentLog, stdoutLines, stderrLines)
        }
        val stdoutPreview = stdoutLines.joinToString("\n").trim().take(4_000).escapeJson()
        val stderrPreview = stderrLines.joinToString("\n").trim().take(4_000).escapeJson()
        respondText(
            text = """{"ok":${exit == 0},"exit_code":$exit,"stdout":"$stdoutPreview","stderr":"$stderrPreview"}""",
            contentType = ContentType.Application.Json,
            status = if (exit == 0) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
        )
        return
    }

    respondText(
        "🙇 Deployment triggered for $matchedSlug!",
        status = HttpStatusCode.Accepted
    )

    webhookDeployMutex.withLock {
        for (command in profile.commands) {
            runWebhookCommand(matchedSlug, command, deploymentLog)
        }
    }
}

private suspend fun runWebhookCommand(
    slug: String,
    command: String,
    logFile: File,
    stdoutLines: MutableList<String>? = null,
    stderrLines: MutableList<String>? = null,
): Int {
    val capture: (suspend (String, Boolean) -> Unit)? =
        if (stdoutLines == null && stderrLines == null) null
        else { line, isError -> (if (isError) stderrLines else stdoutLines)?.add(line) }

    log("Running command for $slug: '$command'", logFile)
    val exit = command.shell(logFile = logFile, onLine = capture)
    val ok = exit == 0
    log("${if (ok) "✅ SUCCESS" else "❌ FAILURE"} ($slug): '$command' ${if (ok) "executed" else "failed"} (exit=$exit).", logFile)
    return exit
}

private fun extractRepoFullName(payload: String): String? = runCatching {
    json.parseToJsonElement(payload)
        .jsonObject["repository"]
        ?.jsonObject
        ?.get("full_name")
        ?.jsonPrimitive
        ?.content
}.getOrNull()

private fun String.escapeJson(): String = buildString {
    for (ch in this@escapeJson) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

private fun serverPyHeadCmd(headSha: String, wait: Boolean): String =
    if (wait)
        """timeout 180 bash -c 'cd $SERVER_PY_REPO_DIR && until [ "${'$'}(git rev-parse HEAD 2>/dev/null)" = "$headSha" ]; do echo "waiting for server_py HEAD $headSha (current=${'$'}(git rev-parse HEAD 2>/dev/null || echo unknown))" >&2; sleep 3; done; echo "server_py HEAD ready: $headSha" >&2'"""
    else
        """cd $SERVER_PY_REPO_DIR && current=${'$'}(git rev-parse HEAD) && [ "${'$'}current" = "$headSha" ] || { echo "server_py HEAD mismatch: expected $headSha current=${'$'}current" >&2; exit 1; }"""
