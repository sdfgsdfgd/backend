package net.sdfgsdfg

import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.sdfgsdfg.data.model.ArcanaIngestDto
import net.sdfgsdfg.data.model.IssueSummaryDto
import net.sdfgsdfg.data.model.IssueSourceSummaryDto
import net.sdfgsdfg.data.model.OpsHostSnapshotDto
import net.sdfgsdfg.data.model.OpsSignalDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsArtifactDto
import net.sdfgsdfg.data.model.RepoHealthDto
import net.sdfgsdfg.data.model.SelfTestCaseSummaryDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import net.sdfgsdfg.data.model.SelfTestSummaryDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.UnixDomainSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.SocketChannel
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val opsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val opsZoneId = ZoneId.of("Australia/Melbourne")
private val opsTimeFormatter = DateTimeFormatter
    .ofPattern("d MMM, h:mm a z", Locale.ENGLISH)
    .withZone(opsZoneId)
private val sessionDatePathFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.systemDefault())
private val psStartFormatter = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH)

private val defaultArcanaIngestFile = File(resolveLogDir(), "arcana-ingest.json")
private val serverPySelfTestFile = File(resolveLogDir(), "server-py-selftest.json")
private val deployHistoryFile = File(resolveLogDir(), "deploy-history.jsonl")
private val homeDir = File(System.getProperty("user.home"))
private val backendRepo = homeDir.resolve("Desktop/kotlin/backend")
private val serverPyRepo = homeDir.resolve("Desktop/py/server_py")
private val arcanaRepo = homeDir.resolve("Desktop/py/arcana")
private val serverPySocket = File("/tmp/server_py/server_py.sock")
private val backendFullSuiteUrl = "https://github.com/sdfgsdfgd/backend/actions/workflows/full-suite.yml"
private val serverPyLiveSelftestUrl = "https://github.com/sdfgsdfgd/server_py/actions/workflows/live-selftest.yml"
private val publicIngressUrl = "https://sdfgsdfg.net/test"
private const val serverPySelfTestArtifactUrl = "/api/ops/artifacts/server-py-selftest.json"
private const val arcanaIngestArtifactUrl = "/api/ops/artifacts/arcana-ingest.json"
private val githubHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
private val opsPeerHttp = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
private val githubIssueCache = mutableMapOf<String, CachedIssueSummary>()
private const val githubIssueCacheMs = 5 * 60 * 1_000L
private const val localRuntimeLabel = "local"
private const val qRuntimeLabel = "remote q"
private const val macHostSnapshotUrl = "http://192.168.1.2/api/ops/host-snapshot"
private const val qHostSnapshotUrl = "http://192.168.1.4/api/ops/host-snapshot"
private val macSshTarget = System.getenv("OPS_MAC_SSH_TARGET")?.takeIf { it.isNotBlank() } ?: "x@192.168.1.2"
private const val peerSnapshotRefreshMs = 45_000L
private const val peerSnapshotFailBackoffMs = 45_000L
private const val peerSnapshotStaleMs = 150_000L
private const val activeProcessesLabel = "active"

private data class CachedIssueSummary(val expiresAtMs: Long, val summary: IssueSummaryDto)
private data class CachedPeerSnapshot(val url: String, val snapshot: OpsHostSnapshotDto?, val nextAttemptAtMs: Long, val misses: Int = 0)

private val peerSnapshotLock = Any()
private var peerSnapshotCache: CachedPeerSnapshot? = null

