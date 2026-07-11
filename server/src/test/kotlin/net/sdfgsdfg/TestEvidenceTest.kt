package net.sdfgsdfg

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.SelfTestCaseDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import net.sdfgsdfg.data.model.TestArtifactDto
import net.sdfgsdfg.data.model.TestArtifactKindDto
import net.sdfgsdfg.data.model.TestCaseDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestEvidenceTest {
    @Test
    fun junitArtifactPreservesScopeParametersAndFailureEvidence() {
        val xml = File(createTempDirectory().toFile(), "TEST-provider.xml").apply {
            writeText(
                """
                <testsuite tests="2" failures="1" errors="0" skipped="0" time="0.75">
                  <testcase classname="tests.integration.provider" name="test_selector[sol-pro]" time="0.25"/>
                  <testcase classname="tests.integration.provider" name="test_boundary" time="0.50">
                    <failure message="wrong pill">expected Pro, observed Pro Extended</failure>
                  </testcase>
                </testsuite>
                """.trimIndent(),
            )
        }

        val artifact = junitArtifact("integration", listOf(JunitEvidenceSource(file = xml)))
        assertEquals(OpsStatusDto.FAIL, artifact?.status)
        assertEquals("1/2 passed · 1 failed", artifact?.summary)
        assertEquals("tests.integration.provider", artifact?.cases?.first()?.scope)
        assertEquals("test_selector[sol-pro]", artifact?.cases?.first()?.name)
        assertEquals(250.0, artifact?.cases?.first()?.durationMs)
        assertTrue(artifact?.cases?.last()?.detail.orEmpty().contains("wrong pill"))
        assertTrue(artifact?.cases?.last()?.detail.orEmpty().contains("Pro Extended"))
    }

    @Test
    fun capabilityResolutionRequiresTheArtifactLedgerSnapshot() {
        val ledger = File(createTempDirectory().toFile(), "capability_contracts.json").apply {
            writeText(
                """
                {
                  "subsystem_taxonomy": [{
                    "id": "command-provider-surface",
                    "name": "Command, Provider, And Transport Surface",
                    "purpose": "Protect provider boundaries."
                  }],
                  "contracts": [{
                    "id": "integration.provider.rpc-model-selectors",
                    "subsystem": "command-provider-surface",
                    "capability": "Model routing remains exact.",
                    "evidence": ["tests/integration/provider.py::test_selector"]
                  }]
                }
                """.trimIndent(),
            )
        }
        val case = TestCaseDto(
            name = "test_selector[sol-pro]",
            scope = "tests.integration.provider",
            status = OpsStatusDto.OK,
        )
        val artifact = TestArtifactDto(
            label = "integration",
            ledgerSha = ledger.sha256ForTest(),
            cases = listOf(case),
        ).withCapabilityContracts(ledger)
        val contract = artifact.cases.single().contracts.single()

        assertEquals("integration.provider.rpc-model-selectors", contract.id)
        assertEquals("command-provider-surface", contract.subsystem)
        assertEquals("Command, Provider, And Transport Surface", contract.subsystemName)
        assertEquals("Protect provider boundaries.", contract.subsystemPurpose)
        assertTrue(TestArtifactDto(label = "integration", ledgerSha = "stale", cases = listOf(case))
            .withCapabilityContracts(ledger).cases.single().contracts.isEmpty())
    }

    @Test
    fun liveSelftestBecomesASelectorArtifactWithoutChangingTheRawContract() {
        val artifact = SelfTestResultDto(
            ok = true,
            satisfiedExpectation = true,
            textExcerpt = "zitchdog",
            latencyMs = 2_000.0,
            cases = listOf(
                SelfTestCaseDto("DeepSeek nonce", true, 500.0, "nonce"),
                SelfTestCaseDto("GPT-5.6 Sol / Pro", true, 250.0, "model=Pro; pill=Pro"),
            ),
        ).toTestArtifact()

        assertEquals(TestArtifactKindDto.MODEL_SELECTORS, artifact.kind)
        assertEquals("canary", artifact.cases.first().scope)
        assertEquals("model selectors", artifact.cases.last().scope)
        assertEquals("2/2 checks passed", artifact.summary)
    }

    @Test
    fun failedDeployGateLeavesUnobservedStagesUnknown() {
        val artifact = deployGateArtifact(TestRunSummaryDto("deploy deadbee", OpsStatusDto.FAIL, detail = "verifyServer failed"))
        assertEquals(OpsStatusDto.FAIL, artifact.status)
        assertEquals(listOf("verifyServer", "dashboard build-if-needed", "installServer", "local smoke"), artifact.cases.map { it.name })
        assertTrue(artifact.cases.all { it.status == OpsStatusDto.UNKNOWN })
    }

    @Test
    fun fullSuiteArtifactKeepsBranchesAndDropsTheAggregator() {
        val jobs = Json.parseToJsonElement(
            """
            [
              {"name":"local-tests","status":"completed","conclusion":"success","started_at":"2026-07-11T00:00:00Z","completed_at":"2026-07-11T00:00:02Z","html_url":"https://github.test/local"},
              {"name":"all-tests / server-py-live-selftest","status":"completed","conclusion":"failure","started_at":"2026-07-11T00:00:00Z","completed_at":"2026-07-11T00:00:05Z","html_url":"https://github.test/live"},
              {"name":"all-tests","status":"completed","conclusion":"failure","started_at":"2026-07-11T00:00:05Z","completed_at":"2026-07-11T00:00:06Z"}
            ]
            """.trimIndent(),
        ).jsonArray.map { it.jsonObject }
        val artifact = backendFullSuiteArtifact(TestRunSummaryDto("full suite", OpsStatusDto.FAIL), jobs)

        assertEquals(listOf("Local backend runtime", "server_py live browser"), artifact.cases.map { it.name })
        assertEquals(listOf(OpsStatusDto.OK, OpsStatusDto.FAIL), artifact.cases.map { it.status })
        assertEquals(listOf(2_000.0, 5_000.0), artifact.cases.map { it.durationMs })
        assertEquals("1/2 testchain branches passed", artifact.summary)
    }
}

private fun File.sha256ForTest(): String = MessageDigest.getInstance("SHA-256")
    .digest(readBytes())
    .joinToString("") { "%02x".format(it) }
