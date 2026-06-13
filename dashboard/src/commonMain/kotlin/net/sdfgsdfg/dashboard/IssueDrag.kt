package net.sdfgsdfg.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import net.sdfgsdfg.data.model.IssueItemDto
import net.sdfgsdfg.data.model.RepoHealthDto
import kotlin.math.abs

private data class IssueDragState(
    val repo: RepoHealthDto,
    val issue: IssueItemDto,
    val fromStatus: String,
)

internal data class IssueDropTarget(
    val bounds: Rect,
    val commit: () -> Unit,
)

internal class IssueBoardDrag {
    var optimisticStatuses by mutableStateOf(emptyMap<String, String>())
        private set

    private var active: IssueDragState? = null
    private var pointer = Offset.Zero
    private val laneBounds = mutableMapOf<String, Rect>()
    private val ticketBounds = mutableMapOf<String, Rect>()

    fun begin(repo: RepoHealthDto, issue: IssueItemDto, pointer: Offset) {
        active = IssueDragState(repo, issue, issue.status)
        this.pointer = pointer
    }

    fun moveBy(delta: Offset) {
        pointer += delta
    }

    private fun end() {
        active = null
        pointer = Offset.Zero
    }

    fun clearOptimisticMoves() {
        optimisticStatuses = emptyMap()
    }

    fun items(repo: RepoHealthDto, lane: IssueLaneSpec) =
        repo.issues.items
            .map { issue -> issue.copy(status = optimisticStatuses[issue.motionKey(repo.id)] ?: issue.status) }
            .filter { it.status == lane.status }
            .sortedByCreation()

    fun suppressesMotion(key: String) = key in optimisticStatuses

    fun placeLane(key: String, bounds: Rect) {
        if (laneBounds[key] != bounds) laneBounds[key] = bounds
    }

    fun placeTicket(key: String, bounds: Rect) {
        ticketBounds[key] = bounds
    }

    fun pruneTickets(repoId: String, status: String, activeKeys: Set<String>) {
        ticketBounds.keys
            .filter { it.startsWith("$repoId:") && it.endsWith(":$status") && it !in activeKeys }
            .forEach { ticketBounds.remove(it) }
    }

    fun dropTarget(releaseBounds: Rect, onMoveIssue: (RepoHealthDto, IssueItemDto, String) -> Unit): IssueDropTarget? {
        val current = active ?: return null
        val target = laneBounds.targetAt(pointer) ?: laneBounds.targetAt(releaseBounds.center)
        val dropTarget = target
            ?.takeIf { (repoId, status) -> repoId == current.repo.id && status != current.fromStatus }
            ?.let { (_, status) ->
                targetBounds(current.repo, current.issue, status, releaseBounds)?.let { bounds ->
                    val key = current.issue.motionKey(current.repo.id)
                    IssueDropTarget(bounds) {
                        optimisticStatuses = optimisticStatuses + (key to status)
                        onMoveIssue(current.repo, current.issue, status)
                    }
                }
            }
        end()
        return dropTarget
    }

    private fun targetBounds(repo: RepoHealthDto, issue: IssueItemDto, status: String, releaseBounds: Rect): Rect? {
        val laneRect = laneBounds["${repo.id}:$status"] ?: return null
        val key = issue.motionKey(repo.id)
        val projected = (repo.issues.items
            .map { it.copy(status = optimisticStatuses[it.motionKey(repo.id)] ?: it.status) }
            .filter { it.status == status && it.motionKey(repo.id) != key } + issue.copy(status = status))
            .sortedByCreation()
        val index = projected.indexOfFirst { it.motionKey(repo.id) == key }.takeIf { it >= 0 } ?: return null
        val before = projected.take(index).asReversed().firstNotNullOfOrNull { ticketBounds[it.ticketKey(repo.id)] }
        val after = projected.drop(index + 1).firstNotNullOfOrNull { ticketBounds[it.ticketKey(repo.id)] }
        val fallback = projected.firstNotNullOfOrNull { ticketBounds[it.ticketKey(repo.id)] }
        val left = after?.left ?: before?.left ?: fallback?.left ?: (laneRect.left + issueLanePaddingPx)
        val top = after?.top ?: before?.let { it.bottom + issueCardGapPx } ?: fallback?.top ?: (laneRect.top + issueLaneEmptyDropTopPx)
        return Rect(left, top, left + releaseBounds.width, top + releaseBounds.height)
    }
}

internal fun Rect.offsetBy(offset: Offset) = Rect(left + offset.x, top + offset.y, right + offset.x, bottom + offset.y)

private fun Map<String, Rect>.targetAt(pointer: Offset): Pair<String, String>? {
    entries.firstOrNull { (_, bounds) -> bounds.contains(pointer) }?.key?.laneTargetOrNull()?.let { return it }
    entries.forEach { (key, bounds) ->
        if (pointer.x < bounds.left || pointer.x > bounds.right) return@forEach
        val repoId = key.substringBefore(':')
        var aligned = 0
        var top = bounds.top
        var bottom = bounds.bottom
        entries.forEach { (otherKey, otherBounds) ->
            if (otherKey.substringBefore(':') == repoId && abs(otherBounds.top - bounds.top) < 24f) {
                aligned += 1
                top = minOf(top, otherBounds.top)
                bottom = maxOf(bottom, otherBounds.bottom)
            }
        }
        if (aligned > 1 && pointer.y >= top && pointer.y <= bottom) return key.laneTargetOrNull()
    }
    return null
}

private fun String.laneTargetOrNull() = split(':', limit = 2)
    .takeIf { it.size == 2 }
    ?.let { it[0] to it[1] }