fun Route.opsRoutes(
    localPreview: Boolean = System.getenv("BACKEND_ENV") == "local",
    arcanaIngestTargetFile: File = defaultArcanaIngestFile,
    selfTestArtifactFile: File = serverPySelfTestFile,
    deployHistorySourceFile: File = deployHistoryFile,
    githubIssues: (String) -> IssueSummaryDto = ::githubIssues,
    enablePeerSnapshots: Boolean = false,
    peerSnapshot: (Boolean) -> OpsHostSnapshotDto? = ::peerHostSnapshot,
) {
    fun allowed(call: ApplicationCall): Boolean {
        val opsHost = call.request.host().substringBefore(':').lowercase() == "ops.sdfgsdfg.net"
        return opsHost || (localPreview && call.clientInfo().isLocal)
    }

    suspend fun ApplicationCall.respondJsonArtifact(file: File) {
        if (!allowed(this)) {
            respondText("Not Found", status = HttpStatusCode.NotFound)
            return
        }

        val artifact = file.takeIf { it.isFile }
        if (artifact == null) {
            respondText("Not Found", status = HttpStatusCode.NotFound)
        } else {
            response.headers.append(HttpHeaders.CacheControl, "no-store")
            respondText(artifact.readText(), ContentType.Application.Json)
        }
    }

    get("/api/ops/summary") {
        if (!allowed(call)) {
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
            return@get
        }
        call.respond(opsSummary(localPreview, arcanaIngestTargetFile, selfTestArtifactFile, deployHistorySourceFile, githubIssues, if (enablePeerSnapshots) peerSnapshot(localPreview) else null))
    }

    get("/api/ops/host-snapshot") {
        if (!call.clientInfo().isLocal) {
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
            return@get
        }
        call.respond(hostSnapshot(localPreview))
    }

    post("/api/ops/ingest/arcana") {
        if (!call.clientInfo().isLocal) {
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
            return@post
        }

        val ingest = runCatching {
            opsJson.decodeFromString<ArcanaIngestDto>(call.receiveText())
        }.getOrElse {
            call.respondText("Invalid Arcana ingest JSON", status = HttpStatusCode.BadRequest)
            return@post
        }.let {
            it.copy(timestampMs = it.timestampMs ?: System.currentTimeMillis())
        }

        arcanaIngestTargetFile.parentFile?.mkdirs()
        arcanaIngestTargetFile.writeText(opsJson.encodeToString(ingest))
        call.respondText("""{"ok":true}""", ContentType.Application.Json, HttpStatusCode.Accepted)
    }

    get(serverPySelfTestArtifactUrl) {
        call.respondJsonArtifact(selfTestArtifactFile)
    }

    get(arcanaIngestArtifactUrl) {
        call.respondJsonArtifact(arcanaIngestTargetFile)
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

private fun opsSummary(
    localPreview: Boolean = System.getenv("BACKEND_ENV") == "local",
    arcanaIngestFile: File = defaultArcanaIngestFile,
    selfTestFile: File = serverPySelfTestFile,
    historyFile: File = deployHistoryFile,
    githubIssues: (String) -> IssueSummaryDto = ::githubIssues,
    peerSnapshot: OpsHostSnapshotDto? = null,
): OpsSummaryDto {
    val ownServerPySelfTest = latestServerPySelfTest(selfTestFile)?.toOpsSelfTestSummary()
    val ownSnapshot = hostSnapshot(localPreview, ownServerPySelfTest)
    val httpPeerSnapshot = peerSnapshot?.takeIf { it.host != "ssh:$localRuntimeLabel" }
    val snapshots = listOfNotNull(ownSnapshot, httpPeerSnapshot).distinctBy { it.host }
    val processSnapshots = listOfNotNull(ownSnapshot, peerSnapshot).distinctBy { it.host }
    val backendRuntimeLabels = snapshots.runtimeLabels { it.backendRuntimeLabel }
    val arcanaRuntimeLabels = processSnapshots.runtimeLabels { it.backendRuntimeLabel }
    val serverPyReadySnapshot = snapshots.firstOrNull { it.serverPyReady } ?: ownSnapshot
    val serverPyRuntimeLabel = serverPyReadySnapshot.serverPyRuntimeLabel
    val serverPyRuntimeLabels = snapshots.runtimeLabels { it.serverPyRuntimeLabel }
    val serverPySelfTest = ownServerPySelfTest ?: snapshots.firstNotNullOfOrNull { it.serverPySelfTest }
    val serverPyReady = serverPyReadySnapshot.serverPyReady
    val serverPyTransport = serverPyReadySnapshot.serverPyTransport
    val serverPySocketStatus = if (serverPyReady) OpsStatusDto.OK else OpsStatusDto.UNKNOWN
    val serverPyStatus = serverPySocketStatus
    val serverPyLatestRun = serverPySelfTest?.toRunSummary() ?: TestRunSummaryDto(
        label = "gRPC bridge",
        status = serverPyStatus,
        detail = if (serverPyReady) "$serverPyTransport bridge ready." else "$serverPyTransport bridge unavailable.",
    )
    val arcanaIngest = latestArcanaIngest(arcanaIngestFile)
    val backendHistory = deployHistory(historyFile)
    val backendIssues = repoIssues(backendRepo, "backend", githubIssues)
    val serverPyIssues = repoIssues(serverPyRepo, "server_py", githubIssues)
    val backendCurrentRun = TestRunSummaryDto(
        label = if (localPreview) "local preview" else "deploy smoke",
        status = OpsStatusDto.OK,
        detail = if (localPreview) {
            "Local health probes passed."
        } else {
            "Deploy health probes passed."
        },
    )
    val backendLatestRun = if (localPreview) backendCurrentRun else backendHistory.firstOrNull() ?: backendCurrentRun
    val arcanaLatestRun = arcanaIngest?.toRunSummary()
    val arcanaIssues = arcanaIngest?.issues?.takeIf { it.hasAny() }?.withSource("arcana", "Arcana ingest", arcanaIngestArtifactUrl)
        ?: localArcanaIssues(arcanaRepo)
    val arcanaSignals = mergedArcanaSignals(processSnapshots)
    val arcanaStatus = if (arcanaSignals.firstOrNull { it.isActiveProcessSignal() }?.status == OpsStatusDto.OK) {
        OpsStatusDto.OK
    } else {
        arcanaIngest?.status ?: OpsStatusDto.WIP
    }

    return OpsSummaryDto(
        generatedAtMs = System.currentTimeMillis(),
        repos = listOf(
            RepoHealthDto(
                id = "backend",
                name = "backend",
                role = "Ktor control plane",
                status = OpsStatusDto.OK,
                runtimeLabel = backendRuntimeLabels.joinedRuntimeLabel(),
                runtimeLabels = backendRuntimeLabels,
                latestRun = backendLatestRun,
                runs = backendRuns(backendLatestRun),
                history = backendHistory,
                issues = backendIssues,
                signals = emptyList(),
            ),
            RepoHealthDto(
                id = "server_py",
                name = "server_py",
                role = "ChatGPT/browser automation bridge",
                status = serverPyStatus,
                runtimeLabel = serverPyRuntimeLabels.joinedRuntimeLabel(),
                runtimeLabels = serverPyRuntimeLabels,
                latestRun = serverPyLatestRun,
                runs = serverPyRuns(serverPySelfTest, serverPyLatestRun),
                selfTest = serverPySelfTest,
                issues = serverPyIssues,
                signals = snapshots.map { it.serverPySignal() },
            ),
            RepoHealthDto(
                id = "arcana",
                name = "arcana",
                role = "Local codebase comprehension and session engine",
                status = arcanaStatus,
                runtimeLabel = arcanaRuntimeLabels.joinedRuntimeLabel(),
                runtimeLabels = arcanaRuntimeLabels,
                latestRun = arcanaLatestRun,
                runs = arcanaRuns(arcanaLatestRun, arcanaIngest),
                issues = arcanaIssues,
                signals = arcanaSignals,
                note = arcanaIngest?.detail ?: "RSI/session ingestion is intentionally deferred.",
            ),
        ),
    )
}

private fun hostSnapshot(
    localPreview: Boolean,
    serverPySelfTest: SelfTestSummaryDto? = latestServerPySelfTest()?.toOpsSelfTestSummary(),
): OpsHostSnapshotDto {
    val backendRuntimeLabel = if (localPreview) localRuntimeLabel else qRuntimeLabel
    val serverPyRuntimeLabel = if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) qRuntimeLabel else localRuntimeLabel
    val serverPyReady = if (serverPyRuntimeLabel == localRuntimeLabel) {
        tcpReady("127.0.0.1", 1453)
    } else {
        serverPySocket.exists() && runCatching {
            SocketChannel.open(UnixDomainSocketAddress.of(serverPySocket.toPath())).use { true }
        }.getOrDefault(false)
    }
    return OpsHostSnapshotDto(
        generatedAtMs = System.currentTimeMillis(),
        host = backendRuntimeLabel,
        backendRuntimeLabel = backendRuntimeLabel,
        serverPyRuntimeLabel = serverPyRuntimeLabel,
        serverPyReady = serverPyReady,
        serverPyTransport = if (serverPyRuntimeLabel == localRuntimeLabel) "TCP 1453" else "UDS",
        serverPySelfTest = serverPySelfTest,
        arcanaSignals = arcanaSignals(backendRuntimeLabel),
    )
}

private fun peerHostSnapshot(localPreview: Boolean): OpsHostSnapshotDto? {
    val url = if (localPreview) qHostSnapshotUrl else macHostSnapshotUrl
    val now = System.currentTimeMillis()
    val previous = synchronized(peerSnapshotLock) { peerSnapshotCache?.takeIf { it.url == url } }
    if (previous != null && previous.nextAttemptAtMs > now) {
        return previous.snapshot?.takeIf { now - it.generatedAtMs <= peerSnapshotStaleMs }
    }
    val fetched = fetchPeerHostSnapshot(url)
    val snapshot = fetched.getOrNull()
        ?: (if (!localPreview) sshMacProcessSnapshot() else null)
        ?: previous?.snapshot?.takeIf { now - it.generatedAtMs <= peerSnapshotStaleMs }
    val missed = fetched.isFailure && snapshot?.host != "ssh:$localRuntimeLabel"
    synchronized(peerSnapshotLock) {
        peerSnapshotCache = CachedPeerSnapshot(
            url = url,
            snapshot = snapshot,
            nextAttemptAtMs = now + if (missed) peerSnapshotBackoffMs(previous?.misses.orZero()) else peerSnapshotRefreshMs,
            misses = if (missed) previous?.misses.orZero() + 1 else 0,
        )
        return snapshot
    }
}

private fun fetchPeerHostSnapshot(url: String) = runCatching {
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMillis(900))
        .header("Accept", "application/json")
        .header("User-Agent", "sdfgsdfg-backend-ops-peer")
        .build()
    val response = opsPeerHttp.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) error("Ops peer snapshot HTTP ${response.statusCode()}")
    opsJson.decodeFromString<OpsHostSnapshotDto>(response.body())
}

