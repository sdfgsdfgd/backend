package net.sdfgsdfg.dashboard.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import net.sdfgsdfg.dashboard.DashboardApp
import net.sdfgsdfg.dashboard.OpsGithubAuthWindowState
import net.sdfgsdfg.dashboard.closeOpsTransport
import net.sdfgsdfg.dashboard.dismissOpsGithubAuthWindow
import net.sdfgsdfg.dashboard.opsGithubAuthWindowState
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.Window as AwtWindow
import kotlin.math.roundToInt

fun main() = try {
    application(exitProcessOnExit = false) {
        val authState by opsGithubAuthWindowState.collectAsState()
        Window(
            onCloseRequest = ::exitApplication,
            title = "Trio Ops Cockpit",
            state = WindowState(
                position = WindowPosition.Aligned(Alignment.Center),
                size = desktopInitialSize(),
            ),
        ) {
            DashboardApp()
        }
        authState?.let { state ->
            Window(
                onCloseRequest = ::dismissOpsGithubAuthWindow,
                title = "GitHub Login",
                state = WindowState(
                    position = WindowPosition.Aligned(Alignment.Center),
                    size = DpSize(520.dp, 310.dp),
                ),
                transparent = true,
                undecorated = true,
                resizable = false,
                alwaysOnTop = true,
            ) {
                val awtWindow = window
                GitHubAuthWindow(
                    state,
                    Modifier
                        .dragWindow(awtWindow)
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                                dismissOpsGithubAuthWindow()
                                true
                            } else {
                                false
                            }
                        },
                )
            }
        }
    }
} finally {
    closeOpsTransport()
}

private fun desktopInitialSize(): DpSize {
    val screen = Toolkit.getDefaultToolkit().screenSize
    return DpSize((screen.width * 0.9).roundToInt().dp, (screen.height * 0.9).roundToInt().dp)
}

private fun Modifier.dragWindow(window: AwtWindow) = pointerInput(window) {
    var startMouse: Point? = null
    var startWindow = Point()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val mouse = MouseInfo.getPointerInfo()?.location
            val pressed = event.buttons.isPrimaryPressed
            val justPressed = event.changes.firstOrNull()?.let { !it.previousPressed && it.pressed } == true
            if (pressed && justPressed && mouse != null) {
                startMouse = mouse
                startWindow = window.location
            }
            if (!pressed || mouse == null) {
                startMouse = null
                continue
            }
            val origin = startMouse
            if (event.type == PointerEventType.Move && origin != null) {
                window.setLocation(
                    startWindow.x + mouse.x - origin.x,
                    startWindow.y + mouse.y - origin.y,
                )
            }
        }
    }
}

