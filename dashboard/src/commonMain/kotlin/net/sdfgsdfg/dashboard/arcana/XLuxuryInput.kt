package net.sdfgsdfg.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val xPillShape = RoundedCornerShape(50)
private val xWorkingCorona = Color(0xFF46125F)

internal fun TextFieldValue.xInsertNewline(): TextFieldValue {
    val start = min(selection.start, selection.end).coerceIn(0, text.length)
    val end = max(selection.start, selection.end).coerceIn(start, text.length)
    return copy(
        text = text.replaceRange(start, end, "\n"),
        selection = TextRange(start + 1),
        composition = null,
    )
}

@Composable
internal fun XLuxuryInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionColor: Color = Color.Transparent,
    enabled: Boolean = true,
    working: Boolean = false,
    mode: XComposerMode = XComposerMode.IDLE,
    onAudioToggle: () -> Unit = {},
    onSend: () -> Unit,
) {
    BoxWithConstraints(modifier.graphicsLayer { clip = false }) {
        val density = LocalDensity.current
        val bleed = 36.dp
        val textStyle = remember {
            TextStyle(
                color = Color(0xCC8A6534),
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                textAlign = TextAlign.Start,
            )
        }
        val targetActionWidth = when {
            mode == XComposerMode.AUDIO -> 84.dp
            actionLabel == null -> 64.dp
            actionLabel == "Quit" -> 88.dp
            actionLabel == "Continue" -> 116.dp
            else -> 150.dp
        }
        val actionWidth by animateDpAsState(
            targetActionWidth,
            spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.7f),
            label = "x-action-width",
        )
        val maxIslandWidth = minOf(maxWidth * 0.9f, maxWidth - targetActionWidth - bleed * 2).coerceAtLeast(220.dp)
        val maxIslandHeight = minOf(maxHeight * 0.9f, maxHeight - bleed * 2).coerceAtLeast(48.dp)
        val maxHorizontalTextInset = maxOf(24.dp, maxIslandWidth * 0.05f)
        val measureCap = (maxIslandWidth - maxHorizontalTextInset * 2).coerceAtLeast(120.dp)
        val textMeasurer = rememberTextMeasurer()
        val measured = remember(value.text, textStyle, measureCap, density) {
            textMeasurer.measure(
                text = AnnotatedString(value.text),
                style = textStyle,
                constraints = Constraints(maxWidth = with(density) { measureCap.roundToPx() }),
            )
        }
        val measuredSize = with(density) {
            DpSize(measured.size.width.toDp(), measured.size.height.toDp())
        }
        val targetSize = DpSize(
            width = maxOf(measuredSize.width + 96.dp, measuredSize.width / 0.9f).coerceIn(220.dp, maxIslandWidth),
            height = maxOf(measuredSize.height + 30.dp, measuredSize.height / 0.9f).coerceIn(48.dp, maxIslandHeight),
        )
        val islandSizeConverter = remember {
            TwoWayConverter<DpSize, AnimationVector2D>(
                convertToVector = { AnimationVector2D(it.width.value, it.height.value) },
                convertFromVector = { DpSize(it.v1.dp, it.v2.dp) },
            )
        }
        val islandSizeSpring = remember {
            spring(
                stiffness = Spring.StiffnessVeryLow * 0.7f,
                dampingRatio = 0.4f,
                visibilityThreshold = DpSize(0.4.dp, 0.4.dp),
            )
        }
        val islandSize by animateValueAsState(
            targetValue = targetSize,
            typeConverter = islandSizeConverter,
            animationSpec = islandSizeSpring,
            label = "x-island-size",
        )
        val textInset = DpSize(
            width = maxOf(24.dp, islandSize.width * 0.05f),
            height = maxOf(8.dp, islandSize.height * 0.05f),
        )

        val bubbleSize = DpSize(actionWidth, 48.dp)
        val splitOffset = 0.dp
        val groupHeight = maxOf(islandSize.height, bubbleSize.height)
        val shellSize = DpSize(
            islandSize.width + bubbleSize.width + splitOffset + bleed * 2,
            groupHeight + bleed * 2,
        )
        val islandY = bleed + (groupHeight - islandSize.height) / 2
        val bubbleY = bleed + (groupHeight - bubbleSize.height) / 2
        val islandGeometry = Modifier
            .offset(bleed, islandY)
            .size(islandSize)
        val liquidMotion = rememberXLiquidMotion(mode)
        val fluidAction = actionLabel == null || mode == XComposerMode.AUDIO
        val merging = mode == XComposerMode.IDLE
        XLiquidSoundEffect(separated = fluidAction && !merging)
        val liquidDeformation = remember(liquidMotion, fluidAction, merging) {
            derivedStateOf {
                xLiquidDeformation(
                    separation = liquidMotion.travel.value,
                    topology = liquidMotion.topology.value,
                    fluidAction = fluidAction,
                    merging = merging,
                )
            }
        }
        val actionGeometry = Modifier
            .offset {
                IntOffset(
                    x = xLiquidActionLeft(
                        bodyLeft = bleed.roundToPx().toFloat(),
                        bodyWidth = islandSize.width.roundToPx().toFloat(),
                        actionWidth = bubbleSize.width.roundToPx().toFloat(),
                        splitGap = splitOffset.roundToPx().toFloat(),
                        separation = liquidMotion.travel.value,
                    ).roundToInt(),
                    y = bubbleY.roundToPx(),
                )
            }
            .size(bubbleSize)
        val actionEnabled = enabled || mode == XComposerMode.AUDIO
        val liquidActionColor = when {
            mode == XComposerMode.AUDIO -> Color(0xFF3A1452)
            actionLabel != null -> actionColor
            else -> Color.Black
        }
        val workingBreath = if (working) {
            rememberInfiniteTransition(label = "x-working-breath").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(4_320, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "x-working-breath-progress",
            )
        } else {
            null
        }

        Box(
            Modifier
                .align(Alignment.Center)
                .size(shellSize),
        ) {
            workingBreath?.let { breath ->
                Box(
                    islandGeometry
                        .drawBehind {
                            val diffusion = breath.value
                            val radiusY = size.height / 2f + lerp(24f, 46f, diffusion).dp.toPx()
                            val radiusX = size.width / 2f + lerp(48f, 72f, diffusion).dp.toPx()
                            val energy = lerp(0.96f, 0.44f, diffusion)
                            val coronaCenter = Offset(center.x, center.y + 2.dp.toPx())
                            withTransform({ scale(radiusX / radiusY, 1f, coronaCenter) }) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        0f to Color(0xFF17051D).copy(alpha = energy * 0.86f),
                                        0.52f to xWorkingCorona.copy(alpha = energy * 0.80f),
                                        0.78f to xWorkingCorona.copy(alpha = energy * 0.38f),
                                        1f to Color.Transparent,
                                        center = coronaCenter,
                                        radius = radiusY,
                                    ),
                                    center = coronaCenter,
                                    radius = radiusY,
                                )
                            }
                        },
                )
            }
            XLiquidSurface(
                motion = liquidMotion,
                deformation = liquidDeformation,
                fluidAction = fluidAction,
                bodyLeft = bleed,
                bodyTop = islandY,
                bodySize = islandSize,
                bodyTargetSize = targetSize,
                actionTop = bubbleY,
                actionSize = bubbleSize,
                splitGap = splitOffset,
                actionMassScale = if (actionLabel == null && mode != XComposerMode.AUDIO) 0.90f else 1f,
                actionColor = liquidActionColor,
                actionTint = if (actionLabel != null || mode == XComposerMode.AUDIO) 1f else 0f,
                enabled = actionEnabled,
                merging = merging,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                actionGeometry
                    .graphicsLayer {
                        val deformation = liquidDeformation.value
                        alpha = if (actionLabel != null) 1f else xLiquidActionContentAlpha(liquidMotion.travel.value)
                        scaleX = deformation.scaleX
                        scaleY = deformation.scaleY
                    }
                    .clickable(enabled = actionEnabled && (actionLabel != null || mode != XComposerMode.IDLE)) {
                        if (mode == XComposerMode.AUDIO) onAudioToggle() else onSend()
                    },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = mode to actionLabel,
                    transitionSpec = { fadeIn(tween(260, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(170)) },
                    label = "x-action-label",
                ) { (contentMode, label) ->
                    when {
                        contentMode == XComposerMode.AUDIO -> Text(
                            "AUDIO",
                            color = Color(0xFFD3A5E8),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                        )
                        label == null -> XSendGlyph(Color.White.copy(alpha = if (enabled) 0.82f else 0.24f))
                        else -> Text(label, color = Color(0xFFEAD59A).copy(alpha = if (enabled) 0.96f else 0.38f), fontSize = 11.sp)
                    }
                }
            }
            XLuxuryCaretField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                focusRequester = focusRequester,
                textStyle = textStyle,
                textInset = textInset,
                fieldModifier = islandGeometry,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun XSendGlyph(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val d = min(size.width, size.height)
        drawPath(
            Path().apply {
                moveTo(0.05f * d, 0.85f * d)
                lineTo(0.95f * d, 0.50f * d)
                lineTo(0.05f * d, 0.15f * d)
                close()
            },
            tint,
        )
    }
}