private fun sshMacProcessSnapshot(): OpsHostSnapshotDto? = runCatching {
    val processes = sshMacProcesses()
    OpsHostSnapshotDto(
        generatedAtMs = System.currentTimeMillis(),
        host = "ssh:$localRuntimeLabel",
        backendRuntimeLabel = localRuntimeLabel,
        serverPyRuntimeLabel = localRuntimeLabel,
        serverPyReady = false,
        serverPyTransport = "SSH",
        arcanaSignals = arcanaSignals(localRuntimeLabel, processes),
    )
}.getOrNull()

private fun OpsHostSnapshotDto.serverPySignal() = OpsSignalDto(
    label = "transport",
    status = if (serverPyReady) OpsStatusDto.OK else OpsStatusDto.UNKNOWN,
    detail = serverPyTransport,
    meta = serverPyRuntimeLabel,
)

private fun mergedArcanaSignals(hostSnapshots: List<OpsHostSnapshotDto>): List<OpsSignalDto> {
    if (hostSnapshots.size == 1) return hostSnapshots.first().arcanaSignals
    val summaries = hostSnapshots.mapNotNull { snapshot ->
        snapshot.arcanaSignals.firstOrNull { it.isActiveProcessSignal() }
    }
    val rows = hostSnapshots.flatMap { snapshot ->
        snapshot.arcanaSignals.filterNot { it.isActiveProcessSignal() }
    }
    return listOf(
        OpsSignalDto(
            label = activeProcessesLabel,
            status = if (summaries.any { it.status == OpsStatusDto.OK }) OpsStatusDto.OK else OpsStatusDto.UNKNOWN,
            detail = summaries.joinToString(" / ") { "${it.meta}: ${it.detail}" }.compact(180),
            meta = hostSnapshots.map { it.backendRuntimeLabel }.runtimeLabels().joinToString(" · "),
        ),
    ) + rows.sortedByDescending { it.timestampMs ?: 0L }.take(10)
}

