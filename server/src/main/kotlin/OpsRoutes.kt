package net.sdfgsdfg

import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.sdfgsdfg.data.model.IssueSummaryDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsArtifactDto
import net.sdfgsdfg.data.model.RepoHealthDto
import net.sdfgsdfg.data.model.SelfTestCaseSummaryDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import net.sdfgsdfg.data.model.SelfTestSummaryDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val opsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val opsTimeFormatter = DateTimeFormatter
    .ofPattern("d MMM, h:mm a z", Locale.ENGLISH)
    .withZone(ZoneId.of("Australia/Melbourne"))

private val serverPySelfTestFile = File(resolveLogDir(), "server-py-selftest.json")
private val deployHistoryFile = File(resolveLogDir(), "deploy-history.jsonl")
private val homeDir = File(System.getProperty("user.home"))
private val backendRepo = homeDir.resolve("Desktop/kotlin/backend")
private val serverPyRepo = homeDir.resolve("Desktop/py/server_py")
private val arcanaRepo = homeDir.resolve("Desktop/py/arcana")
private val publicIngressUrl = "https://sdfgsdfg.net/test"

fun Route.opsRoutes(localPreview: Boolean = System.getenv("BACKEND_ENV") == "local") {
    get("/api/ops/summary") {
        val opsHost = call.request.host().substringBefore(':').lowercase() == "ops.sdfgsdfg.net"
        if (!opsHost && !(localPreview && call.clientInfo().isLocal)) {
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
            return@get
        }
        call.respond(opsSummary())
    }
}

internal suspend fun ApplicationCall.respondOpsDashboard(
    dashboardDir: File = Paths.get("dashboard", "web", "build", "dist", "wasmJs", "productionExecutable").toFile(),
) {
    val path = request.path().trimStart('/')
    if (path.startsWith("api/")) {
        respondText("Not Found", status = HttpStatusCode.NotFound)
    } else {
        respondDashboardAsset(path.ifBlank { "index.html" }, dashboardDir)
    }
}

private suspend fun ApplicationCall.respondDashboardAsset(asset: String, dashboardDir: File) {
    val root = dashboardDir.canonicalFile
    val requested = root.resolve(asset.ifBlank { "index.html" }).canonicalFile
    val insideRoot = requested == root || requested.path.startsWith(root.path + File.separator)
    val target = requested.takeIf { insideRoot && it.isFile }
    val fallback = root.resolve("index.html").takeIf { !asset.substringAfterLast('/').contains('.') && it.isFile }
    val responseFile = target ?: fallback

    if (responseFile == null) {
        respondText(
            text = if (root.isDirectory) "Not Found" else "Trio Ops Cockpit artifact is not built yet. Run ./gradlew :dashboard:web:wasmJsBrowserDistribution.",
            contentType = ContentType.Text.Plain,
            status = if (root.isDirectory) HttpStatusCode.NotFound else HttpStatusCode.ServiceUnavailable,
        )
    } else {
        response.headers.append(HttpHeaders.CacheControl, responseFile.dashboardCacheControl())
        if (responseFile.name == "index.html") {
            respondText(responseFile.dashboardIndex(root), ContentType.Text.Html)
        } else {
            respondOutputStream(responseFile.dashboardContentType()) {
                responseFile.inputStream().use { it.copyTo(this) }
            }
        }
    }
}

private fun File.dashboardIndex(root: File): String {
    val script = root.resolve("dashboard-web.js")
    val styles = root.resolve("styles.css")
    val scriptVersion = script.takeIf { it.isFile }?.sha256Prefix() ?: lastModified().toString()
    val styleVersion = styles.takeIf { it.isFile }?.sha256Prefix() ?: lastModified().toString()
    return readText()
        .replace(Regex("""styles\.css(?:\?v=[^"]*)?"""), "styles.css?v=$styleVersion")
        .replace(Regex("""dashboard-web\.js(?:\?v=[^"]*)?"""), "dashboard-web.js?v=$scriptVersion")
}

private fun File.sha256Prefix(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().take(8).joinToString("") { "%02x".format(it) }
}

private fun File.dashboardCacheControl() = if (extension.equals("wasm", ignoreCase = true)) {
    "public, max-age=31536000, immutable"
} else {
    "no-cache"
}

private fun File.dashboardContentType() = when (extension.lowercase()) {
    "css" -> ContentType.Text.CSS
    "html" -> ContentType.Text.Html
    "js" -> ContentType.Application.JavaScript
    "wasm" -> ContentType.parse("application/wasm")
    else -> ContentType.Application.OctetStream
}

