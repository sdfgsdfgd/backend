package net.sdfgsdfg.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.sdfgsdfg.dashboard.generated.resources.Res
import net.sdfgsdfg.dashboard.generated.resources.delius
import net.sdfgsdfg.dashboard.generated.resources.issue_editor_papyrus_panel
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource

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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(30.dp)
        val tabFont = FontFamily(Font(Res.font.delius, FontWeight.Normal))
        val scriptFont = tabFont
        val fieldShape = RoundedCornerShape(7.dp)
        val darkPurple = Color(0xFF6A35D8)
        val titlePurple = Color(0xFF28105F)
        val glowBleed = 132.dp
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val panelWidth = (maxWidth * 0.60f).coerceIn(640.dp, 1120.dp)
            val panelHeight = maxHeight * 0.80f
            Box(
                modifier = Modifier
                    .width(panelWidth + glowBleed * 2f)
                    .height(panelHeight + glowBleed * 2f)
                    .onPreviewKeyEvent(::handleKey),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            val corner = 30.dp.toPx()
                            val panelWidthPx = panelWidth.toPx()
                            val panelHeightPx = panelHeight.toPx()
                            val panelTopLeft = Offset(
                                x = (size.width - panelWidthPx) / 2f,
                                y = (size.height - panelHeightPx) / 2f,
                            )
                            drawContent()
                            drawRoundRect(
                                color = Color.Transparent,
                                topLeft = panelTopLeft,
                                size = Size(panelWidthPx, panelHeightPx),
                                cornerRadius = CornerRadius(corner, corner),
                                blendMode = BlendMode.Clear,
                            )
                        },
                ) {
                    Box(
                        Modifier
                            .width(panelWidth)
                            .height(panelHeight)
                            .align(Alignment.Center)
                            .dropShadow(
                                shape,
                                Shadow(
                                    radius = 44.dp,
                                    spread = 4.dp,
                                    offset = DpOffset(0.dp, 16.dp),
                                    color = Color.Black,
                                    alpha = 0.30f,
                                ),
                            )
                            .dropShadow(
                                shape,
                                Shadow(
                                    radius = 72.dp,
                                    spread = 22.dp,
                                    offset = DpOffset.Zero,
                                    color = darkPurple,
                                    alpha = 0.44f,
                                ),
                            ),
                    )
                }
                Box(
                    Modifier
                        .width(panelWidth)
                        .height(panelHeight)
                        .align(Alignment.Center)
                        .clip(shape)
                        .background(Color(0xFF120909).copy(alpha = 0.08f), shape)
                        .border(BorderStroke(1.dp, darkPurple.copy(alpha = 0.48f)), shape)
                        .border(BorderStroke(1.dp, amber.copy(alpha = 0.14f)), shape),
                ) {
                    Image(
                        painter = painterResource(Res.drawable.issue_editor_papyrus_panel),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.80f),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF050202).copy(alpha = 0.06f),
                                        Color(0xFF070303).copy(alpha = 0.10f),
                                    ),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 34.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        val editorTitle = if (state.id == null) "New ${state.status}" else "Edit issue"
                        Box(Modifier.padding(start = 56.dp, end = 28.dp)) {
                            Text(
                                editorTitle,
                                color = Color.Black.copy(alpha = 0.96f),
                                fontSize = 33.sp,
                                fontFamily = tabFont,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.graphicsLayer {
                                    translationX = 1.4f
                                    translationY = 2.4f
                                },
                                style = androidx.compose.ui.text.TextStyle(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color.Black.copy(alpha = 0.98f),
                                        offset = Offset.Zero,
                                        blurRadius = 10.0f,
                                    ),
                                ),
                            )
                            Text(
                                editorTitle,
                                color = Color(0xFF8E6425).copy(alpha = 0.34f),
                                fontSize = 33.sp,
                                fontFamily = tabFont,
                                fontWeight = FontWeight.Bold,
                                style = androidx.compose.ui.text.TextStyle(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color(0xFFB17A2B).copy(alpha = 0.32f),
                                        offset = Offset.Zero,
                                        blurRadius = 1.4f,
                                    ),
                                ),
                            )
                            Text(
                                editorTitle,
                                color = titlePurple.copy(alpha = 0.96f),
                                fontSize = 33.sp,
                                fontFamily = tabFont,
                                fontWeight = FontWeight.Bold,
                                style = androidx.compose.ui.text.TextStyle(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color(0xFF020006).copy(alpha = 0.96f),
                                        offset = Offset.Zero,
                                        blurRadius = 6.6f,
                                    ),
                                ),
                            )
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(start = 22.dp, end = 14.dp)
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent(::handleKey),
                            shape = fieldShape,
                            minLines = 8,
                            maxLines = 24,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = scriptFont,
                                fontSize = 17.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color(0xFF080301).copy(alpha = 0.76f),
                                    offset = Offset.Zero,
                                    blurRadius = 1.8f,
                                ),
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFF090502),
                                unfocusedTextColor = Color(0xFF090502).copy(alpha = 0.98f),
                                cursorColor = amber.copy(alpha = 0.80f),
                                focusedContainerColor = Color(0xFF110B0D).copy(alpha = 0.06f),
                                unfocusedContainerColor = Color(0xFF110B0D).copy(alpha = 0.04f),
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 28.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD0BFA6).copy(alpha = 0.74f))) {
                                Text("cancel", fontSize = 20.sp, fontFamily = scriptFont, fontWeight = FontWeight.Normal)
                            }
                            TextButton(onClick = ::save, colors = ButtonDefaults.textButtonColors(contentColor = amber.copy(alpha = 0.92f))) {
                                Text("save", fontSize = 20.sp, fontFamily = scriptFont, fontWeight = FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class IssueEditorState(
    val repoId: String,
    val status: String,
    val id: String? = null,
    val body: String = "",
)
