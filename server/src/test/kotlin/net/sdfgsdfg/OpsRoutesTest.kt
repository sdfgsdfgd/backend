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
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.host
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.sdfgsdfg.data.model.IssueSourceSummaryDto
import net.sdfgsdfg.data.model.IssueSummaryDto
import net.sdfgsdfg.data.model.OPS_CAPABILITY_ISSUES_WRITE
import net.sdfgsdfg.data.model.OPS_ISSUES_PATH
import net.sdfgsdfg.data.model.OPS_SUMMARY_PATH
import net.sdfgsdfg.data.model.OPS_VIEWER_PATH
import net.sdfgsdfg.data.model.OpsHostSnapshotDto
import net.sdfgsdfg.data.model.OpsSignalDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsViewerDto
import net.sdfgsdfg.data.model.SelfTestCaseDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import net.sdfgsdfg.data.model.SelfTestSummaryDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
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
    private val noBackendFullSuite = {
        TestRunSummaryDto("full suite", OpsStatusDto.OK, detail = "stubbed backend umbrella.", url = "https://github.com/sdfgsdfgd/backend/actions/workflows/full-suite.yml")
    }
    private val guestViewer = OpsViewerDto()
    private val adminViewer = OpsViewerDto(
        userId = "kaan",
        displayName = "kaan",
        role = "admin",
        proofs = listOf("test"),
        capabilities = listOf(OPS_CAPABILITY_ISSUES_WRITE),
        issueWrite = true,
    )
    private val remoteClient = ClientInfo(
        clientIp = "203.0.113.10",
        remoteIp = "203.0.113.10",
        cfIp = null,
        source = "test",
        trustedProxy = true,
        isLocal = false,
        allowed = true,
    )
    private fun Application.installOpsRouteTestPlugins() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets)
    }

    @Test
    fun opsSummaryExposesGrandTrio() = testApplication {
        application {
            installOpsRouteTestPlugins()
            routing { opsRoutes(githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite) }
        }

        val response = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.OK, response.status)

        val summary = json.decodeFromString<OpsSummaryDto>(response.body<String>())
        val backend = summary.repos.first { it.id == "backend" }
        val serverPy = summary.repos.first { it.id == "server_py" }
        val backendRuns = summary.repos.first { it.id == "backend" }.runs
        assertEquals(listOf("backend", "server_py", "arcana"), summary.repos.map { it.id })
        assertEquals("remote q", backend.runtimeLabel)
        assertEquals(if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) "remote q" else "local", serverPy.runtimeLabel)
        assertEquals(true, backendRuns.any { it.label == "unit tests" })
        assertEquals(true, backendRuns.any { it.label == "full suite" })
        assertEquals(
            "https://github.com/sdfgsdfgd/backend/actions/workflows/full-suite.yml",
            backendRuns.first { it.label == "full suite" }.url,
        )
        assertEquals(OpsStatusDto.OK, backendRuns.first { it.label == "full suite" }.status)
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
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite)
                host("ops.sdfgsdfg.net") {
                    route("/{...}") {
                        handle {
                            call.respondOpsDashboard(dist)
                        }
                    }
                }
            }
        }

        val response = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("backend", "server_py", "arcana"), json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos.map { it.id })
    }

    @Test
    fun opsApiIsScopedToOpsHost() = testApplication {
        val dist = createTempDirectory().toFile()
        File(dist, "index.html").writeText("<main>dashboard</main>")

        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite)
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

        val opsResponse = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val publicResponse = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "sdfgsdfg.net") }

        assertEquals(HttpStatusCode.OK, opsResponse.status)
        assertEquals(HttpStatusCode.NotFound, publicResponse.status)
    }

    @Test
    fun opsSelftestArtifactIsScopedAndDownloadable() = testApplication {
        val artifact = File(createTempDirectory().toFile(), "server-py-selftest.json")
        artifact.writeText("""{"ok":true,"text_excerpt":"conversation ok"}""")

        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(selfTestArtifactFile = artifact, githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite)
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
        val dir = createTempDirectory().toFile()
        val artifact = File(dir, "server-py-selftest.json")
        val history = File(dir, "server-py-selftest-history.jsonl")
        val unitArtifact = File(dir, "server-py-unit.json")
        val unitHistory = File(dir, "server-py-unit-history.jsonl")
        artifact.writeText("""{"ok":true,"text_excerpt":"conversation ok","latency_ms":88.0,"timestamp_ms":42}""")

        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(
                    localPreview = true,
                    selfTestArtifactFile = artifact,
                    selfTestHistoryFile = history,
                    serverPyUnitFile = unitArtifact,
                    serverPyUnitHistoryFile = unitHistory,
                    githubIssues = noGithubIssues,
                    backendFullSuite = noBackendFullSuite,
                )
            }
        }

        val response = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "127.0.0.1") }
        val serverPy = json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos.first { it.id == "server_py" }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(serverPy.signals.single().status, serverPy.status)
        assertEquals("live selftest", serverPy.latestRun?.label)
        assertEquals(OpsStatusDto.OK, serverPy.latestRun?.status)
        assertEquals("conversation ok", serverPy.latestRun?.detail)
        assertEquals(88.0, serverPy.latestRun?.durationMs)
        assertEquals("transport", serverPy.signals.single().label)
        assertEquals(listOf("live e2e selftest"), serverPy.runs.map { it.label })
        assertEquals(listOf("live selftest"), serverPy.history.map { it.label })
    }

    @Test
    fun opsSummaryUsesTheScopedServerPyUnitArtifact() = testApplication {
        val dir = createTempDirectory().toFile()
        val artifact = File(dir, "server-py-unit.json")
        val history = File(dir, "server-py-unit-history.jsonl")
        val selfTestArtifact = File(dir, "server-py-selftest.json")
        artifact.writeText("""{"label":"unit tests","status":"OK","detail":"3 passed in 0.10s","coverage_pct":91.5}""")

        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(
                    localPreview = true,
                    selfTestArtifactFile = selfTestArtifact,
                    serverPyUnitFile = artifact,
                    serverPyUnitHistoryFile = history,
                    githubIssues = noGithubIssues,
                    backendFullSuite = noBackendFullSuite,
                )
            }
        }

        val artifactResponse = client.get("/api/ops/artifacts/server-py-unit.json") { header(HttpHeaders.Host, "127.0.0.1") }
        val publicResponse = client.get("/api/ops/artifacts/server-py-unit.json") { header(HttpHeaders.Host, "sdfgsdfg.net") }
        val summaryResponse = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "127.0.0.1") }
        val serverPy = json.decodeFromString<OpsSummaryDto>(summaryResponse.body<String>()).repos.first { it.id == "server_py" }

        assertEquals(HttpStatusCode.OK, artifactResponse.status)
        assertEquals("no-store", artifactResponse.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.NotFound, publicResponse.status)
        assertEquals(listOf("unit tests"), serverPy.runs.map { it.label })
        assertEquals(91.5, serverPy.runs.single().coveragePct)
        assertEquals(listOf("unit tests"), serverPy.history.map { it.label })
    }

    @Test
    fun arcanaIngestArtifactIsScopedAndDownloadable() = testApplication {
        val artifact = File(createTempDirectory().toFile(), "arcana-ingest.json")
        artifact.writeText("""{"status":"OK","label":"q arcana full pyramid"}""")

        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(arcanaIngestTargetFile = artifact, githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite)
            }
        }

        val opsResponse = client.get("/api/ops/artifacts/arcana-ingest.json") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val publicResponse = client.get("/api/ops/artifacts/arcana-ingest.json") { header(HttpHeaders.Host, "sdfgsdfg.net") }

        assertEquals(HttpStatusCode.OK, opsResponse.status)
        assertEquals("no-store", opsResponse.headers[HttpHeaders.CacheControl])
        assertEquals("""{"status":"OK","label":"q arcana full pyramid"}""", opsResponse.body<String>())
        assertEquals(HttpStatusCode.NotFound, publicResponse.status)
    }

    @Test
    fun opsApiIsAvailableOnLoopbackForLocalDashboardPreview() = testApplication {
        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(localPreview = true, githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite)
                route("/{...}") {
                    handle {
                        call.respondText("proxy")
                    }
                }
            }
        }

        val response = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "127.0.0.1") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("backend", "server_py", "arcana"), json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos.map { it.id })
    }

    @Test
    fun opsViewerEndpointExposesResolvedViewer() = testApplication {
        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(
                    githubIssues = noGithubIssues,
                    backendFullSuite = noBackendFullSuite,
                    resolveViewer = { adminViewer },
                )
            }
        }

        val response = client.get(OPS_VIEWER_PATH) { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val viewer = json.decodeFromString<OpsViewerDto>(response.body<String>())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("kaan", viewer.userId)
        assertEquals(true, viewer.issueWrite)
        assertEquals(listOf(OPS_CAPABILITY_ISSUES_WRITE), viewer.capabilities)
    }

    @Test
    fun opsGithubSessionSignsAndExpires() {
        val session = OpsGithubSession("kaan", "Kaan", "https://avatars.githubusercontent.com/u/1?v=4")
        val value = signOpsGithubSession(session, "secret", nowMs = 1_000L, ttlMs = 1_000L)

        assertEquals(session, verifyOpsGithubSession(value, "secret", nowMs = 1_500L))
        assertEquals(null, verifyOpsGithubSession(value, "secret", nowMs = 2_500L))
        assertEquals(null, verifyOpsGithubSession(value, "wrong", nowMs = 1_500L))
    }

    @Test
    fun opsViewerUsesGithubIdentityWithoutGrantingIssueWrite() {
        val viewer = resolveOpsViewer(remoteClient, OpsGithubSession("octo", "Octo", "avatar.png"))

        assertEquals("octo", viewer.userId)
        assertEquals("Octo", viewer.displayName)
        assertEquals("guest", viewer.role)
        assertEquals(listOf("github:octo"), viewer.proofs)
        assertEquals(emptyList(), viewer.capabilities)
        assertEquals("avatar.png", viewer.avatarUrl)
        assertEquals(false, viewer.issueWrite)
    }

    @Test
    fun opsViewerCombinesGithubIdentityWithOwnerIssueWrite() {
        val ownerClient = remoteClient.copy(clientIp = "127.0.0.1", remoteIp = "127.0.0.1", isLocal = true)
        val viewer = resolveOpsViewer(ownerClient, OpsGithubSession("octo", "Octo"))

        assertEquals("octo", viewer.userId)
        assertEquals("Octo", viewer.displayName)
        assertEquals("admin", viewer.role)
        assertEquals(listOf("loopback", "github:octo"), viewer.proofs)
        assertEquals(listOf(OPS_CAPABILITY_ISSUES_WRITE), viewer.capabilities)
        assertEquals(true, viewer.issueWrite)
    }

    @Test
    fun opsViewerTreatsExpandedIpv6LoopbackAsOwner() {
        val ownerClient = remoteClient.copy(clientIp = "0:0:0:0:0:0:0:1", remoteIp = "0:0:0:0:0:0:0:1", isLocal = true)
        val viewer = resolveOpsViewer(ownerClient)

        assertEquals("kaan", viewer.userId)
        assertEquals("admin", viewer.role)
        assertEquals(listOf("loopback"), viewer.proofs)
        assertEquals(true, viewer.issueWrite)
    }

    @Test
    fun opsIssueMutationRequiresIssueWrite() = testApplication {
        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(
                    githubIssues = noGithubIssues,
                    backendFullSuite = noBackendFullSuite,
                    resolveViewer = { guestViewer },
                )
            }
        }

        val response = client.post(OPS_ISSUES_PATH) {
            header(HttpHeaders.Host, "ops.sdfgsdfg.net")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("not-json")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("Issue mutations require admin viewer", response.body<String>())
    }

    @Test
    fun opsIssueMutationAllowsAdminThroughJsonValidation() = testApplication {
        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(
                    githubIssues = noGithubIssues,
                    backendFullSuite = noBackendFullSuite,
                    resolveViewer = { adminViewer },
                )
            }
        }

        val response = client.post(OPS_ISSUES_PATH) {
            header(HttpHeaders.Host, "ops.sdfgsdfg.net")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("not-json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Invalid issue mutation JSON", response.body<String>())
    }

    @Test
    fun opsIssueMutationRejectsRemoteGithubIdentityWithoutOwnerProof() = testApplication {
        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(
                    githubIssues = noGithubIssues,
                    backendFullSuite = noBackendFullSuite,
                    resolveViewer = { resolveOpsViewer(remoteClient, OpsGithubSession("kaan", "Kaan")) },
                )
            }
        }

        val response = client.post(OPS_ISSUES_PATH) {
            header(HttpHeaders.Host, "ops.sdfgsdfg.net")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("not-json")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("Issue mutations require admin viewer", response.body<String>())
    }

    @Test
    fun opsSummaryMergesPeerHostSnapshotWhenEnabled() = testApplication {
        val dir = createTempDirectory().toFile()
        val missingSelfTestFile = File(dir, "server-py-selftest.json")
        val missingUnitFile = File(dir, "server-py-unit.json")
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
            serverPyUnitTest = TestRunSummaryDto(
                label = "unit tests",
                status = OpsStatusDto.OK,
                detail = "22 passed in 0.64s",
                url = "/api/ops/artifacts/server-py-unit.json",
                coveragePct = 33.0,
            ),
            arcanaSignals = listOf(
                OpsSignalDto("active", OpsStatusDto.OK, detail = "0 arcana live · 1 codex live", meta = "remote q"),
                OpsSignalDto("codex", OpsStatusDto.OK, timestampMs = 99L, detail = "peer session", meta = "remote q · process #7"),
            ),
        )

        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(
                    localPreview = true,
                    selfTestArtifactFile = missingSelfTestFile,
                    serverPyUnitFile = missingUnitFile,
                    githubIssues = noGithubIssues,
                    backendFullSuite = noBackendFullSuite,
                    enablePeerSnapshots = true,
                    peerSnapshot = { peer },
                )
            }
        }

        val response = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "127.0.0.1") }
        val repos = json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos
        val backend = repos.first { it.id == "backend" }
        val serverPy = repos.first { it.id == "server_py" }
        val arcana = repos.first { it.id == "arcana" }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("local", "remote q"), backend.runtimeLabels)
        assertEquals("local + remote q", backend.runtimeLabel)
        assertEquals(OpsStatusDto.OK, serverPy.selfTest?.status)
        assertEquals("peer selftest", serverPy.latestRun?.detail)
        assertEquals(listOf("unit tests", "live e2e selftest", "model matrix"), serverPy.runs.map { it.label })
        assertEquals(33.0, serverPy.runs.first().coveragePct)
        assertEquals("https://ops.sdfgsdfg.net/api/ops/artifacts/server-py-unit.json", serverPy.runs.first().url)
        assertEquals(listOf("local", "remote q"), arcana.runtimeLabels)
        assertEquals(true, arcana.signals.first().detail?.contains("remote q: 0 arcana live") == true)
        assertEquals(true, arcana.signals.any { it.detail == "peer session" && it.meta == "remote q · process #7" })
    }

    @Test
    fun opsSummaryDoesNotInventMissingPeerRuntime() = testApplication {
        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(
                    localPreview = false,
                    githubIssues = noGithubIssues,
                    backendFullSuite = noBackendFullSuite,
                    enablePeerSnapshots = true,
                    peerSnapshot = { null },
                )
            }
        }

        val response = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val backend = json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos.first { it.id == "backend" }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("remote q"), backend.runtimeLabels)
        assertEquals("remote q", backend.runtimeLabel)
    }

    @Test
    fun opsHostSnapshotExposesOnlyCurrentHostStatus() = testApplication {
        application {
            installOpsRouteTestPlugins()
            routing { opsRoutes(localPreview = true, githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite) }
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
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(
                    localPreview = true,
                    deployHistorySourceFile = historyFile,
                    selfTestArtifactFile = missingSelfTestFile,
                    githubIssues = noGithubIssues,
                    backendFullSuite = noBackendFullSuite,
                )
            }
        }

        val response = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "127.0.0.1") }
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
        assertEquals(emptyList(), serverPy.runs.map { it.label })
    }

    @Test
    fun arcanaWithoutIngestDoesNotFabricateSessionOrPlaceholderRun() = testApplication {
        val ingestFile = File(createTempDirectory().toFile(), "missing-arcana-ingest.json")

        application {
            installOpsRouteTestPlugins()
            routing { opsRoutes(localPreview = true, arcanaIngestTargetFile = ingestFile, githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite) }
        }

        val response = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "127.0.0.1") }
        val arcana = json.decodeFromString<OpsSummaryDto>(response.body<String>()).repos.first { it.id == "arcana" }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(null, arcana.latestRun)
        assertEquals(emptyList(), arcana.runs)
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
                { "key": "BCK-001", "title": "triage", "status": "todo", "created_at_ms": 10, "updated_at_ms": 11 },
                { "key": "BCK-002", "title": "active", "status": "WIP" },
                { "key": "BCK-003", "title": "stuck", "state": "blocked" },
                { "key": "BCK-004", "title": "check", "status": "review" },
                { "key": "BCK-005", "title": "closed", "status": "done", "completed_at_ms": 20 },
                { "key": "BCK-006", "title": "retained", "status": "archive" }
              ]
            }
            """.trimIndent(),
        )
        File(arcanaDir, "issues.events.jsonl").writeText("""{"event_id":"EVT-1","ts_ms":11,"event":"updated","key":"BCK-001","title":"triage","status":"todo"}""" + "\n")

        val issues = localArcanaIssues(repo)
        assertEquals(1, issues.todo)
        assertEquals(1, issues.wip)
        assertEquals(1, issues.blocked)
        assertEquals(1, issues.review)
        assertEquals(1, issues.done)
        assertEquals(1, issues.archive)
        assertEquals(4, issues.active)
        assertEquals("arcana", issues.sources.single().id)
        assertEquals(4, issues.sources.single().active)
        assertEquals(6, issues.items.size)
        assertEquals("BCK-002", issues.items.first { it.status == "wip" }.id)
        assertEquals(10L, issues.items.first().createdAtMs)
        assertEquals("updated", issues.events.single().event)
        assertEquals("BCK-001", issues.events.single().id)
    }

    @Test
    fun githubIssueSummarySkipsPullRequestsAndMapsLabels() {
        val issues = githubIssueSummary(json.parseToJsonElement(
            """
            [
              { "number": 1, "title": "plain", "html_url": "https://github.test/1", "labels": [] },
              { "number": 2, "title": "blocked", "labels": [{ "name": "blocked" }] },
              { "number": 3, "title": "progress", "labels": [{ "name": "in progress" }] },
              { "number": 4, "title": "review", "labels": [{ "name": "review" }] },
              { "number": 5, "pull_request": {}, "labels": [{ "name": "blocked" }] }
            ]
            """.trimIndent(),
        ))

        assertEquals(1, issues.todo)
        assertEquals(1, issues.wip)
        assertEquals(1, issues.blocked)
        assertEquals(1, issues.review)
        assertEquals(4, issues.active)
        assertEquals(4, issues.items.size)
        assertEquals("#1", issues.items.first().id)
        assertEquals("github", issues.items.first().source)
    }

    @Test
    fun arcanaIngestIsLocalOnlyAndFeedsOpsSummary() = testApplication {
        val dir = createTempDirectory().toFile()
        val ingestFile = File(dir, "arcana-ingest.json")
        val historyFile = File(dir, "arcana-ingest-history.jsonl")

        application {
            installOpsRouteTestPlugins()
            routing {
                opsRoutes(localPreview = true, arcanaIngestTargetFile = ingestFile, arcanaIngestHistoryFile = historyFile, githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite)
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
                  "label": "q arcana full pyramid",
                  "duration_ms": 123.0,
                  "detail": "370 passed on q @abc1234",
                  "issues": { "todo": 2, "wip": 1, "done": 3 },
                  "runs": [
                    { "label": "deterministic baseline", "status": "OK", "detail": "366 passed", "coverage_pct": 80.5 },
                    { "label": "live e2e canaries", "status": "OK", "detail": "3 passed" },
                    { "label": "benchmark seed", "status": "OK", "detail": "1 passed" }
                  ],
                  "coverage_pct": 80.5
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Accepted, localWrite.status)

        val summaryResponse = client.get(OPS_SUMMARY_PATH) { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val arcana = json.decodeFromString<OpsSummaryDto>(summaryResponse.body<String>()).repos.first { it.id == "arcana" }
        assertEquals(OpsStatusDto.OK, arcana.status)
        assertEquals("q arcana full pyramid", arcana.latestRun?.label)
        assertEquals("370 passed on q @abc1234", arcana.latestRun?.detail)
        assertEquals(2, arcana.issues.todo)
        assertEquals(1, arcana.issues.wip)
        assertEquals(3, arcana.issues.done)
        assertEquals(true, arcana.runs.any { it.label == "deterministic baseline" && it.status == OpsStatusDto.OK && it.coveragePct == 80.5 })
        assertEquals(true, arcana.runs.any { it.label == "live e2e canaries" && it.status == OpsStatusDto.OK })
        assertEquals(true, arcana.runs.any { it.label == "benchmark seed" && it.status == OpsStatusDto.OK })
        assertEquals(80.5, arcana.latestRun?.coveragePct)
        assertEquals(listOf("q arcana full pyramid"), arcana.history.map { it.label })
        assertEquals(false, arcana.runs.any { it.label == "pytest unit" })
        assertEquals(false, arcana.runs.any { it.status == OpsStatusDto.WIP })
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
