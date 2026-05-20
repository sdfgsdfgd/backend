package net.sdfgsdfg

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.SelfTestCaseDto
import net.sdfgsdfg.data.model.SelfTestResultDto
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

    @Test
    fun opsSummaryExposesGrandTrio() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { opsRoutes() }
        }

        val response = client.get("/api/ops/summary") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        assertEquals(HttpStatusCode.OK, response.status)

        val summary = json.decodeFromString<OpsSummaryDto>(response.body<String>())
        assertEquals(listOf("backend", "server_py", "arcana"), summary.repos.map { it.id })
        assertEquals(
            listOf("local smoke", "server checks", "public ingress"),
            summary.repos.first { it.id == "backend" }.runs.map { it.label },
        )
        assertEquals(
            "https://sdfgsdfg.net/test",
            summary.repos.first { it.id == "backend" }.runs.first { it.label == "public ingress" }.url,
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
                opsRoutes()
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
                opsRoutes()
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
    fun opsApiIsAvailableOnLoopbackForLocalDashboardPreview() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                opsRoutes(localPreview = true)
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
        File(dist, "index.html").writeText("""<script src="dashboard-web.js"></script>""")
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
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, Regex("""dashboard-web\.js\?v=[a-f0-9]{16}""").containsMatchIn(response.body<String>()))
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
    }

    @Test
    fun selfTestSummaryPreservesReadmeParitySignals() {
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
        assertEquals(listOf(OpsStatusDto.OK, OpsStatusDto.FAIL), summary.cases.map { it.status })
    }
}