private fun opsSummary(): OpsSummaryDto {
    val serverPySelfTest = latestServerPySelfTest()
    val backendHistory = deployHistory()
    val backendLatestRun = backendHistory.firstOrNull() ?: TestRunSummaryDto(
        label = "local smoke",
        status = OpsStatusDto.OK,
        detail = "/test and /metrics/security are deploy-gated",
    )
    val arcanaLatestRun = TestRunSummaryDto(
        label = "local-first publisher",
        status = OpsStatusDto.WIP,
        detail = ".arcana/issues.json and run ingestion stay local-first.",
    )

    return OpsSummaryDto(
        generatedAtMs = System.currentTimeMillis(),
        repos = listOf(
            RepoHealthDto(
                id = "backend",
                name = "backend",
                role = "Ktor control plane",
                status = OpsStatusDto.OK,
                location = backendRepo.path,
                serviceName = "backend.service",
                latestRun = backendLatestRun,
                runs = backendRuns(backendLatestRun),
                history = backendHistory,
                issues = localArcanaIssues(backendRepo),
                note = "Production deploy verifies before restart.",
            ),
            RepoHealthDto(
                id = "server_py",
                name = "server_py",
                role = "ChatGPT/browser automation bridge",
                status = serverPySelfTest?.let { if (it.ok) OpsStatusDto.OK else OpsStatusDto.FAIL } ?: OpsStatusDto.UNKNOWN,
                location = serverPyRepo.path,
                serviceName = "server_py.service",
                latestRun = serverPySelfTest?.toRunSummary(),
                runs = serverPyRuns(serverPySelfTest),
                selfTest = serverPySelfTest?.toOpsSelfTestSummary(),
                issues = localArcanaIssues(serverPyRepo),
                note = "Live selftest JSON is rendered by the dashboard and preserved as a workflow artifact.",
            ),
            RepoHealthDto(
                id = "arcana",
                name = "arcana",
                role = "Local codebase comprehension and session engine",
                status = OpsStatusDto.WIP,
                location = arcanaRepo.path,
                latestRun = arcanaLatestRun,
                runs = arcanaRuns(arcanaLatestRun),
                issues = localArcanaIssues(arcanaRepo),
                note = "RSI/session ingestion is intentionally deferred.",
            ),
        ),
    )
}

private fun latestServerPySelfTest(): SelfTestResultDto? = runCatching {
    serverPySelfTestFile.takeIf { it.exists() }
        ?.readText()
        ?.let { opsJson.decodeFromString<SelfTestResultDto>(it) }
}.getOrNull()

