package net.sdfgsdfg.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Compose/Skia boundary for the selector. The top bar supplies spring bodies;
 * one RuntimeEffect owns both implicit liquid geometry and optical material.
 * Animated reads remain inside Canvas, invalidating only this draw layer.
 */
@Composable
internal fun LiquidNavigationLayer(
    state: LiquidNavigationState,
    modifier: Modifier = Modifier,
    restHeight: Dp = 44.dp,
    material: LiquidNavigationMaterial = LiquidNavigationMaterial(),
) {
    val effect = remember { RuntimeEffect.makeForShader(LIQUID_NAVIGATION_SHADER) }
    val builder = remember(effect) { RuntimeShaderBuilder(effect) }
    val paint = remember { Paint() }

    DisposableEffect(effect, builder) {
        onDispose {
            paint.shader = null
            builder.close()
            effect.close()
        }
    }

    Canvas(modifier) {
        val phase = state.timeline.value.coerceIn(0f, 1f)
        val alpha = material.opacity.coerceIn(0f, 1f)

        val height = restHeight.toPx().coerceAtLeast(1f)
        val headX = state.head.value.toPx()
        val tailX = state.reservoir.value.toPx()
        val directedVelocity = state.travelDirection *
            (state.head.velocity.toPx() - state.reservoir.velocity.toPx()) / height
        val warmth = mix(
            state.departureWarmth,
            state.destinationWarmth,
            smoothStep(phase),
        )

        builder.uniform("motion", headX, tailX, phase, state.travelDirection)
        builder.uniform(
            "dimensions",
            state.departureWidth.toPx().coerceAtLeast(height),
            state.destinationWidth.toPx().coerceAtLeast(height),
            height,
            size.height / 2f,
        )
        builder.uniform(
            "dynamics",
            directedVelocity,
            state.destinationCenter.toPx(),
            alpha,
            warmth,
        )
        builder.colorUniform("coolRim", material.coolRim)
        builder.colorUniform("warmRim", material.warmRim)
        builder.colorUniform("glassTop", material.glassTop)
        builder.colorUniform("glassMiddle", material.glassMiddle)
        builder.colorUniform("glassBottom", material.glassBottom)

        val shader = builder.makeShader()
        paint.shader = shader.asComposeShader()
        drawContext.canvas.drawRect(Rect(0f, 0f, size.width, size.height), paint)
        paint.shader = null
        shader.close()
    }
}

private fun RuntimeShaderBuilder.colorUniform(name: String, color: Color) {
    uniform(name, color.red, color.green, color.blue, color.alpha)
}

private fun smoothStep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

private fun mix(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction.coerceIn(0f, 1f)