/** Direct visual port of frontend-compose's lantern caret; continuous motion stays draw-scoped. */
private data class XCaretSpec(
    val color: Color = Color(0xAFB8FA10),
    val glowSoft: Color = Color(0x808A6534),
    val glowStrong: Color = Color(0xB80C0C05),
    val glowHighlight: Color = Color(0x4DF5B504),
    val widthDp: Float = 2f,
    val blinkMillis: Int = 2_150,
)

@Composable
private fun XLuxuryCaretField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    textStyle: TextStyle,
    textInset: DpSize,
    fieldModifier: Modifier,
    modifier: Modifier,
    caretSpec: XCaretSpec = XCaretSpec(),
) {
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    var textContentOrigin by remember { mutableStateOf(Offset.Zero) }

    val focusFade = updateTransition(focused, label = "x-caret-focus").animateFloat(
        transitionSpec = {
            if (targetState) tween(222, easing = FastOutSlowInEasing)
            else tween(666, easing = FastOutSlowInEasing)
        },
        label = "x-caret-focus-fade",
    ) { if (it) 1f else 0f }

    val osc = rememberInfiniteTransition(label = "x-caret-osc")
    val rawBlink = osc.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = caretSpec.blinkMillis
                1f at 0
                0.5f at caretSpec.blinkMillis / 2
                1f at caretSpec.blinkMillis
            },
        ),
        label = "x-caret-blink",
    )
    val rawGlow = osc.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 6_000
                0f at 0 using FastOutSlowInEasing
                1f at 1_067 using FastOutSlowInEasing
                0.96f at 2_000 using LinearEasing
                1f at 2_800 using LinearEasing
                0.98f at 3_067 using LinearEasing
                0f at 6_000 using LinearEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "x-caret-glow",
    )
    val density = LocalDensity.current
    val caretWidthPx = with(density) { caretSpec.widthDp.dp.toPx() }
    val minCaretHeightPx = with(density) { 18.dp.toPx() }
    val caretX = remember { Animatable(0f) }
    val caretY = remember { Animatable(0f) }
    val caretH = remember { Animatable(minCaretHeightPx) }
    val targetRect = remember(value.selection, layout) {
        layout?.let { current ->
            val index = value.selection.start.coerceIn(0, current.layoutInput.text.text.length)
            runCatching { current.getCursorRect(index) }.getOrNull()
        }
    }

    LaunchedEffect(focused, targetRect) {
        val target = targetRect ?: return@LaunchedEffect
        val height = max(target.height, minCaretHeightPx)
        if (!focused) {
            caretX.snapTo(target.left)
            caretY.snapTo(target.top)
            caretH.snapTo(height)
            return@LaunchedEffect
        }
        val dx = target.left - caretX.value
        val dy = target.top - caretY.value
        val t = (sqrt(dx * dx + dy * dy) / with(density) { 72.dp.toPx() }).coerceIn(0f, 1f)
        val stiffness = lerp(220f, 1_600f, t)
        val damping = lerp(0.90f, 0.70f, t)
        val motion = spring<Float>(stiffness = stiffness, dampingRatio = damping, visibilityThreshold = 0.5f)
        launch { caretX.animateTo(target.left, motion) }
        launch { caretY.animateTo(target.top, motion) }
        launch {
            caretH.animateTo(
                height,
                spring(stiffness = stiffness * 0.9f, dampingRatio = damping, visibilityThreshold = 0.5f),
            )
        }
    }

    Box(
        modifier
            .graphicsLayer { clip = false }
            .onGloballyPositioned { containerOrigin = it.positionInRoot() },
    ) {
        Box(
            fieldModifier,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = false,
                textStyle = textStyle,
                cursorBrush = SolidColor(Color.Transparent),
                onTextLayout = {
                    val constraints = it.layoutInput.constraints
                    val prepass = constraints.minWidth == 0 || constraints.minHeight == 0 || it.size.width == 0 || it.size.height == 0
                    if (!prepass) {
                        val same = layout?.size == it.size &&
                            layout?.layoutInput?.constraints == constraints &&
                            layout?.layoutInput?.text?.text == it.layoutInput.text.text
                        if (!same) layout = it
                    }
                },
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.J &&
                            event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed && !event.isMetaPressed
                        ) {
                            onValueChange(value.xInsertNewline())
                            true
                        } else {
                            false
                        }
                    }
                    .fillMaxSize()
                    .padding(horizontal = textInset.width, vertical = textInset.height)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) {
                            Text(
                                placeholder,
                                color = Color(0x99B4B4B4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 16.sp,
                                maxLines = 1,
                            )
                        }
                        Box(
                            Modifier.onGloballyPositioned {
                                textContentOrigin = it.positionInRoot() - containerOrigin
                            },
                        ) { inner() }
                    }
                },
            )
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = false },
        ) {
            val focus = focusFade.value
            val envelope = ((if (focus > 0.01f) rawBlink.value else 1f) * focus).coerceIn(0f, 1f)
            if (envelope <= 0.01f) return@Canvas
            val left = caretX.value + textContentOrigin.x
            val top = caretY.value + textContentOrigin.y
            val height = caretH.value
            val snappedLeft = round(left)
            val snappedTop = round(top)
            val snappedWidth = max(1f, round(caretWidthPx))
            drawRect(
                brush = Brush.verticalGradient(
                    0.05f to caretSpec.color.copy(alpha = 0.12f),
                    0.50f to caretSpec.color.copy(alpha = 0.55f),
                    0.95f to caretSpec.color.copy(alpha = 0.12f),
                ),
                topLeft = Offset(snappedLeft - 0.5f, snappedTop),
                size = Size(snappedWidth + 1f, height),
                blendMode = BlendMode.Screen,
                alpha = envelope,
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.18f to Color.Transparent,
                        0.82f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.35f),
                    ),
                    startY = top,
                    endY = top + height,
                ),
                topLeft = Offset(snappedLeft, top),
                size = Size(snappedWidth, height),
                blendMode = BlendMode.Multiply,
                alpha = envelope,
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.28f),
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.28f),
                ),
                topLeft = Offset(snappedLeft, top),
                size = Size(snappedWidth, height),
                blendMode = BlendMode.Multiply,
                alpha = 0.40f * envelope,
            )
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = false }
                .blur(3.25.dp, BlurredEdgeTreatment.Unbounded),
        ) {
            val focus = focusFade.value
            val envelope = ((if (focus > 0.01f) rawBlink.value else 1f) * focus).coerceIn(0f, 1f)
            if (envelope <= 0.01f) return@Canvas
            fun floatLerp(a: Float, b: Float, t: Float) = a + (b - a) * t
            fun tint(color: Color, red: Float, green: Float, blue: Float) = Color(
                red = (color.red * red).coerceIn(0f, 1f),
                green = (color.green * green).coerceIn(0f, 1f),
                blue = (color.blue * blue).coerceIn(0f, 1f),
                alpha = color.alpha,
            )
            val raw = (if (focus > 0.01f) rawGlow.value else 0f).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(raw)
            val knee1 = floatLerp(0.18f, 0.30f, eased)
            val knee2 = floatLerp(0.60f, 0.95f, eased)
            val verticalScale = 1.15f
            val crest = ((raw - 0.85f) / 0.15f).coerceIn(0f, 1f)
            val left = caretX.value + textContentOrigin.x
            val top = caretY.value + textContentOrigin.y
            val height = caretH.value
            fun stops(color: Color, core: Float, middle: Float, amplitude: Float) = arrayOf(
                0f to color.copy(alpha = core * amplitude),
                knee1 to color.copy(alpha = middle * amplitude),
                (knee2 - 0.035f).coerceIn(0f, 1f) to color.copy(alpha = middle * 0.08f * amplitude),
                knee2 to Color.Transparent,
            )
            fun drawGlowCapsule(
                basePad: Float,
                growPad: Float,
                color: Color,
                core: Float,
                middle: Float,
                amplitude: Float,
                dx: Float = 0f,
                dy: Float = 0f,
                vScale: Float = verticalScale,
            ) {
                val pad = (basePad + growPad * eased) * focus
                val width = caretWidthPx + pad * 2f
                val glowHeight = height + pad * 2f * vScale
                val radius = min(width, glowHeight) / 2f
                val gradientRadius = 0.5f * sqrt(width * width + glowHeight * glowHeight) * 0.90f
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colorStops = stops(color, core, middle, amplitude),
                        center = Offset(left + caretWidthPx / 2f + dx, top + height / 2f + dy),
                        radius = gradientRadius,
                    ),
                    topLeft = Offset(left - pad + dx, top - pad * vScale + dy),
                    size = Size(width, glowHeight),
                    cornerRadius = CornerRadius(radius, radius),
                    blendMode = BlendMode.Plus,
                )
            }
            drawGlowCapsule(2.dp.toPx(), 5.dp.toPx(), caretSpec.glowSoft, 0.32f, 0.20f, envelope)
            drawGlowCapsule(6.dp.toPx(), 10.dp.toPx(), caretSpec.glowStrong, 0.26f, 0.14f, envelope)
            if (raw > 0.85f) {
                val crestEase = FastOutSlowInEasing.transform(crest)
                val crestAmplitude = floatLerp(1f, 1.90f, crestEase)
                val crestGrowth = floatLerp(1f, 2.20f, crestEase)
                drawGlowCapsule(
                    9.dp.toPx(),
                    9.dp.toPx() * crest * crestGrowth,
                    caretSpec.glowHighlight,
                    0.32f * crest * crestAmplitude,
                    0.12f * crest * crestAmplitude,
                    envelope,
                    vScale = verticalScale + 0.06f * crestEase,
                )
                val fringeAlpha = 0.07f * envelope * crest
                val fringeOffset = (0.6f * crest).dp.toPx()
                drawGlowCapsule(9.dp.toPx(), 9.dp.toPx() * crest, tint(caretSpec.glowHighlight, 1.20f, 0.97f, 0.92f), 0.14f * crest, 0.06f * crest, fringeAlpha, dx = fringeOffset)
                drawGlowCapsule(9.dp.toPx(), 9.dp.toPx() * crest, tint(caretSpec.glowHighlight, 0.92f, 0.97f, 1.20f), 0.14f * crest, 0.06f * crest, fringeAlpha, dx = -fringeOffset)
            }
        }

        Box(
            Modifier
                .offset {
                    IntOffset(
                        (caretX.value + textContentOrigin.x).roundToInt(),
                        (caretY.value + textContentOrigin.y).roundToInt(),
                    )
                }
                .layout { measurable, constraints ->
                    val width = max(1, caretWidthPx.roundToInt()).coerceIn(constraints.minWidth, constraints.maxWidth)
                    val height = max(1, caretH.value.roundToInt()).coerceIn(constraints.minHeight, constraints.maxHeight)
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { placeable.place(0, 0) }
                }
                .clip(xPillShape)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .innerShadow(xPillShape) {
                    val focus = focusFade.value
                    val envelope = ((if (focus > 0.01f) rawBlink.value else 1f) * focus).coerceIn(0f, 1f)
                    val progress = FastOutSlowInEasing.transform(
                        if (focus > 0.01f) rawGlow.value.coerceIn(0f, 1f) else 0f,
                    )
                    radius = with(density) { androidx.compose.ui.unit.lerp(2.dp, 8.dp, progress).toPx() }
                    spread = 2f
                    color = Color.Black
                    alpha = (0.94f - 0.42f * progress) * envelope
                    blendMode = BlendMode.Multiply
                    offset = Offset.Zero
                }
                .innerShadow(xPillShape) {
                    val focus = focusFade.value
                    val envelope = ((if (focus > 0.01f) rawBlink.value else 1f) * focus).coerceIn(0f, 1f)
                    val progress = FastOutSlowInEasing.transform(
                        if (focus > 0.01f) rawGlow.value.coerceIn(0f, 1f) else 0f,
                    )
                    radius = with(density) { androidx.compose.ui.unit.lerp(1.dp, 12.dp, progress).toPx() }
                    spread = lerp(1f, 14f, progress)
                    color = caretSpec.color
                    alpha = envelope * lerp(0.06f, 0.22f, progress)
                    blendMode = BlendMode.Multiply
                    offset = Offset.Zero
                }
                .innerShadow(xPillShape) {
                    val focus = focusFade.value
                    val progress = FastOutSlowInEasing.transform(
                        if (focus > 0.01f) rawGlow.value.coerceIn(0f, 1f) else 0f,
                    )
                    radius = with(density) { androidx.compose.ui.unit.lerp(1.dp, 4.dp, progress).toPx() }
                    spread = lerp(0f, 4f, progress)
                    color = Color.Black
                    alpha = if (focus > 0.01f) lerp(0.01f, 0.16f, progress) else 0f
                    blendMode = BlendMode.Multiply
                    offset = Offset.Zero
                },
        )
    }
}
