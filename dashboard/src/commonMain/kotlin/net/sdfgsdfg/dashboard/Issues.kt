package net.sdfgsdfg.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.data.model.IssueMutationRequestDto
import net.sdfgsdfg.data.model.OpsIssuePatchDto

@Composable
internal fun Issues(
    loadState: OpsLoadState,
    pageWidth: Dp,
    canWriteIssues: Boolean,
    onIssuePatch: (OpsIssuePatchDto) -> Unit,
    onEditorActiveChanged: (Boolean) -> Unit = {},
) {
    var editor by remember { mutableStateOf<IssueEditorState?>(null) }
    var archiveRepo by remember { mutableStateOf<IssueRepoModel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val drag = remember { IssueBoardDrag() }

    fun mutate(request: IssueMutationRequestDto) {
        if (!canWriteIssues) {
            drag.clearOptimisticMoves()
            error = "Read-only viewer"
            return
        }
        mutateIssue(
            request = request,
            onLoaded = { patch ->
                onIssuePatch(patch)
                drag.clearOptimisticMoves()
                archiveRepo = archiveRepo?.let { open ->
                    patch.repos.firstOrNull { it.id == open.id }?.let { repoPatch ->
                        open.copy(issues = repoPatch.issues.mergeIssuePatch(open.issues, patch.generatedAtMs))
                    } ?: open
                }
                error = null
            },
            onFailed = {
                drag.clearOptimisticMoves()
                error = it
            },
        )
    }
    LaunchedEffect(editor != null) {
        onEditorActiveChanged(editor != null)
    }
    LaunchedEffect(canWriteIssues) {
        if (!canWriteIssues) {
            editor = null
            drag.clearOptimisticMoves()
        }
    }

    when (loadState) {
        OpsLoadState.Loading -> WorkSurface(
            title = "Issues",
            detail = "Waiting for local .arcana/issues.json summaries before shaping lanes.",
            items = issueLanes.map { it.label },
        )
        is OpsLoadState.Failed -> WorkSurface(
            title = "Issues Unavailable",
            detail = loadState.message,
            items = listOf("/api/ops/summary", "issue summary DTO", "repo lanes"),
        )
        is OpsLoadState.Ready -> Box(modifier = Modifier.fillMaxWidth()) {
            val board = rememberIssueBoardModel(loadState.summary)
            val issueAgeNowMs = (board.generatedAtMs / 60_000L) * 60_000L
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                error?.let { Text(it, color = rose, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                IssuePanels(
                    repos = board.repos,
                    generatedAtMs = issueAgeNowMs,
                    pageWidth = pageWidth,
                    drag = drag,
                    canWriteIssues = canWriteIssues,
                    onCreate = { repo, status -> editor = IssueEditorState(repo.id, status) },
                    onEdit = { repo, issue -> editor = IssueEditorState(repo.id, issue.status, issue.id, issue.issueEditorText()) },
                    onArchiveIssue = { repo, issue -> mutate(IssueMutationRequestDto("trash", repo.id, id = issue.id, status = "trash")) },
                    onDeleteIssue = { repo, issue -> mutate(IssueMutationRequestDto("delete", repo.id, id = issue.id)) },
                    onArchive = { archiveRepo = it },
                    onMoveIssue = { repo, issue, status -> mutate(IssueMutationRequestDto("move", repo.id, id = issue.id, status = status)) },
                )
                IssueEventStrip(board, animatedFreshness = false, motionSafeSurface = true)
            }
        }
    }
    editor?.let { state ->
        IssueEditorDialog(
            state = state,
            onDismiss = { editor = null },
            onSave = { text ->
                val body = text.trim()
                val request = body.takeIf { it.isNotBlank() }
                    ?.let { IssueMutationRequestDto(if (state.id == null) "create" else "update", state.repoId, state.id, state.status, it) }
                editor = null
                request?.let(::mutate)
            },
        )
    }
    archiveRepo?.let { repo ->
        ArchiveDialog(
            repo = repo,
            canWriteIssues = canWriteIssues,
            onDelete = { issue -> mutate(IssueMutationRequestDto("delete", repo.id, id = issue.id)) },
            onDismiss = { archiveRepo = null },
        )
    }
}
