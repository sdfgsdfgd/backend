package net.sdfgsdfg.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import net.sdfgsdfg.data.model.IssueEventDto
import net.sdfgsdfg.data.model.IssueItemDto
import net.sdfgsdfg.data.model.IssueSummaryDto
import net.sdfgsdfg.data.model.OpsSummaryDto

internal const val issueMotionEnterMs = 2_400
internal const val issueMotionExitMs = 1_800
internal const val issueMotionHoldMs = 3_000L
internal const val issueMotionFlashInMs = 450
internal const val issueMotionFlashOutMs = 1_800
internal const val issueMotionMoveFadeMs = 560
internal const val issueMotionMoveSlideMs = 680
internal const val issueDropReleaseMs = 760
internal const val issueCardGapPx = 6f
internal const val issueLanePaddingPx = 8f
internal const val issueLaneEmptyDropTopPx = 42f
internal val issueEnterLabels = setOf("new", "moved")

internal val issueLanes = listOf(
    IssueLaneSpec("BLOCKED", "blocked", rose) { it.blocked },
    IssueLaneSpec("TODO", "todo", Color(0xFF8EA0B8)) { it.todo },
    IssueLaneSpec("WIP", "wip", cyan) { it.wip },
    IssueLaneSpec("REVIEW", "review", amber) { it.review },
    IssueLaneSpec("DONE", "done", green) { it.done },
)

@Immutable
internal data class IssueBoardModel(
    val generatedAtMs: Long,
    val repos: List<IssueRepoModel>,
    val events: List<IssueEventModel>,
)

@Immutable
internal data class IssueRepoModel(
    val id: String,
    val name: String,
    val role: String,
    val note: String?,
    val issues: IssueSummaryDto,
)

@Immutable
internal data class IssueEventModel(
    val repo: IssueRepoModel,
    val event: IssueEventDto,
)

internal fun OpsSummaryDto.issueBoardModel(): IssueBoardModel {
    val issueRepos = repos.map { repo ->
        IssueRepoModel(
            id = repo.id,
            name = repo.name,
            role = repo.role,
            note = repo.note,
            issues = repo.issues.copy(events = emptyList()),
        )
    }
    return IssueBoardModel(
        generatedAtMs = generatedAtMs,
        repos = issueRepos,
        events = repos.zip(issueRepos).flatMap { (repo, issueRepo) ->
            repo.issues.events.map { IssueEventModel(issueRepo, it) }
        },
    )
}

@Composable
internal fun rememberIssueBoardModel(summary: OpsSummaryDto): IssueBoardModel {
    val previous = remember { arrayOf<IssueBoardModel?>(null) }
    val next = remember(summary.generatedAtMs, summary.repos) {
        val model = summary.issueBoardModel()
        previous[0]?.let {
            model.copy(
                repos = if (it.repos == model.repos) it.repos else model.repos,
                events = if (it.events == model.events) it.events else model.events,
            )
        } ?: model
    }
    SideEffect {
        previous[0] = next
    }
    return next
}

internal fun IssueItemDto.motionKey(repoId: String) = "$repoId:$source:$id"

internal fun IssueItemDto.ticketKey(repoId: String) = "${motionKey(repoId)}:$status"

internal fun IssueItemDto.issueEditorText() = buildString {
    append(title.ifBlank { id })
    if (description.isNotBlank()) append("\n").append(description)
}

internal fun String.visibleNotes(description: String) = trim().takeIf { it.isNotBlank() && !description.contains(it) }

internal fun IssueRepoModel.issueCode(issue: IssueItemDto) = issueCode(issue.id)

internal fun IssueRepoModel.issueCode(id: String): String {
    val prefix = id.issueRepoPrefixOrNull() ?: issueRepoPrefix()
    id.issueNumberOrNull(prefix)?.let { return "$prefix-${it.toString().padStart(3, '0')}" }
    val index = issues.items.indexOfFirst { it.id == id }.coerceAtLeast(0)
    return "$prefix-${index.toString().padStart(3, '0')}"
}

internal fun String.issueSourceShort() = removeSuffix(" issues").removeSuffix(" Issues")

private fun IssueRepoModel.issueRepoPrefix() = when (id) {
    "arcana" -> "ARC"
    "backend" -> "BCK"
    "server_py" -> "SPY"
    else -> name.take(3).uppercase().padEnd(3, 'X')
}

private fun String.issueRepoPrefixOrNull() = takeIf { length > 4 && this[3] == '-' && take(3).all(Char::isLetter) && drop(4).all(Char::isDigit) }
    ?.take(3)

private fun String.issueNumberOrNull(prefix: String) =
    takeIf { startsWith("$prefix-") }?.substringAfter('-')?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.toIntOrNull()