private fun OpsSignalDto.isActiveProcessSignal() = label == activeProcessesLabel || label.startsWith("visible ")

private fun <T> List<T>.runtimeLabels(label: (T) -> String): List<String> = map(label).runtimeLabels()

private fun List<String>.runtimeLabels(): List<String> = distinct().filter { it.isNotBlank() }

private fun List<String>.joinedRuntimeLabel(): String? = takeIf { it.isNotEmpty() }?.joinToString(" + ")

private fun Int?.orZero() = this ?: 0

private fun peerSnapshotBackoffMs(misses: Int) = when {
    misses >= 3 -> 5 * 60_000L
    misses >= 1 -> 2 * 60_000L
    else -> peerSnapshotFailBackoffMs
}

private fun latestServerPySelfTest(file: File = serverPySelfTestFile): SelfTestResultDto? = runCatching {
    file.takeIf { it.exists() }
        ?.readText()
        ?.let { opsJson.decodeFromString<SelfTestResultDto>(it) }
}.getOrNull()

private fun latestArcanaIngest(file: File = defaultArcanaIngestFile): ArcanaIngestDto? = runCatching {
    file.takeIf { it.isFile }
        ?.readText()
        ?.let { opsJson.decodeFromString<ArcanaIngestDto>(it) }
}.getOrNull()

