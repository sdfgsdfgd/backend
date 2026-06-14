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
internal fun IssuesNew(
    loadState: OpsLoadState,
    pageWidth: Dp,
    onIssuePatch: (OpsIssuePatchDto) -> Unit,
    onEditorActiveChanged: (Boolean) -> Unit = {},
) {
    var editor by remember { mutableStateOf<IssueEditorState?>(null) }
    var archiveRepo by remember { mutableStateOf<IssueRepoModel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun mutate(request: IssueMutationRequestDto) = mutateIssue(
        request = request,
        onLoaded = { patch ->
            onIssuePatch(patch)
            archiveRepo = archiveRepo?.let { open ->
                patch.repos.firstOrNull { it.id == open.id }?.let { repoPatch ->
                    open.copy(issues = repoPatch.issues.mergeIssuePatch(open.issues, patch.generatedAtMs))
                } ?: open
            }
            error = null
        },
        onFailed = { error = it },
    )
    LaunchedEffect(editor != null) {
        onEditorActiveChanged(editor != null)
    }

    when (loadState) {
        OpsLoadState.Loading -> WorkSurface(
            title = "IssuesNew",
            detail = "Static issue board baseline.",
            items = issueLanes.map { it.label },
        )
        is OpsLoadState.Failed -> WorkSurface(
            title = "IssuesNew Unavailable",
            detail = loadState.message,
            items = listOf("/api/ops/summary", "issue summary DTO", "repo lanes"),
        )
        is OpsLoadState.Ready -> Box(modifier = Modifier.fillMaxWidth()) {
            val board = rememberIssueBoardModel(loadState.summary)
            val issueAgeNowMs = (board.generatedAtMs / 60_000L) * 60_000L
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                error?.let { Text(it, color = rose, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                IssueStaticPanels(
                    repos = board.repos,
                    generatedAtMs = issueAgeNowMs,
                    pageWidth = pageWidth,
                    onCreate = { repo, status -> editor = IssueEditorState(repo.id, status) },
                    onEdit = { repo, issue -> editor = IssueEditorState(repo.id, issue.status, issue.id, issue.issueEditorText()) },
                    onArchiveIssue = { repo, issue -> mutate(IssueMutationRequestDto("trash", repo.id, id = issue.id, status = "trash")) },
                    onDeleteIssue = { repo, issue -> mutate(IssueMutationRequestDto("delete", repo.id, id = issue.id)) },
                    onArchive = { archiveRepo = it },
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
            onDelete = { issue -> mutate(IssueMutationRequestDto("delete", repo.id, id = issue.id)) },
            onDismiss = { archiveRepo = null },
        )
    }
}
