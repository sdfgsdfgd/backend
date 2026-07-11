package net.sdfgsdfg

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.SelfTestResultDto
import net.sdfgsdfg.data.model.TestArtifactDto
import net.sdfgsdfg.data.model.TestArtifactKindDto
import net.sdfgsdfg.data.model.TestCaseDto
import net.sdfgsdfg.data.model.TestContractRefDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

private val evidenceJson = Json { ignoreUnknownKeys = true }

// Keep case payloads out of the websocket snapshot: summaries point here and the dashboard hydrates evidence only after expansion.
internal data class JunitEvidenceSource(val label: String? = null, val file: File)

internal fun junitArtifact(
    label: String,
    sources: List<JunitEvidenceSource>,
    timestampMs: Long? = null,
    durationMs: Double? = null,
    detail: String? = null,
    coveragePct: Double? = null,
    url: String? = null,
): TestArtifactDto? {
    val cases = sources.flatMap { source -> source.file.junitCases(source.label) }
    if (cases.isEmpty()) return null
    val failed = cases.count { it.status == OpsStatusDto.FAIL }
    val skipped = cases.count { it.status == OpsStatusDto.WARN }
    val passed = cases.size - failed - skipped
    val summary = buildList {
        add("$passed/${cases.size} passed")
        if (failed > 0) add("$failed failed")
        if (skipped > 0) add("$skipped skipped")
    }.joinToString(" · ")
    return TestArtifactDto(
        label = label,
        status = if (failed == 0) OpsStatusDto.OK else OpsStatusDto.FAIL,
        timestampMs = timestampMs,
        durationMs = durationMs ?: cases.sumOf { it.durationMs ?: 0.0 },
        detail = detail ?: summary,
        url = url,
        coveragePct = coveragePct,
        summary = summary,
        cases = cases,
    )
}

private fun File.junitCases(sourceLabel: String?): List<TestCaseDto> = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    val nodes = factory.newDocumentBuilder().parse(this).getElementsByTagName("testcase")
    (0 until nodes.length).mapNotNull { index ->
        val node = nodes.item(index) as? Element ?: return@mapNotNull null
        val name = node.getAttribute("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val className = node.getAttribute("classname").takeIf(String::isNotBlank)
        val failure = node.getElementsByTagName("failure").item(0) as? Element
        val error = node.getElementsByTagName("error").item(0) as? Element
        val skipped = node.getElementsByTagName("skipped").item(0) as? Element
        val evidence = failure ?: error ?: skipped
        TestCaseDto(
            name = name,
            scope = listOfNotNull(sourceLabel, className).joinToString(".").takeIf(String::isNotBlank),
            status = when {
                failure != null || error != null -> OpsStatusDto.FAIL
                skipped != null -> OpsStatusDto.WARN
                else -> OpsStatusDto.OK
            },
            durationMs = node.getAttribute("time").toDoubleOrNull()?.times(1_000.0),
            detail = evidence?.let {
                listOfNotNull(
                    it.getAttribute("message").takeIf(String::isNotBlank),
                    it.textContent?.trim()?.take(1_200)?.takeIf(String::isNotBlank),
                ).distinct().joinToString("\n").takeIf(String::isNotBlank)
            },
        )
    }
}.getOrDefault(emptyList())

internal fun TestArtifactDto.toRunSummary(artifactUrl: String) = TestRunSummaryDto(
    label = label,
    status = status,
    timestampMs = timestampMs,
    durationMs = durationMs,
    detail = detail ?: summary,
    url = url,
    artifactUrl = artifactUrl,
    coveragePct = coveragePct,
)

internal fun SelfTestResultDto.toTestArtifact() = TestArtifactDto(
    label = "live e2e",
    status = if (ok && satisfiedExpectation) OpsStatusDto.OK else OpsStatusDto.FAIL,
    timestampMs = timestampMs.takeIf { it > 0 },
    durationMs = latencyMs,
    detail = rawError,
    url = workflowUrl,
    kind = TestArtifactKindDto.MODEL_SELECTORS,
    summary = "${cases.count { it.ok }}/${cases.size} checks passed",
    cases = cases.map { case ->
        TestCaseDto(
            name = case.name,
            status = if (case.ok) OpsStatusDto.OK else OpsStatusDto.FAIL,
            scope = if (case.name.startsWith("DeepSeek")) "canary" else "model selectors",
            durationMs = case.latencyMs,
            detail = case.note,
        )
    },
)

internal fun deployGateArtifact(run: TestRunSummaryDto) = TestArtifactDto(
    label = "deploy gate",
    status = run.status,
    timestampMs = run.timestampMs,
    durationMs = run.durationMs,
    detail = run.detail,
    summary = if (run.status == OpsStatusDto.OK) "4/4 deployment contracts passed" else "Deployment gate did not complete",
    cases = listOf(
        "verifyServer" to "Compiles the backend and runs its server and shared-contract tests.",
        "dashboard build-if-needed" to "Rebuilds the web dashboard when tracked UI inputs changed; otherwise reuses the verified artifact.",
        "installServer" to "Installs the runnable backend distribution before the runtime swap.",
        "local smoke" to "Probes the restarted backend locally before the deployment is accepted.",
    ).map { (name, meaning) ->
        TestCaseDto(
            name = name,
            scope = "deployment contract",
            status = if (run.status == OpsStatusDto.OK) OpsStatusDto.OK else OpsStatusDto.UNKNOWN,
            detail = meaning,
        )
    },
)

internal fun TestArtifactDto.withCapabilityContracts(ledgerFile: File): TestArtifactDto {
    // A moving ledger must never relabel historical evidence. Only the exact ledger snapshot that produced the artifact may enrich it.
    val expectedSha = ledgerSha?.trim()?.lowercase() ?: return this
    if (!ledgerFile.isFile || ledgerFile.sha256() != expectedSha) return this
    val contracts = runCatching {
        val ledger = evidenceJson.parseToJsonElement(ledgerFile.readText()).jsonObject
        val subsystems = ledger["subsystem_taxonomy"]?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { subsystem -> subsystem.text("id")?.let { it to subsystem } }
            .toMap()
        ledger["contracts"]?.jsonArray.orEmpty()
            .mapNotNull { raw -> raw as? JsonObject }
            .flatMap { contract ->
                val subsystem = contract.text("subsystem")
                val taxonomy = subsystem?.let(subsystems::get)
                val reference = TestContractRefDto(
                    id = contract.text("id") ?: return@flatMap emptyList(),
                    subsystem = subsystem,
                    subsystemName = taxonomy?.text("name"),
                    subsystemPurpose = taxonomy?.text("purpose"),
                    capability = contract.text("capability"),
                )
                contract["evidence"]?.jsonArray.orEmpty().mapNotNull { evidence ->
                    evidence.jsonPrimitive.contentOrNull?.takeIf { "::" in it }?.normalizedEvidenceId()?.let { it to reference }
                }
            }
            .groupBy({ it.first }, { it.second })
    }.getOrNull() ?: return this
    return copy(cases = cases.map { case ->
        case.copy(contracts = contracts[case.evidenceId()].orEmpty().distinctBy(TestContractRefDto::id))
    })
}

private fun TestCaseDto.evidenceId(): String = (
    scope?.let { "$it::${name.substringBefore('[')}" } ?: name.substringBefore('[')
).replace('\\', '/')

private fun String.normalizedEvidenceId(): String {
    val path = substringBefore("::").removeSuffix(".py").replace('/', '.')
    return "$path::${substringAfter("::").substringBefore('[')}"
}

private fun File.sha256(): String = inputStream().use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