private fun arcanaSignals(runtimeLabel: String, processes: List<ProcessSnapshot> = processSnapshots()): List<OpsSignalDto> = buildList {
    val arcanaGroups = processes.filter { it.command.containsArcanaProcess() }.arcanaProcessGroups()
    val codexProcesses = processes.filter { it.command.containsCodexProcess() }
    val codexSessions = codexSessionSnapshots()
    val codexGroups = codexProcesses.groupBy { it.startedAtMs?.div(1_000) ?: it.pid }
    add(
        OpsSignalDto(
            label = activeProcessesLabel,
            status = if (arcanaGroups.size + codexGroups.size > 0) OpsStatusDto.OK else OpsStatusDto.UNKNOWN,
            detail = "${arcanaGroups.size} arcana live · ${codexGroups.size} codex live",
            meta = runtimeLabel,
        ),
    )
    (
        arcanaGroups.map { it.toSignal(runtimeLabel) } +
            codexGroups.values.map { group ->
                val head = group.minBy { it.startedAtMs ?: Long.MAX_VALUE }
                head.toSignal("codex", runtimeLabel, group.map { it.pid }, codexSessions.nearest(head.startedAtMs)?.detail)
            }
        )
        .sortedByDescending { it.timestampMs ?: 0L }
        .take(6)
        .forEach(::add)
}

internal data class ProcessSnapshot(val pid: Long, val parentPid: Long?, val command: String, val startedAtMs: Long?)

internal data class ProcessGroup(val processes: List<ProcessSnapshot>) {
    val pids = processes.map { it.pid }.sorted()
    val startedAtMs = processes.mapNotNull { it.startedAtMs }.minOrNull()
    val head = processes.minWith(
        compareBy<ProcessSnapshot> { it.command.contains("docker ", ignoreCase = true) }
            .thenBy { it.startedAtMs ?: Long.MAX_VALUE }
            .thenBy { it.pid },
    )
}

