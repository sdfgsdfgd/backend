package net.sdfgsdfg

import net.sdfgsdfg.data.model.OpsStatusDto
import net.sdfgsdfg.data.model.OpsSummaryDto
import net.sdfgsdfg.data.model.RepoHealthDto
import net.sdfgsdfg.data.model.TestRunSummaryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackendRuntimeTest {
    @Test
    fun socketHubsKeepTransientRunsIsolated() {
        val first = OpsSocketHub()
        val second = OpsSocketHub()
        val summary = OpsSummaryDto(
            generatedAtMs = 0,
            repos = listOf(RepoHealthDto("backend", "backend", "test", OpsStatusDto.OK)),
        )

        try {
            first.broadcastRunStarted("backend", TestRunSummaryDto("deploy", OpsStatusDto.WIP, timestampMs = 1))

            assertEquals("deploy", first.withActiveRuns(summary).repos.single().history.single().label)
            assertTrue(second.withActiveRuns(summary).repos.single().history.isEmpty())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun runtimeShutdownIsIdempotent() {
        BackendRuntime().run {
            close()
            close()
            assertTrue(opsHttp.isTerminated)
        }
    }
}
