package net.sdfgsdfg.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sdfgsdfg.data.model.IssueItemDto

@Composable
internal fun ArchiveDialog(
    repo: IssueRepoModel,
    canWriteIssues: Boolean,
    onDelete: (IssueItemDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val archived = remember(repo.issues.items) {
        repo.issues.items.filter { it.status == "trash" }.sortedByCreation()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${repo.name} Archive", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                StatusPill("${archived.size}", if (archived.isEmpty()) muted else cyan)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (archived.isEmpty()) {
                    Text("No archived tickets", color = muted, fontSize = 12.sp)
                }
                archived.take(14).forEach { issue ->
                    key(issue.motionKey(repo.id)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(Brush.horizontalGradient(listOf(panelRaised, cyan.copy(alpha = 0.045f))))
                                .border(BorderStroke(1.dp, cyan.copy(alpha = 0.20f)), RoundedCornerShape(7.dp))
                                .padding(9.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${repo.issueCode(issue)} · ${issue.title.ifBlank { issue.id }}", color = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(issue.description.ifBlank { issue.notes }.ifBlank { " " }, color = Color(0xFFB9C5D2), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (canWriteIssues) DeleteButton { onDelete(issue) }
                        }
                    }
                }
                if (archived.size > 14) {
                    Text("${archived.size - 14} more", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun IssueEditorDialog(state: IssueEditorState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember(state) { mutableStateOf(state.body) }
    val focusRequester = remember { FocusRequester() }
    fun save() = onSave(draft)
    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when {
            event.key == Key.Enter && event.isMetaPressed -> { save(); true }
            event.key == Key.Escape -> { onDismiss(); true }
            else -> false
        }
    }
    LaunchedEffect(state) {
        runCatching { focusRequester.requestFocus() }
    }
    AlertDialog(
        modifier = Modifier.onPreviewKeyEvent(::handleKey),
        onDismissRequest = onDismiss,
        title = { Text(if (state.id == null) "New ${state.status}" else "Edit issue", color = text) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent(::handleKey),
                minLines = 7,
                maxLines = 12,
            )
        },
        confirmButton = { TextButton(onClick = ::save) { Text("save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel") } },
    )
}

internal data class IssueEditorState(
    val repoId: String,
    val status: String,
    val id: String? = null,
    val body: String = "",
)