private data class CodexSessionSnapshot(val startedAtMs: Long, val detail: String)

private fun processSnapshots(): List<ProcessSnapshot> = ProcessHandle.allProcesses()
    .toList()
    .mapNotNull { process ->
        process.info().commandLine().orElse(null)?.let { command ->
            ProcessSnapshot(
                pid = process.pid(),
                parentPid = process.parent().map { it.pid() }.orElse(null),
                command = command,
                startedAtMs = process.info().startInstant().orElse(null)?.toEpochMilli(),
            )
        }
    }

private fun sshMacProcesses(): List<ProcessSnapshot> {
    val command = listOf(
        "ssh",
        "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=2",
        "-o", "ControlMaster=auto",
        "-o", "ControlPersist=3m",
        "-o", "ControlPath=/tmp/sdfgsdfg-ops-mac-ssh-%r@%h:%p",
        macSshTarget,
        "ps -axo pid=,ppid=,lstart=,command= | grep -E 'arcana|codex|_0.py' || true",
    )
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("Mac SSH process probe timed out")
    }
    if (process.exitValue() != 0) error("Mac SSH process probe failed")
    return process.inputStream.bufferedReader().lineSequence().mapNotNull { it.toProcessSnapshot() }.toList()
}

private fun String.toProcessSnapshot(): ProcessSnapshot? {
    val parts = trimStart().split(Regex("\\s+"), limit = 9)
    if (parts.size < 9) return null
    val command = parts[8].trim().takeIf { it.isNotBlank() } ?: return null
    return ProcessSnapshot(
        pid = parts[0].toLongOrNull() ?: return null,
        parentPid = parts[1].toLongOrNull(),
        command = command,
        startedAtMs = runCatching {
            LocalDateTime.parse(parts.subList(2, 7).joinToString(" "), psStartFormatter)
                .atZone(opsZoneId)
                .toInstant()
                .toEpochMilli()
        }.getOrNull(),
    )
}

internal fun List<ProcessSnapshot>.arcanaProcessGroups(): List<ProcessGroup> {
    val byPid = associateBy { it.pid }
    fun root(process: ProcessSnapshot): ProcessSnapshot {
        var current = process
        val seen = mutableSetOf<Long>()
        while (seen.add(current.pid)) {
            current = byPid[current.parentPid] ?: return current
        }
        return current
    }
    return groupBy { root(it).pid }
        .values
        .map { ProcessGroup(it) }
}

private fun ProcessSnapshot.toSignal(kind: String, runtimeLabel: String, pids: List<Long> = listOf(pid), detailOverride: String? = null) = OpsSignalDto(
    label = kind,
    status = OpsStatusDto.OK,
    timestampMs = startedAtMs,
    detail = detailOverride ?: if (kind == "codex") "Codex CLI process" else command.commandPreview(),
    meta = "$runtimeLabel · process ${pids.sorted().joinToString(" ") { "#$it" }}",
)

private fun ProcessGroup.toSignal(runtimeLabel: String) = OpsSignalDto(
    label = "arcana",
    status = OpsStatusDto.OK,
    timestampMs = startedAtMs,
    detail = head.command.normalizedCommand(),
    meta = "$runtimeLabel · process ${pids.joinToString(" ") { "#$it" }}",
)

private fun codexSessionSnapshots(): List<CodexSessionSnapshot> = runCatching {
    val dir = homeDir.resolve(".codex/sessions").resolve(sessionDatePathFormatter.format(Instant.now()))
    dir.listFiles { file -> file.isFile && file.name.startsWith("rollout-") && file.extension == "jsonl" }.orEmpty()
        .mapNotNull { file ->
            var startedAtMs: Long? = null
            var detail: String? = null
            file.useLines { lines ->
                lines.take(180).forEach { line ->
                    val root = runCatching { opsJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                    val payload = root.obj("payload")
                    if (root.text("type") == "session_meta") startedAtMs = payload?.instantMs("timestamp")
                    if (detail == null) detail = payload?.obj("goal")?.text("objective")
                }
            }
            startedAtMs?.let {
                CodexSessionSnapshot(
                    it,
                    detail?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotBlank) ?: "Codex session",
                )
            }
        }
}.getOrDefault(emptyList())