internal fun deployHistory(file: File = deployHistoryFile): List<TestRunSummaryDto> = runCatching {
    file.takeIf { it.isFile }
        ?.readLines()
        ?.asReversed()
        ?.mapNotNull { line ->
            val item = runCatching { opsJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@mapNotNull null
            val head = item.text("head")
            TestRunSummaryDto(
                label = item.text("label") ?: head?.let { "deploy $it" } ?: "deploy",
                status = item.text("status")?.let { status -> runCatching { OpsStatusDto.valueOf(status) }.getOrNull() } ?: OpsStatusDto.UNKNOWN,
                timestampMs = item.long("timestamp_ms"),
                durationMs = item.double("duration_ms"),
                detail = item.text("detail") ?: item.text("mode"),
            )
        }
        ?.take(8)
        ?: emptyList()
}.getOrDefault(emptyList())

private fun backendRuns(latestRun: TestRunSummaryDto) = listOf(
    latestRun,
    TestRunSummaryDto("server checks", OpsStatusDto.OK, detail = "Gradle :verifyServer gates production deploy before restart."),
    TestRunSummaryDto("public ingress", OpsStatusDto.WIP, detail = "External probe stays outside restart gating.", url = publicIngressUrl),
)

private fun serverPyRuns(selfTest: SelfTestResultDto?): List<TestRunSummaryDto> = listOfNotNull(
    selfTest?.toRunSummary() ?: TestRunSummaryDto("live selftest", OpsStatusDto.UNKNOWN, detail = "Waiting for persisted server-py-selftest.json."),
    selfTest?.cases?.takeIf { it.isNotEmpty() }?.let { cases ->
        val passed = cases.count { it.ok }
        TestRunSummaryDto(
            label = "model matrix",
            status = if (passed == cases.size) OpsStatusDto.OK else OpsStatusDto.FAIL,
            detail = "$passed/${cases.size} model cases passing.",
        )
    },
    TestRunSummaryDto("dashboard selftest parity", OpsStatusDto.OK, detail = "Dashboard renders the live selftest JSON, workflow link, and model matrix."),
    TestRunSummaryDto("gRPC/browser bridge", OpsStatusDto.WIP, detail = "server_py owns automation internals; backend displays normalized facts."),
)

private fun arcanaRuns(latestRun: TestRunSummaryDto) = listOf(
    latestRun,
    TestRunSummaryDto("pytest unit spine", OpsStatusDto.WIP, detail = "Future local publisher reports pytest/session/issue summaries."),
    TestRunSummaryDto("RSI sessions", OpsStatusDto.WIP, detail = "Deferred until issue and CI surfaces can receive output."),
)

internal fun localArcanaIssues(repoRoot: File): IssueSummaryDto = runCatching {
    val file = repoRoot.resolve(".arcana/issues.json").takeIf { it.isFile } ?: return IssueSummaryDto()
    issueObjects(opsJson.parseToJsonElement(file.readText()))
        .mapNotNull { issue -> issue.statusText() }
        .fold(IssueSummaryDto()) { summary, status -> summary.with(status) }
}.getOrDefault(IssueSummaryDto())

private fun issueObjects(element: JsonElement): List<JsonObject> = when (element) {
    is JsonArray -> element.filterIsInstance<JsonObject>()
    is JsonObject -> (element["issues"] as? JsonArray)?.filterIsInstance<JsonObject>()
        ?: element.values.filterIsInstance<JsonObject>().filter { it.statusText() != null }
    else -> emptyList()
}

private fun JsonObject.statusText(): String? = sequenceOf("status", "state")
    .mapNotNull { this[it]?.jsonPrimitive?.contentOrNull }
    .firstOrNull()

private fun IssueSummaryDto.with(status: String): IssueSummaryDto = when (status.trim().lowercase().replace("-", "_")) {
    "todo", "to_do", "open", "new" -> copy(todo = todo + 1)
    "wip", "doing", "in_progress", "progress" -> copy(wip = wip + 1)
    "blocked", "blocker" -> copy(blocked = blocked + 1)
    "review", "in_review", "reviewing" -> copy(review = review + 1)
    "done", "complete", "completed", "closed" -> copy(done = done + 1)
    else -> this
}

private fun SelfTestResultDto.toRunSummary() = TestRunSummaryDto(
    label = "live selftest",
    status = if (ok) OpsStatusDto.OK else OpsStatusDto.FAIL,
    timestampMs = timestampMs.takeIf { it > 0 },
    durationMs = latencyMs,
    detail = rawError ?: textExcerpt.take(120).ifBlank { null },
)

internal fun SelfTestResultDto.toOpsSelfTestSummary(): SelfTestSummaryDto {
    val caseSummaries = cases.map {
        SelfTestCaseSummaryDto(
            name = it.name,
            status = if (it.ok) OpsStatusDto.OK else OpsStatusDto.FAIL,
            latencyMs = it.latencyMs,
            note = it.note,
        )
    }
    return SelfTestSummaryDto(
        status = if (ok) OpsStatusDto.OK else OpsStatusDto.FAIL,
        ok = ok,
        satisfiedExpectation = satisfiedExpectation,
        timestampMs = timestampMs.takeIf { it > 0 },
        timestampLabel = timestampMs.takeIf { it > 0 }?.let { opsTimeFormatter.format(Instant.ofEpochMilli(it)) },
        latencyMs = latencyMs,
        askLatencyMs = askLatencyMs,
        auditLatencyMs = auditLatencyMs,
        textExcerpt = textExcerpt,
        rawError = rawError,
        retried = retried,
        caseCount = caseSummaries.size,
        casePassCount = caseSummaries.count { it.status == OpsStatusDto.OK },
        zenPresent = zen != null,
        zenState = zen?.text("state"),
        zenReason = zen?.text("reason"),
        zenSeverity = zen?.text("severity"),
        zenArtifactPath = zen?.text("folder") ?: zen?.text("context_file") ?: zen?.text("status_file"),
        workflowUrl = workflowUrl,
        artifacts = listOf(OpsArtifactDto(name = "server-py-selftest.json", path = serverPySelfTestFile.path)),
        cases = caseSummaries,
    )
}

private fun JsonObject.text(name: String): String? = this[name]
    ?.jsonPrimitive
    ?.contentOrNull
    ?.takeIf { it.isNotBlank() }

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull
