package net.sdfgsdfg.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Horizontal liquid navigation state. The leading drop and rear reservoir are
 * independent spring bodies; [timeline] sequences transfer without owning shape.
 *
 * Retargeting keeps both live spring positions. Use [settleAt] for non-visual state
 * invalidation (for example, permission revocation where an anchor disappears).
 */
@Stable
internal class LiquidNavigationState(
    initialCenter: Dp,
    initialWidth: Dp,
    initialWarmth: Float = 0f,
) {
    internal val head = Animatable(initialCenter, Dp.VectorConverter)
    internal val reservoir = Animatable(initialCenter, Dp.VectorConverter)
    internal val timeline = Animatable(1f)

    internal var departureWidth by mutableStateOf(initialWidth)
        private set
    internal var destinationWidth by mutableStateOf(initialWidth)
        private set
    internal var departureWarmth by mutableStateOf(initialWarmth.coerceIn(0f, 1f))
        private set
    internal var destinationWarmth by mutableStateOf(initialWarmth.coerceIn(0f, 1f))
        private set

    internal var destinationCenter = initialCenter
        private set
    internal var travelDirection = 1f
        private set

    val headCenter: Dp get() = head.value
    val phase: Float get() = timeline.value

    /**
     * Preserves both live body positions on interruption. A linear timeline
     * avoids double-easing; the springs supply physical acceleration and recoil.
     */
    suspend fun moveTo(
        center: Dp,
        width: Dp,
        warmth: Float = 0f,
    ) {
        val currentPhase = timeline.value.coerceIn(0f, 1f)
        val currentShapePhase = smootherStep(currentPhase)
        val currentWidth = mix(departureWidth, destinationWidth, currentShapePhase)
        val currentWarmth = mix(departureWarmth, destinationWarmth, currentShapePhase)
        if (
            currentPhase >= 0.999f &&
            center == destinationCenter &&
            width == destinationWidth &&
            warmth.coerceIn(0f, 1f) == destinationWarmth
        ) return

        departureWidth = currentWidth
        destinationWidth = width
        departureWarmth = currentWarmth
        destinationWarmth = warmth.coerceIn(0f, 1f)
        val liveCenter = mix(reservoir.value, head.value, 0.62f)
        val durationMillis = (520f + abs(center.value - liveCenter.value) * 2.2f)
            .toInt()
            .coerceIn(540, 780)
        val direction = (center.value - liveCenter.value).sign
        if (direction != 0f) travelDirection = direction
        destinationCenter = center
        timeline.snapTo(0f)

        coroutineScope {
            val headMotion = launch {
                head.animateTo(
                    targetValue = center,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = 145f,
                        visibilityThreshold = 0.05.dp,
                    ),
                )
            }
            val reservoirMotion = launch {
                reservoir.animateTo(
                    targetValue = center,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = 35f,
                        visibilityThreshold = 0.05.dp,
                    ),
                )
            }
            timeline.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = durationMillis, easing = LinearEasing),
            )
            headMotion.join()
            reservoirMotion.join()
        }
    }

    /** Immediately restores a valid single-lobe state without animating stale UI. */
    suspend fun settleAt(
        center: Dp,
        width: Dp,
        warmth: Float = 0f,
    ) {
        val safeWarmth = warmth.coerceIn(0f, 1f)
        head.stop()
        reservoir.stop()
        timeline.stop()
        head.snapTo(center)
        reservoir.snapTo(center)
        departureWidth = width
        destinationWidth = width
        departureWarmth = safeWarmth
        destinationWarmth = safeWarmth
        destinationCenter = center
        timeline.snapTo(1f)
    }
}

@Composable
internal fun rememberLiquidNavigationState(
    initialCenter: Dp,
    initialWidth: Dp,
    initialWarmth: Float = 0f,
): LiquidNavigationState = remember {
    LiquidNavigationState(initialCenter, initialWidth, initialWarmth)
}

private fun mix(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction.coerceIn(0f, 1f)

private fun mix(start: Dp, end: Dp, fraction: Float): Dp =
    start + (end - start) * fraction.coerceIn(0f, 1f)

private fun smootherStep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}