private fun List<CodexSessionSnapshot>.nearest(startedAtMs: Long?): CodexSessionSnapshot? = startedAtMs
    ?.let { start -> minByOrNull { abs(it.startedAtMs - start) }?.takeIf { abs(it.startedAtMs - start) < 4 * 60_000 } }

private fun String.containsArcanaProcess() = lowercase().let { !it.isProbeCommand() && ("desktop/py/arcana" in it || "/arcana/" in it || it.endsWith("/arcana")) }

private fun String.containsCodexProcess() = lowercase().let { !it.isProbeCommand() && "computeruse" !in it && "codex_snapshot" !in it && (" codex" in it || "/codex" in it) }

private fun String.isProbeCommand() = "grep" in this || "/rg " in this || endsWith("/rg") || " rg " in this

private fun String.commandPreview(): String = normalizedCommand().compact(150)

private fun String.normalizedCommand(): String = replace(Regex("/opt/homebrew/lib/node_modules/@openai/codex/\\S*/bin/codex"), "codex")
    .replace("/opt/homebrew/bin/node /opt/homebrew/bin/codex", "node codex")
    .replace("/opt/homebrew/bin/codex", "codex")
    .replace(Regex("(/Users|/home)/x/"), "~/")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun tcpReady(host: String, port: Int): Boolean = runCatching {
    Socket().use { it.connect(InetSocketAddress(host, port), 150) }
}.isSuccess

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
    TestRunSummaryDto("server checks", OpsStatusDto.OK, detail = "Gradle server checks passed.", url = backendFullSuiteUrl),
    TestRunSummaryDto("public ingress", OpsStatusDto.WIP, detail = "External probe stays outside restart gating.", url = publicIngressUrl),
)

private fun serverPyRuns(selfTest: SelfTestSummaryDto?, latestRun: TestRunSummaryDto) = buildList {
    add(latestRun.copy(url = selfTest?.workflowUrl ?: serverPyLiveSelftestUrl))
    if (selfTest == null) return@buildList
    if (selfTest.caseCount > 0) {
        add(TestRunSummaryDto(
            label = "model matrix",
            status = if (selfTest.casePassCount == selfTest.caseCount) OpsStatusDto.OK else OpsStatusDto.FAIL,
            detail = "${selfTest.casePassCount}/${selfTest.caseCount} model cases passing.",
        ))
    }
    add(TestRunSummaryDto("selftest artifact", OpsStatusDto.OK, detail = "Dashboard renders the latest selftest JSON and workflow link.", url = serverPySelfTestArtifactUrl))
}

private fun arcanaRuns(latestRun: TestRunSummaryDto?, ingest: ArcanaIngestDto?) = buildList {
    latestRun?.let(::add)
    addAll(ingest?.runs.orEmpty())
    if (ingest == null) {
        add(TestRunSummaryDto("pytest unit spine", OpsStatusDto.WIP, detail = "arcana-smoke has not published a q pytest summary yet."))
    }
    addAll(listOf(
        TestRunSummaryDto("issue/session schema", OpsStatusDto.WIP, detail = "Waits for Arcana-owned .arcana/issues.json and session output contracts."),
        TestRunSummaryDto("RSI sessions", OpsStatusDto.WIP, detail = "Deferred until issue and CI surfaces can receive output."),
    ))
}

internal fun localArcanaIssues(repoRoot: File): IssueSummaryDto = runCatching {
    val file = repoRoot.resolve(".arcana/issues.json")
    if (file.isFile) {
        issueObjects(opsJson.parseToJsonElement(file.readText()))
            .mapNotNull { issue -> issue.statusText() }
            .fold(IssueSummaryDto()) { summary, status -> summary.with(status) }
    } else {
        IssueSummaryDto()
    }
}.getOrDefault(IssueSummaryDto()).withSource("arcana", "Arcana issues")

