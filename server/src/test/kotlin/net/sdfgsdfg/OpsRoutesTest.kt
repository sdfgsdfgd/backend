package net.sdfgsdfg

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.host
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.sdfgsdfg.data.model.IssueSourceSummaryDto
import net.sdfgsdfg.data.model.IssueSummaryDto
import net.sdfgsdfg.data.model.OpsHostSnapshotDto
import net.sdfgsdfg.data.model.OpsSignalDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.SelfTestCaseDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import net.sdfgsdfg.data.model.SelfTestSummaryDto
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.io.path.createTempDirectory

// Purpose: protect ops cockpit contracts that are easy to break during routing
// and dashboard plumbing: host-scoped APIs, Compose/Wasm asset serving, local
// .arcana issue summaries, and server_py selftest parity. Keep these only while
// those contracts feed the dashboard; replace them when ownership moves.
class OpsRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val noGithubIssues: (String) -> IssueSummaryDto = { repo ->
        IssueSummaryDto(sources = listOf(IssueSourceSummaryDto("github", "GitHub Issues", "https://github.com/sdfgsdfgd/$repo/issues")))
    }

    @Test
    fun opsSummaryExposesGrandTrio() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { opsRoutes(githubIssues = noGithubIssues) }
        }

        val response = client.get("/api/ops/summary") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.OK, response.status)

        val summary = json.decodeFromString<OpsSummaryDto>(response.body<String>())
        val backend = summary.repos.first { it.id == "backend" }
        val serverPy = summary.repos.first { it.id == "server_py" }
        val backendRuns = summary.repos.first { it.id == "backend" }.runs
        assertEquals(listOf("backend", "server_py", "arcana"), summary.repos.map { it.id })
        assertEquals("remote q", backend.runtimeLabel)
        assertEquals(if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) "remote q" else "local", serverPy.runtimeLabel)
        assertEquals(true, backendRuns.any { it.label == "server checks" })
        assertEquals(true, backendRuns.any { it.label == "public ingress" })
        assertEquals(
            "https://github.com/sdfgsdfgd/backend/actions/workflows/full-suite.yml",
            backendRuns.first { it.label == "server checks" }.url,
        )
        assertEquals(
            "https://sdfgsdfg.net/test",
            backendRuns.first { it.label == "public ingress" }.url,
        )
    }

    @Test
    fun opsDashboardHostServesBuiltArtifact() = testApplication {
        val dist = createTempDirectory().toFile()
        File(dist, "index.html").writeText("<main>Trio Ops Cockpit</main>")

        application {
            routing {
                route("/{...}") {
                    handle {
                        call.respondOpsDashboard(dist)
                    }
                }
            }
        }

        val response = client.get("/") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("<main>Trio Ops Cockpit</main>", response.body<String>())
    }

    @Test
    fun catchAllKeepsPreciseOpsApiAheadOfDashboardFallback() = testApplication {
        val dist = createTempDirectory().toFile()
        File(dist, "index.html").writeText("<main>dashboard</main>")

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                opsRoutes(githubIssues = noGithubIssues)
                host("ops.sdfgsdfg.net") {
                    route("/{...}") {
                        handle {
                            call.respondOpsDashboard(dist)
                        }
                    }
                }
            }
        }

        val response = client.get("/api/ops/summary") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("backend", "server_py", "arcana"), json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos.map { it.id })
    }

    @Test
    fun opsApiIsScopedToOpsHost() = testApplication {
        val dist = createTempDirectory().toFile()
        File(dist, "index.html").writeText("<main>dashboard</main>")

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                opsRoutes(githubIssues = noGithubIssues)
                host("ops.sdfgsdfg.net") {
                    route("/{...}") {
                        handle {
                            call.respondOpsDashboard(dist)
                        }
                    }
                }
                route("/{...}") {
                    handle {
                        call.respondText("proxy")
                    }
                }
            }
        }

        val opsResponse = client.get("/api/ops/summary") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val publicResponse = client.get("/api/ops/summary") { header(HttpHeaders.Host, "sdfgsdfg.net") }

        assertEquals(HttpStatusCode.OK, opsResponse.status)
        assertEquals(HttpStatusCode.NotFound, publicResponse.status)
    }

    @Test
    fun opsSelftestArtifactIsScopedAndDownloadable() = testApplication {
        val artifact = File(createTempDirectory().toFile(), "server-py-selftest.json")
        artifact.writeText("""{"ok":true,"text_excerpt":"conversation ok"}""")

        application {
            routing {
                opsRoutes(selfTestArtifactFile = artifact, githubIssues = noGithubIssues)
            }
        }

        val opsResponse = client.get("/api/ops/artifacts/server-py-selftest.json") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val publicResponse = client.get("/api/ops/artifacts/server-py-selftest.json") { header(HttpHeaders.Host, "sdfgsdfg.net") }

        assertEquals(HttpStatusCode.OK, opsResponse.status)
        assertEquals("no-store", opsResponse.headers[HttpHeaders.CacheControl])
        assertEquals("""{"ok":true,"text_excerpt":"conversation ok"}""", opsResponse.body<String>())
        assertEquals(HttpStatusCode.NotFound, publicResponse.status)
    }

    @Test
    fun opsSummaryUsesTheScopedServerPySelftestArtifact() = testApplication {
        val artifact = File(createTempDirectory().toFile(), "server-py-selftest.json")
        artifact.writeText("""{"ok":true,"text_excerpt":"conversation ok","latency_ms":88.0,"timestamp_ms":42}""")

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { opsRoutes(localPreview = true, selfTestArtifactFile = artifact, githubIssues = noGithubIssues) }
        }

        val response = client.get("/api/ops/summary") { header(HttpHeaders.Host, "127.0.0.1") }
        val serverPy = json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos.first { it.id == "server_py" }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(serverPy.signals.single().status, serverPy.status)
        assertEquals("live selftest", serverPy.latestRun?.label)
        assertEquals(OpsStatusDto.OK, serverPy.latestRun?.status)
        assertEquals("conversation ok", serverPy.latestRun?.detail)
        assertEquals(88.0, serverPy.latestRun?.durationMs)
        assertEquals("transport", serverPy.signals.single().label)
        assertEquals(listOf("live selftest", "selftest artifact"), serverPy.runs.map { it.label })
    }

    @Test
    fun arcanaIngestArtifactIsScopedAndDownloadable() = testApplication {
        val artifact = File(createTempDirectory().toFile(), "arcana-ingest.json")
        artifact.writeText("""{"status":"OK","label":"q arcana unit pytest"}""")

        application {
            routing {
                opsRoutes(arcanaIngestTargetFile = artifact, githubIssues = noGithubIssues)
            }
        }

        val opsResponse = client.get("/api/ops/artifacts/arcana-ingest.json") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val publicResponse = client.get("/api/ops/artifacts/arcana-ingest.json") { header(HttpHeaders.Host, "sdfgsdfg.net") }

        assertEquals(HttpStatusCode.OK, opsResponse.status)
        assertEquals("no-store", opsResponse.headers[HttpHeaders.CacheControl])
        assertEquals("""{"status":"OK","label":"q arcana unit pytest"}""", opsResponse.body<String>())
        assertEquals(HttpStatusCode.NotFound, publicResponse.status)
    }

    @Test
    fun opsApiIsAvailableOnLoopbackForLocalDashboardPreview() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                opsRoutes(localPreview = true, githubIssues = noGithubIssues)
                route("/{...}") {
                    handle {
                        call.respondText("proxy")
                    }
                }
            }
        }

        val response = client.get("/api/ops/summary") { header(HttpHeaders.Host, "127.0.0.1") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("backend", "server_py", "arcana"), json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos.map { it.id })
    }

    @Test
    fun opsSummaryMergesPeerHostSnapshotWhenEnabled() = testApplication {
        val missingSelfTestFile = File(createTempDirectory().toFile(), "server-py-selftest.json")
        val peer = OpsHostSnapshotDto(
            generatedAtMs = 123L,
            host = "remote q",
            backendRuntimeLabel = "remote q",
            serverPyRuntimeLabel = "remote q",
            serverPyReady = true,
            serverPyTransport = "UDS",
            serverPySelfTest = SelfTestSummaryDto(
                status = OpsStatusDto.OK,
                ok = true,
                satisfiedExpectation = true,
                timestampMs = 99L,
                latencyMs = 12.0,
                textExcerpt = "peer selftest",
                caseCount = 2,
                casePassCount = 2,
            ),
            arcanaSignals = listOf(
                OpsSignalDto("active", OpsStatusDto.OK, detail = "0 arcana live · 1 codex live", meta = "remote q"),
                OpsSignalDto("codex", OpsStatusDto.OK, timestampMs = 99L, detail = "peer session", meta = "remote q · process #7"),
            ),
        )

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                opsRoutes(
                    localPreview = true,
                    selfTestArtifactFile = missingSelfTestFile,
                    githubIssues = noGithubIssues,
                    enablePeerSnapshots = true,
                    peerSnapshot = { peer },
                )
            }
        }

        val response = client.get("/api/ops/summary") { header(HttpHeaders.Host, "127.0.0.1") }
        val repos = json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos
        val backend = repos.first { it.id == "backend" }
        val serverPy = repos.first { it.id == "server_py" }
        val arcana = repos.first { it.id == "arcana" }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("local", "remote q"), backend.runtimeLabels)
        assertEquals("local + remote q", backend.runtimeLabel)
        assertEquals(OpsStatusDto.OK, serverPy.selfTest?.status)
        assertEquals("peer selftest", serverPy.latestRun?.detail)
        assertEquals(listOf("live selftest", "model matrix", "selftest artifact"), serverPy.runs.map { it.label })
        assertEquals(listOf("local", "remote q"), arcana.runtimeLabels)
        assertEquals(true, arcana.signals.first().detail?.contains("remote q: 0 arcana live") == true)
        assertEquals(true, arcana.signals.any { it.detail == "peer session" && it.meta == "remote q · process #7" })
    }

    @Test
    fun opsSummaryDoesNotInventMissingPeerRuntime() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                opsRoutes(
                    localPreview = false,
                    githubIssues = noGithubIssues,
                    enablePeerSnapshots = true,
                    peerSnapshot = { null },
                )
            }
        }

        val response = client.get("/api/ops/summary") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val backend = json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos.first { it.id == "backend" }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("remote q"), backend.runtimeLabels)
        assertEquals("remote q", backend.runtimeLabel)
    }

    @Test
    fun opsHostSnapshotExposesOnlyCurrentHostStatus() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { opsRoutes(localPreview = true, githubIssues = noGithubIssues) }
        }

        val response = client.get("/api/ops/host-snapshot") { header(HttpHeaders.Host, "127.0.0.1") }
        val snapshot = json.decodeFromString<OpsHostSnapshotDto>(response.body<String>())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("local", snapshot.host)
        assertEquals("local", snapshot.backendRuntimeLabel)
        assertEquals(true, snapshot.arcanaSignals.any { it.label == "active" })
    }

    @Test
    fun localPreviewShowsCurrentRuntimeInsteadOfStaleFailedDeployHistory() = testApplication {
        val historyFile = File(createTempDirectory().toFile(), "deploy-history.jsonl").apply {
            writeText("""{"label":"deploy failed","status":"FAIL","timestamp_ms":2,"duration_ms":22,"detail":"docker daemon was down"}""")
        }
        val missingSelfTestFile = File(createTempDirectory().toFile(), "server-py-selftest.json")
        val expectedServerPyRuntime = if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) "remote q" else "local"

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                opsRoutes(
                    localPreview = true,
                    deployHistorySourceFile = historyFile,
                    selfTestArtifactFile = missingSelfTestFile,
                    githubIssues = noGithubIssues,
                )
            }
        }

        val response = client.get("/api/ops/summary") { header(HttpHeaders.Host, "127.0.0.1") }
        val repos = json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos
        val backend = repos.first { it.id == "backend" }
        val serverPy = repos.first { it.id == "server_py" }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("local", backend.runtimeLabel)
        assertEquals(expectedServerPyRuntime, serverPy.runtimeLabel)
        assertEquals("local preview", backend.latestRun?.label)
        assertEquals(OpsStatusDto.OK, backend.latestRun?.status)
        assertEquals("deploy failed", backend.history.first().label)
        assertEquals(OpsStatusDto.FAIL, backend.history.first().status)
        assertEquals(listOf("gRPC bridge"), serverPy.runs.map { it.label })
    }

    @Test
    fun arcanaWithoutIngestDoesNotFabricateSessionOrPlaceholderRun() = testApplication {
        val ingestFile = File(createTempDirectory().toFile(), "missing-arcana-ingest.json")

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { opsRoutes(localPreview = true, arcanaIngestTargetFile = ingestFile, githubIssues = noGithubIssues) }
        }

        val response = client.get("/api/ops/summary") { header(HttpHeaders.Host, "127.0.0.1") }
        val arcana = json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos.first { it.id == "arcana" }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(null, arcana.latestRun)
        assertEquals(false, arcana.runs.any { it.label == "activity monitor" })
        assertEquals(true, arcana.signals.any { it.label == "active" })
        assertEquals(false, arcana.signals.any { it.label == "arcana current" || it.label == "current session" })
    }

    @Test
    fun arcanaProcessGroupsCollapseSandboxLauncherAndDockerChild() {
        val launcher = ProcessSnapshot(
            pid = 10,
            parentPid = null,
            command = "python _0.py --kaan --sandbox --path /Users/x/Desktop/py/arcana",
            startedAtMs = 100,
        )
        val sandbox = ProcessSnapshot(
            pid = 11,
            parentPid = 10,
            command = "docker run --rm /app/arcana/_0.py --path /Users/x/Desktop/py/arcana",
            startedAtMs = 110,
        )
        val independent = ProcessSnapshot(
            pid = 12,
            parentPid = null,
            command = "python _0.py --auto --path /Users/x/Desktop/py/arcana",
            startedAtMs = 120,
        )

        val groups = listOf(launcher, sandbox, independent)
            .arcanaProcessGroups()
            .sortedBy { it.pids.first() }

        assertEquals(listOf(listOf(10L, 11L), listOf(12L)), groups.map { it.pids })
        assertEquals(launcher, groups.first().head)
    }

    @Test
    fun arcanaProcessGroupsKeepIndependentRunsSeparate() {
        val first = ProcessSnapshot(20, null, "python _0.py --path /Users/x/Desktop/py/arcana", 200)
        val second = ProcessSnapshot(21, null, "python _0.py --no-index-sync --path /Users/x/Desktop/py/arcana", 210)

        val groups = listOf(first, second)
            .arcanaProcessGroups()
            .sortedBy { it.pids.first() }

        assertEquals(listOf(listOf(20L), listOf(21L)), groups.map { it.pids })
    }

    // Static asset tests are intentionally kept together: they protect the
    // public Compose/Wasm shell, not generic file-serving trivia.
    @Test
    fun opsDashboardFallbackDoesNotServeDashboardForApiPaths() = testApplication {
        val dist = createTempDirectory().toFile()
        File(dist, "index.html").writeText("<main>dashboard</main>")

        application {
            routing {
                route("/{...}") {
                    handle {
                        call.respondOpsDashboard(dist)
                    }
                }
            }
        }

        val response = client.get("/api/missing") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Not Found", response.body<String>())
    }

    @Test
    fun opsDashboardDoesNotFallbackStaticAssetMissesToIndex() = testApplication {
        val dist = createTempDirectory().toFile()
        File(dist, "index.html").writeText("<main>dashboard</main>")

        application {
            routing {
                route("/{...}") {
                    handle {
                        call.respondOpsDashboard(dist)
                    }
                }
            }
        }

        val response = client.get("/missing.wasm") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Not Found", response.body<String>())
    }

    @Test
    fun opsDashboardServesStaticAssetsWithExplicitContentAndCacheHeaders() = testApplication {
        val dist = createTempDirectory().toFile()
        File(dist, "index.html").writeText("<main>dashboard</main>")
        File(dist, "dashboard.wasm").writeBytes(byteArrayOf(0, 0x61, 0x73, 0x6d))
        File(dist, "dashboard-web.js").writeText("console.log('ok')")

        application {
            routing {
                route("/{...}") {
                    handle {
                        call.respondOpsDashboard(dist)
                    }
                }
            }
        }

        val response = client.get("/dashboard.wasm") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/wasm", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertEquals("public, max-age=31536000, immutable", response.headers[HttpHeaders.CacheControl])

        val scriptResponse = client.get("/dashboard-web.js") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.OK, scriptResponse.status)
        assertEquals("no-cache", scriptResponse.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun opsDashboardIndexesVersionTheUnhashedJsShell() = testApplication {
        val dist = createTempDirectory().toFile()
        File(dist, "index.html").writeText("""<link rel="stylesheet" href="styles.css"><script src="dashboard-web.js"></script>""")
        File(dist, "styles.css").writeText("body { color: white; }")
        File(dist, "dashboard-web.js").writeText("console.log('versioned')")

        application {
            routing {
                route("/{...}") {
                    handle {
                        call.respondOpsDashboard(dist)
                    }
                }
            }
        }

        val response = client.get("/") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val body = response.body<String>()
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, Regex("""styles\.css\?v=[a-f0-9]{16}""").containsMatchIn(body))
        assertEquals(true, Regex("""dashboard-web\.js\?v=[a-f0-9]{16}""").containsMatchIn(body))
    }

    @Test
    fun opsDashboardHostIsExplicitWhenArtifactIsMissing() = testApplication {
        val missingDist = File(createTempDirectory().toFile(), "missing")

        application {
            routing {
                route("/{...}") {
                    handle {
                        call.respondOpsDashboard(missingDist)
                    }
                }
            }
        }

        val response = client.get("/") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(true, response.body<String>().contains(":dashboard:web:wasmJsBrowserDistribution"))
    }

    @Test
    fun localArcanaIssuesSummarizesRepoIssueFile() {
        val repo = createTempDirectory().toFile()
        val arcanaDir = File(repo, ".arcana").also { it.mkdirs() }
        File(arcanaDir, "issues.json").writeText(
            """
            {
              "version": 1,
              "issues": [
                { "id": "backend-1", "status": "todo" },
                { "id": "backend-2", "status": "WIP" },
                { "id": "backend-3", "state": "blocked" },
                { "id": "backend-4", "status": "review" },
                { "id": "backend-5", "status": "done" }
              ]
            }
            """.trimIndent(),
        )

        val issues = localArcanaIssues(repo)
        assertEquals(1, issues.todo)
        assertEquals(1, issues.wip)
        assertEquals(1, issues.blocked)
        assertEquals(1, issues.review)
        assertEquals(1, issues.done)
        assertEquals(4, issues.active)
        assertEquals("arcana", issues.sources.single().id)
        assertEquals(4, issues.sources.single().active)
    }

    @Test
    fun githubIssueSummarySkipsPullRequestsAndMapsLabels() {
        val issues = githubIssueSummary(json.parseToJsonElement(
            """
            [
              { "number": 1, "labels": [] },
              { "number": 2, "labels": [{ "name": "blocked" }] },
              { "number": 3, "labels": [{ "name": "in progress" }] },
              { "number": 4, "labels": [{ "name": "review" }] },
              { "number": 5, "pull_request": {}, "labels": [{ "name": "blocked" }] }
            ]
            """.trimIndent(),
        ))

        assertEquals(1, issues.todo)
        assertEquals(1, issues.wip)
        assertEquals(1, issues.blocked)
        assertEquals(1, issues.review)
        assertEquals(4, issues.active)
    }

    @Test
    fun arcanaIngestIsLocalOnlyAndFeedsOpsSummary() = testApplication {
        val ingestFile = File(createTempDirectory().toFile(), "arcana-ingest.json")

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                opsRoutes(localPreview = true, arcanaIngestTargetFile = ingestFile, githubIssues = noGithubIssues)
            }
        }

        val publicWrite = client.post("/api/ops/ingest/arcana") {
            header(HttpHeaders.Host, "ops.sdfgsdfg.net")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"status":"OK"}""")
        }
        assertEquals(HttpStatusCode.NotFound, publicWrite.status)

        val localWrite = client.post("/api/ops/ingest/arcana") {
            header(HttpHeaders.Host, "127.0.0.1")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                """
                {
                  "status": "OK",
                  "label": "pytest local publisher",
                  "duration_ms": 123.0,
                  "detail": "unit spine passed",
                  "issues": { "todo": 2, "wip": 1, "done": 3 },
                  "runs": [
                    { "label": "pytest unit", "status": "OK", "detail": "83 tests" }
                  ]
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Accepted, localWrite.status)

        val summaryResponse = client.get("/api/ops/summary") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val arcana = json.decodeFromString<OpsSummaryDto>(summaryResponse.body<String>()).repos.first { it.id == "arcana" }
        assertEquals(OpsStatusDto.OK, arcana.status)
        assertEquals("pytest local publisher", arcana.latestRun?.label)
        assertEquals("unit spine passed", arcana.latestRun?.detail)
        assertEquals(2, arcana.issues.todo)
        assertEquals(1, arcana.issues.wip)
        assertEquals(3, arcana.issues.done)
        assertEquals(true, arcana.runs.any { it.label == "pytest unit" && it.status == OpsStatusDto.OK })
        assertEquals(false, arcana.runs.any { it.label == "pytest unit spine" })
        assertEquals(true, arcana.runs.any { it.label == "issue/session schema" && it.status == OpsStatusDto.WIP })
        assertEquals(true, ingestFile.readText().contains("timestamp_ms"))
    }

    @Test
    fun deployHistoryReadsNewestRunsWithStatus() {
        val file = File(createTempDirectory().toFile(), "deploy-history.jsonl")
        file.writeText(
            """
            not-json
            {"label":"deploy old","status":"OK","timestamp_ms":1,"duration_ms":11,"head":"old","detail":"old detail"}
            {"label":"deploy failed","status":"FAIL","timestamp_ms":2,"duration_ms":22,"head":"fail","detail":"gradle failed"}
            """.trimIndent(),
        )

        val history = deployHistory(file)
        assertEquals(listOf("deploy failed", "deploy old"), history.map { it.label })
        assertEquals(listOf(OpsStatusDto.FAIL, OpsStatusDto.OK), history.map { it.status })
        assertEquals(listOf("gradle failed", "old detail"), history.map { it.detail })
        assertEquals(2L, history.first().timestampMs)
        assertEquals(22.0, history.first().durationMs)
    }

    @Test
    fun selfTestSummaryPreservesLiveSelftestSignals() {
        val summary = SelfTestResultDto(
            ok = true,
            textExcerpt = "conversation ok",
            latencyMs = 100.0,
            askLatencyMs = 70.0,
            auditLatencyMs = 20.0,
            satisfiedExpectation = true,
            retried = true,
            cases = listOf(
                SelfTestCaseDto("5.5-thinking-heavy", ok = true, latencyMs = 50.0, note = "selected"),
                SelfTestCaseDto("o3", ok = false, latencyMs = 10.0, note = "missing"),
            ),
            zen = buildJsonObject {
                put("state", "push_failed")
                put("reason", "model selector drift")
                put("severity", "error")
                put("folder", "/tmp/zen-artifact")
            },
            workflowUrl = "https://github.com/x/backend/actions/runs/1",
            timestampMs = 42L,
        ).toOpsSelfTestSummary()

        assertEquals(OpsStatusDto.OK, summary.status)
        assertEquals(true, summary.satisfiedExpectation)
        assertEquals(42L, summary.timestampMs)
        assertEquals(true, assertNotNull(summary.timestampLabel).isNotBlank())
        assertEquals(100.0, summary.latencyMs)
        assertEquals(70.0, summary.askLatencyMs)
        assertEquals(20.0, summary.auditLatencyMs)
        assertEquals(true, summary.retried)
        assertEquals("https://github.com/x/backend/actions/runs/1", summary.workflowUrl)
        assertEquals(2, summary.caseCount)
        assertEquals(1, summary.casePassCount)
        assertEquals(true, summary.zenPresent)
        assertEquals("push_failed", summary.zenState)
        assertEquals("model selector drift", summary.zenReason)
        assertEquals("error", summary.zenSeverity)
        assertEquals("/tmp/zen-artifact", summary.zenArtifactPath)
        assertEquals("server-py-selftest.json", summary.artifacts.single().name)
        assertEquals(null, summary.artifacts.single().path)
        assertEquals("/api/ops/artifacts/server-py-selftest.json", summary.artifacts.single().url)
        assertEquals(listOf(OpsStatusDto.OK, OpsStatusDto.FAIL), summary.cases.map { it.status })
    }
}
