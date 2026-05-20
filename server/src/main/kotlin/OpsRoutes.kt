package net.sdfgsdfg

import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
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
import kotlinx.serialization.json.jsonPrimitive
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

private val opsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val serverPySelfTestFile = File(resolveLogDir(), "server-py-selftest.json")
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
    } else if (responseFile.extension.equals("wasm", ignoreCase = true)) {
        response.headers.append(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        respondOutputStream(ContentType.parse("application/wasm")) {
            responseFile.inputStream().use { it.copyTo(this) }
        }
    } else {
        response.headers.append(HttpHeaders.CacheControl, "no-cache")
        respondFile(responseFile)
    }
}

private fun opsSummary(): OpsSummaryDto {
    val serverPySelfTest = latestServerPySelfTest()
    val backendLatestRun = TestRunSummaryDto(
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
                note = "README matrix remains canonical until dashboard parity.",
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
    TestRunSummaryDto("README matrix parity", OpsStatusDto.WIP, detail = "Dashboard must reach README live matrix parity before absorption."),
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
        latencyMs = latencyMs,
        askLatencyMs = askLatencyMs,
        auditLatencyMs = auditLatencyMs,
        textExcerpt = textExcerpt,
        rawError = rawError,
        retried = retried,
        caseCount = caseSummaries.size,
        casePassCount = caseSummaries.count { it.status == OpsStatusDto.OK },
        zenPresent = zen != null,
        artifacts = listOf(OpsArtifactDto(name = "server-py-selftest.json", path = serverPySelfTestFile.path)),
        cases = caseSummaries,
    )
}
