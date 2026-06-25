package net.sdfgsdfg.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import net.sdfgsdfg.data.model.IssueItemDto
import kotlin.math.abs

private data class IssueDragState(
    val repo: IssueRepoModel,
    val issue: IssueItemDto,
    val fromStatus: String,
    val session: IssueDragSession,
)

internal data class IssueDragSession(val id: Int)

internal data class IssueDragPreview(
    val repo: IssueRepoModel,
    val issue: IssueItemDto,
    val bounds: Rect,
    val offset: Offset = Offset.Zero,
    val scale: Float = 1f,
    val panelAnchored: Boolean = false,
)

internal data class IssueDropTarget(
    val status: String,
    val ticketKey: String,
    val bounds: Rect,
    val commit: () -> Unit,
)

internal class IssueBoardDrag {
    var optimisticStatuses by mutableStateOf(emptyMap<String, String>())
        private set
    var preview by mutableStateOf<IssueDragPreview?>(null)
        private set

    private var nextSessionId = 0
    private var previewSession: IssueDragSession? = null
    private var active: IssueDragState? = null
    private var pointer = Offset.Zero
    private val laneBounds = mutableMapOf<String, Rect>()
    private val ticketBounds = mutableMapOf<String, Rect>()

    fun begin(repo: IssueRepoModel, issue: IssueItemDto, pointer: Offset, bounds: Rect?): IssueDragSession {
        val session = IssueDragSession(++nextSessionId)
        active = IssueDragState(repo, issue, issue.status, session)
        this.pointer = pointer
        previewSession = session
        preview = bounds?.let { IssueDragPreview(repo, issue, it) }
        return session
    }

    fun moveBy(session: IssueDragSession, delta: Offset) {
        if (active?.session != session) return
        pointer += delta
        if (previewSession == session) preview = preview?.let { it.copy(offset = it.offset + delta) }
    }

    fun movePreviewTo(session: IssueDragSession, offset: Offset) {
        if (previewSession == session) preview = preview?.copy(offset = offset)
    }

    fun scalePreviewTo(session: IssueDragSession, scale: Float) {
        if (previewSession == session) preview = preview?.copy(scale = scale)
    }

    fun anchorPreviewToPanel(session: IssueDragSession, panelBounds: Rect): Rect? {
        val current = preview?.takeIf { previewSession == session } ?: return null
        val localBounds = current.bounds.offsetBy(Offset(current.offset.x - panelBounds.left, current.offset.y - panelBounds.top))
        preview = current.copy(bounds = localBounds, offset = Offset.Zero, panelAnchored = true)
        return localBounds
    }

    fun retargetPreview(session: IssueDragSession, status: String) {
        if (previewSession == session) preview = preview?.let { it.copy(issue = it.issue.copy(status = status)) }
    }

    private fun clearPreview() {
        preview = null
        previewSession = null
    }

    fun clearPreview(session: IssueDragSession) {
        if (previewSession == session) clearPreview()
    }

    fun cancel(session: IssueDragSession) {
        end(session, clear = true)
    }

    private fun end(session: IssueDragSession, clear: Boolean) {
        if (active?.session == session) {
            active = null
            pointer = Offset.Zero
        }
        if (clear && previewSession == session) clearPreview()
    }

    fun clearOptimisticMoves() {
        optimisticStatuses = emptyMap()
    }

    fun items(repo: IssueRepoModel, lane: IssueLaneSpec) =
        repo.issues.items
            .map { issue -> issue.copy(status = optimisticStatuses[issue.motionKey(repo.id)] ?: issue.status) }
            .filter { it.status == lane.status }
            .sortedByCreation()

    fun placeLane(key: String, bounds: Rect) {
        if (laneBounds[key] != bounds) laneBounds[key] = bounds
    }

    fun pruneLanes(repoId: String, activeStatuses: Set<String>) {
        laneBounds.keys
            .filter { it.substringBefore(':') == repoId && it.substringAfter(':') !in activeStatuses }
            .forEach { laneBounds.remove(it) }
    }

    fun placeTicket(key: String, bounds: Rect) {
        ticketBounds[key] = bounds
    }

    fun placedTicket(key: String): Rect? = ticketBounds[key]

    fun pruneTickets(repoId: String, status: String, activeKeys: Set<String>) {
        ticketBounds.keys
            .filter { it.startsWith("$repoId:") && it.endsWith(":$status") && it !in activeKeys }
            .forEach { ticketBounds.remove(it) }
    }

    fun dropTarget(
        session: IssueDragSession,
        releaseBounds: Rect,
        onMoveIssue: (IssueRepoModel, IssueItemDto, String) -> Unit,
        keepPreview: Boolean = false,
    ): IssueDropTarget? {
        val current = active?.takeIf { it.session == session } ?: return null
        val target = laneBounds.targetAt(pointer) ?: laneBounds.targetAt(releaseBounds.center)
        val dropTarget = target
            ?.takeIf { (repoId, status) -> repoId == current.repo.id && status != current.fromStatus }
            ?.let { (_, status) ->
                targetBounds(current.repo, current.issue, status, releaseBounds)?.let { bounds ->
                    val key = current.issue.motionKey(current.repo.id)
                    val ticketKey = current.issue.copy(status = status).ticketKey(current.repo.id)
                    IssueDropTarget(status, ticketKey, bounds) {
                        optimisticStatuses = optimisticStatuses + (key to status)
                        onMoveIssue(current.repo, current.issue, status)
                    }
                }
            }
        end(session, clear = !keepPreview)
        return dropTarget
    }

    private fun targetBounds(repo: IssueRepoModel, issue: IssueItemDto, status: String, releaseBounds: Rect): Rect? {
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