@Composable
private fun GitHubAuthWindow(state: OpsGithubAuthWindowState, modifier: Modifier = Modifier) {
    val tone = if (state.success) Color(0xFF5CFF95) else if (state.terminal) Color(0xFFFFC86B) else Color(0xFF75D4FF)
    Box(
        modifier
            .fillMaxSize()
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xF2131D28),
                            Color(0xEA060A11),
                            Color(0xF0160711),
                        )
                    )
                )
                .border(BorderStroke(1.dp, tone.copy(alpha = 0.50f)), RoundedCornerShape(32.dp)),
        ) {
            GitHubAuthTexture(tone)
            GitHubAuthCloseButton(tone, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 24.dp))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        GitHubAuthMark(tone, state)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            BasicText(state.title, style = TextStyle(color = Color(0xEEF5FAFF), fontSize = 26.sp, fontWeight = FontWeight.Bold))
                            BasicText(state.status, style = TextStyle(color = tone.copy(alpha = 0.78f), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                    state.code?.let { code ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(
                                code,
                                style = TextStyle(
                                    color = Color(0xF0F5FFF9),
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.6.sp,
                                ),
                                maxLines = 1,
                            )
                            GitHubAuthCopyButton(tone, code)
                        }
                    }
                    BasicText(state.detail, style = TextStyle(color = Color(0xAFC5D2E1), fontSize = 14.sp, lineHeight = 19.sp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GitHubAuthProgress(tone, running = !state.terminal)
                    if (!state.terminal) {
                        GitHubAuthCancelButton(tone)
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHubAuthMark(tone: Color, state: OpsGithubAuthWindowState) {
    val transition = rememberInfiniteTransition(label = "github-auth-window-mark")
    val pulse by transition.animateFloat(
        initialValue = 0.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "github-auth-window-pulse",
    )
    Canvas(Modifier.size(64.dp)) {
        drawCircle(tone.copy(alpha = 0.08f + pulse * 0.08f), radius = size.minDimension * 0.48f)
        drawCircle(tone.copy(alpha = 0.90f), radius = size.minDimension * 0.32f)
        if (state.success) {
            drawLine(Color(0xFF07111D), Offset(size.width * 0.36f, size.height * 0.51f), Offset(size.width * 0.47f, size.height * 0.63f), strokeWidth = 4.5f)
            drawLine(Color(0xFF07111D), Offset(size.width * 0.47f, size.height * 0.63f), Offset(size.width * 0.68f, size.height * 0.38f), strokeWidth = 4.5f)
        } else {
            drawCircle(Color(0xFF07111D), radius = size.minDimension * 0.08f, center = center)
        }
    }
}

@Composable
private fun GitHubAuthCloseButton(tone: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x86101722))
            .border(BorderStroke(1.dp, tone.copy(alpha = 0.34f)), RoundedCornerShape(999.dp))
            .clickable { dismissOpsGithubAuthWindow() },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("x", style = TextStyle(color = Color(0xBDF0FFF6), fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun GitHubAuthCancelButton(tone: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x65101722))
            .border(BorderStroke(1.dp, tone.copy(alpha = 0.26f)), RoundedCornerShape(999.dp))
            .clickable { dismissOpsGithubAuthWindow() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("Cancel login", style = TextStyle(color = Color(0x99D7E3EF), fontSize = 11.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun GitHubAuthCopyButton(tone: Color, code: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x86101722))
            .border(BorderStroke(1.dp, tone.copy(alpha = 0.42f)), RoundedCornerShape(999.dp))
            .clickable { copyToClipboard(code) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("Copy", style = TextStyle(color = Color(0xDDF0FFF6), fontSize = 12.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun GitHubAuthProgress(tone: Color, running: Boolean) {
    val transition = rememberInfiniteTransition(label = "github-auth-window-progress")
    val x by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart),
        label = "github-auth-window-sweep",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.08f)),
    ) {
        if (running) {
            Box(
                Modifier
                    .offset(x = (420.dp * x))
                    .width(150.dp)
                    .height(3.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, tone, Color.Transparent))),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tone.copy(alpha = 0.70f)),
            )
        }
    }
}

@Composable
private fun GitHubAuthTexture(tone: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(tone.copy(alpha = 0.12f), radius = size.width * 0.42f, center = Offset(-size.width * 0.02f, size.height * 1.18f))
        drawCircle(Color(0xFFFF477E).copy(alpha = 0.08f), radius = size.width * 0.30f, center = Offset(size.width * 1.02f, -size.height * 0.12f))
        var x = -size.width * 0.10f
        while (x < size.width) {
            drawLine(
                color = Color.White.copy(alpha = 0.028f),
                start = Offset(x, size.height),
                end = Offset(x + size.height * 1.55f, 0f),
                strokeWidth = 0.8f,
            )
            x += 42f
        }
        drawRoundRect(
            color = Color.White.copy(alpha = 0.06f),
            topLeft = Offset(1.2f, 1.2f),
            size = androidx.compose.ui.geometry.Size(size.width - 2.4f, size.height - 2.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(32.dp.toPx()),
            style = Stroke(width = 1f),
        )
    }
}

private fun copyToClipboard(value: String) =
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }.isSuccess