private fun repoIssues(repoRoot: File, githubRepo: String, githubIssues: (String) -> IssueSummaryDto) =
    localArcanaIssues(repoRoot) + githubIssues(githubRepo)

private fun githubIssues(repo: String): IssueSummaryDto {
    val now = System.currentTimeMillis()
    synchronized(githubIssueCache) {
        githubIssueCache[repo]?.takeIf { it.expiresAtMs > now }?.summary
    }?.let { return it }

    val summary = fetchGithubIssues(repo)
        .getOrDefault(IssueSummaryDto())
        .withSource("github", "GitHub Issues", "https://github.com/sdfgsdfgd/$repo/issues")
    synchronized(githubIssueCache) {
        githubIssueCache[repo] = CachedIssueSummary(now + githubIssueCacheMs, summary)
    }
    return summary
}

private fun fetchGithubIssues(repo: String) = runCatching {
    val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/sdfgsdfgd/$repo/issues?state=open&per_page=100"))
        .timeout(Duration.ofSeconds(2))
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "sdfgsdfg-backend-ops")
        .build()
    val response = githubHttp.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) error("GitHub issues HTTP ${response.statusCode()}")
    githubIssueSummary(opsJson.parseToJsonElement(response.body()))
}

internal fun githubIssueSummary(element: JsonElement): IssueSummaryDto = (element as? JsonArray)
    ?.filterIsInstance<JsonObject>()
    ?.filter { "pull_request" !in it }
    ?.fold(IssueSummaryDto()) { summary, issue -> summary.with(githubIssueStatus(issue)) }
    ?: IssueSummaryDto()

private fun githubIssueStatus(issue: JsonObject): String {
    val labels = (issue["labels"] as? JsonArray)
        ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.lowercase() }
        .orEmpty()
    return when {
        labels.any { "block" in it } -> "blocked"
        labels.any { "review" in it } -> "review"
        labels.any { it == "wip" || "progress" in it || "doing" in it } -> "wip"
        else -> "todo"
    }
}

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

private fun IssueSummaryDto.hasAny(): Boolean = active + done > 0

private fun IssueSummaryDto.withSource(id: String, label: String, url: String? = null) = copy(
    sources = listOf(
        IssueSourceSummaryDto(
            id = id,
            label = label,
            url = url,
            todo = todo,
            wip = wip,
            blocked = blocked,
            review = review,
            done = done,
        ),
    ),
)

private operator fun IssueSummaryDto.plus(other: IssueSummaryDto) = IssueSummaryDto(
    todo = todo + other.todo,
    wip = wip + other.wip,
    blocked = blocked + other.blocked,
    review = review + other.review,
    done = done + other.done,
    sources = sources + other.sources,
)

private fun ArcanaIngestDto.toRunSummary() = TestRunSummaryDto(
    label = label,
    status = status,
    timestampMs = timestampMs,
    durationMs = durationMs,
    detail = detail,
    url = url,
)

private fun SelfTestSummaryDto.toRunSummary() = TestRunSummaryDto(
    label = "live selftest",
    status = status,
    timestampMs = timestampMs?.takeIf { it > 0 },
    durationMs = latencyMs,
    detail = rawError ?: textExcerpt.take(120).ifBlank { null },
    url = workflowUrl ?: serverPyLiveSelftestUrl,
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
        artifacts = listOf(OpsArtifactDto(name = "server-py-selftest.json", url = serverPySelfTestArtifactUrl)),
        cases = caseSummaries,
    )
}

private fun JsonObject.text(name: String): String? = this[name]
    ?.jsonPrimitive
    ?.contentOrNull
    ?.takeIf { it.isNotBlank() }

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.instantMs(name: String): Long? = text(name)?.let { value ->
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

private fun String.compact(max: Int): String = replace(Regex("\\s+"), " ")
    .trim()
    .let { if (it.length <= max) it else it.take(max - 1).trimEnd() + "…" }
