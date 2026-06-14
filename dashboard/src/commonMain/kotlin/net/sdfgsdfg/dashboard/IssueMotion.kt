package net.sdfgsdfg.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import net.sdfgsdfg.data.model.IssueItemDto

@Composable
internal fun rememberIssueBoardMotionState(board: IssueBoardModel): IssueBoardMotionState {
    val previous = remember { arrayOf<Map<String, IssueSnapshot>?>(null) }
    var retained by remember { mutableStateOf(IssueBoardMotionState()) }
    val current = remember(board.repos) { board.issueSnapshots() }
    val detected = previous[0]?.takeIf { it != current }?.let { old ->
        val currentIds = current.keys
        val moved = current.values.mapNotNull { next ->
            old[next.key]
                ?.takeIf { it.repoId != next.repoId || it.status != next.status }
                ?.let { it to next }
        }
        val created = current.values.filter { it.key !in old }
        val removed = old.values.filter { it.key !in currentIds }
        val updated = current.values.filter { next ->
            old[next.key]?.let { it.repoId == next.repoId && it.status == next.status && it.issue != next.issue } == true
        }

        val nextCues = buildMap {
            updated.forEach { put(it.ticketKey, IssueMotionCue("updated")) }
            created.forEach { put(it.ticketKey, IssueMotionCue("new")) }
            moved.forEach { (old, next) ->
                put(next.ticketKey, IssueMotionCue("moved", old.moveDirectionTo(next)))
            }
        }
        val nextExits = removed.map { it.exitSlot(board.generatedAtMs) }
        IssueBoardMotionState(nextCues, nextExits)
    } ?: IssueBoardMotionState()
    val motion = if (detected.active) detected else retained

    SideEffect {
        previous[0] = current
        if (detected.active) retained = detected
    }

    LaunchedEffect(retained) {
        if (!retained.active) return@LaunchedEffect
        delay(issueMotionHoldMs)
        retained = IssueBoardMotionState()
    }

    return motion
}

internal data class IssueBoardMotionState(
    val cues: Map<String, IssueMotionCue> = emptyMap(),
    val exits: List<IssueTicketSlot> = emptyList(),
)

internal val IssueBoardMotionState.active get() = cues.isNotEmpty() || exits.isNotEmpty()

internal data class IssueMotionCue(
    val label: String,
    val moveDirection: Int = 0,
)

private data class IssueSnapshot(
    val repoId: String,
    val issue: IssueItemDto,
    val status: String,
    val index: Int,
) {
    val key = issue.motionKey(repoId)
    val ticketKey = issue.ticketKey(repoId)
}

internal data class IssueTicketSlot(
    val repoId: String,
    val status: String,
    val issue: IssueItemDto,
    val key: String,
    val index: Int,
    val exiting: Boolean,
)

private fun IssueBoardModel.issueSnapshots(): Map<String, IssueSnapshot> =
    repos.flatMap { repo ->
        issueLanes.flatMap { lane ->
            lane.items(repo.issues)
                .mapIndexed { index, issue -> IssueSnapshot(repo.id, issue, issue.status, index) }
        }
    }.associateBy { it.key }

private fun IssueSnapshot.exitSlot(generatedAtMs: Long) = IssueTicketSlot(
    repoId = repoId,
    status = status,
    issue = issue,
    key = "exit:$ticketKey:$generatedAtMs",
    index = index,
    exiting = true,
)

private fun IssueSnapshot.moveDirectionTo(next: IssueSnapshot): Int {
    val from = issueLanes.indexOfFirst { it.status == status }
    val to = issueLanes.indexOfFirst { it.status == next.status }
    return when {
        from < 0 || to < 0 || from == to -> 0
        from < to -> -1
        else -> 1
    }
}
