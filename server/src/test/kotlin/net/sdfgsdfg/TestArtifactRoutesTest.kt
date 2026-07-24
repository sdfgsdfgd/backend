package net.sdfgsdfg

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.sdfgsdfg.data.model.IssueSummaryDto
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.SelfTestCaseDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import net.sdfgsdfg.data.model.TestArtifactDto
import net.sdfgsdfg.data.model.TestArtifactKindDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class TestArtifactRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val noGithubIssues: (String) -> IssueSummaryDto = { IssueSummaryDto() }
    private val noBackendFullSuite = { TestRunSummaryDto("full suite", OpsStatusDto.OK) }

    @Test
    fun liveSelftestRouteNormalizesWithoutChangingTheRawArtifact() = testApplication {
        val artifact = File(createTempDirectory().toFile(), "server-py-selftest.json")
        val raw = json.encodeToString(
            SelfTestResultDto(
                ok = true,
                satisfiedExpectation = true,
                textExcerpt = "zitchdog",
                cases = listOf(
                    SelfTestCaseDto("DeepSeek nonce", true, 500.0, "nonce"),
                    SelfTestCaseDto("GPT-5.6 Sol / Pro", true, 250.0, "model=Pro; pill=Pro"),
                ),
            ),
        )
        artifact.writeText(raw)
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(WebSockets)
            routing { opsRoutes(selfTestArtifactFile = artifact, githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite) }
        }

        val rawResponse = client.get("/api/ops/artifacts/server-py-selftest.json") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val evidenceResponse = client.get("/api/ops/artifacts/server-py-live-e2e.json") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val evidence = json.decodeFromString<TestArtifactDto>(evidenceResponse.body<String>())

        assertEquals(raw, rawResponse.body<String>())
        assertEquals(HttpStatusCode.OK, evidenceResponse.status)
        assertEquals(TestArtifactKindDto.MODEL_SELECTORS, evidence.kind)
        assertEquals(listOf("canary", "model selectors"), evidence.cases.map { it.scope })
    }

    @Test
    fun deployRouteExposesTheDeclaredGate() = testApplication {
        val history = File(createTempDirectory().toFile(), "deploy-history.jsonl").apply {
            writeText("""{"label":"deploy abc1234","status":"OK","timestamp_ms":42,"duration_ms":1200,"detail":"Local health probes passed."}""" + "\n")
        }
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(WebSockets)
            routing { opsRoutes(deployHistorySourceFile = history, githubIssues = noGithubIssues, backendFullSuite = noBackendFullSuite) }
        }

        val response = client.get("/api/ops/artifacts/backend-deploy.json") { header(HttpHeaders.Host, "ops.sdfgsdfg.net") }
        val artifact = json.decodeFromString<TestArtifactDto>(response.body<String>())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("verifyServer", "verifyDashboard-if-needed", "installServer", "local smoke"), artifact.cases.map { it.name })
        assertEquals(4, artifact.cases.count { it.status == OpsStatusDto.OK })
    }
}
