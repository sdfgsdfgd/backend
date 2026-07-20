package net.sdfgsdfg.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The composer's complete visual state machine. Session actions remain orthogonal. */
internal enum class XComposerMode {
    /** No Unicode letter or digit: the action droplet rests inside the composer. */
    IDLE,

    /** Meaningful typed or pasted text: the action droplet separates for submission. */
    TEXT,

    /** Explicit Ctrl-Space override: the preserved draft yields to future audio input. */
    AUDIO,
}

internal fun xComposerMode(text: String, audioActive: Boolean): XComposerMode = when {
    audioActive -> XComposerMode.AUDIO
    text.any(Char::isLetterOrDigit) -> XComposerMode.TEXT
    else -> XComposerMode.IDLE
}

internal fun xLiquidActionLeft(
    bodyLeft: Float,
    bodyWidth: Float,
    actionWidth: Float,
    splitGap: Float,
    separation: Float,
): Float {
    val unit = separation.coerceIn(0f, 1f)
    val overshoot = separation - unit
    val elasticOvershoot = overshoot / (1f + abs(overshoot) / 0.16f)
    val loadedUnit = unit * lerp(0.88f, 1f, unit)
    val viscousTravel = loadedUnit * loadedUnit * (3f - 2f * loadedUnit)
    val travel = viscousTravel + elasticOvershoot * 0.65f
    return lerp(
        start = bodyLeft + bodyWidth - actionWidth,
        stop = bodyLeft + bodyWidth + splitGap,
        fraction = travel,
    )
}

/** Content emerges only after the action mass is visibly outside the reservoir. */
internal fun xLiquidActionContentAlpha(separation: Float): Float {
    val unit = ((separation - 0.56f) / 0.20f).coerceIn(0f, 1f)
    return unit * unit * (3f - 2f * unit)
}

internal fun xLiquidProfileExponent(
    aspect: Float,
    tension: Float,
    eigenmode: Float,
): Float {
    val elongation = ((aspect - 1.08f) / 0.47f).coerceIn(0f, 1f)
    val resting = lerp(2f, 3.4f, elongation)
    val pressure = maxOf(tension, abs(eigenmode)).coerceIn(0f, 1f)
    return lerp(resting, 2.2f, elongation * pressure * 0.42f)
}

internal fun xLiquidResizeStrain(current: Float, target: Float): Float {
    val relative = (target - current) / maxOf(abs(current), abs(target), 1f)
    return relative / (1f + abs(relative) / 0.18f)
}

@Immutable
internal data class XLiquidDeformation(
    val scaleX: Float,
    val scaleY: Float,
    val tension: Float,
    val eigenmode: Float,
)

internal fun xLiquidDeformation(
    separation: Float,
    topology: Float,
    fluidAction: Boolean,
    merging: Boolean,
): XLiquidDeformation {
    if (!fluidAction) return XLiquidDeformation(1f, 1f, 0f, 0f)

    val unit = separation.coerceIn(0f, 1f)
    val tension = sin(PI.toFloat() * unit)
    val releaseUnit = ((topology.coerceIn(0f, 1f) - 0.70f) / 0.24f).coerceIn(0f, 1f)
    val released = releaseUnit * releaseUnit * (3f - 2f * releaseUnit)
    val displacement = (separation - 1f).let { it / (1f + abs(it) / 0.18f) } / 0.18f
    val eigenmode = if (merging) 0f else released * displacement
    val stretch = (1f + tension * 0.42f - eigenmode * 0.42f).coerceIn(0.66f, 1.58f)
    val conservation = 1f - tension * 0.06f
    return XLiquidDeformation(
        scaleX = stretch * conservation,
        scaleY = conservation / stretch,
        tension = tension,
        eigenmode = eigenmode,
    )
}

@Stable
internal class XLiquidMotion internal constructor(
    internal val travel: State<Float>,
    internal val topology: State<Float>,
)

@Composable
internal fun rememberXLiquidMotion(mode: XComposerMode): XLiquidMotion {
    val transition = updateTransition(mode, label = "x-liquid-mode")
    val travel = transition.animateFloat(
        transitionSpec = {
            spring(
                stiffness = Spring.StiffnessVeryLow * 0.55f,
                dampingRatio = 0.26f,
                visibilityThreshold = 0.001f,
            )
        },
        label = "x-liquid-travel",
    ) { if (it == XComposerMode.IDLE) 0f else 1f }
    val topology = transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (targetState == XComposerMode.IDLE) 360 else 420,
                easing = LinearEasing,
            )
        },
        label = "x-liquid-topology",
    ) { if (it == XComposerMode.IDLE) 0f else 1f }
    return remember(travel, topology) { XLiquidMotion(travel, topology) }
}

@Composable
internal fun XLiquidSurface(
    motion: XLiquidMotion,
    deformation: State<XLiquidDeformation>,
    fluidAction: Boolean,
    bodyLeft: Dp,
    bodyTop: Dp,
    bodySize: DpSize,
    bodyTargetSize: DpSize,
    actionTop: Dp,
    actionSize: DpSize,
    splitGap: Dp,
    actionMassScale: Float,
    actionColor: Color,
    actionTint: Float,
    enabled: Boolean,
    merging: Boolean,
    modifier: Modifier = Modifier,
) {
    val effect = remember(X_LIQUID_SHADER) { RuntimeEffect.makeForShader(X_LIQUID_SHADER) }
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
        val bodyLeftPx = bodyLeft.toPx()
        val bodyTopPx = bodyTop.toPx()
        val bodyWidthPx = bodySize.width.toPx()
        val bodyHeightPx = bodySize.height.toPx()
        val bodyStrainX = xLiquidResizeStrain(bodyWidthPx, bodyTargetSize.width.toPx())
        val bodyStrainY = xLiquidResizeStrain(bodyHeightPx, bodyTargetSize.height.toPx())
        val actionWidthPx = actionSize.width.toPx()
        val actionHeightPx = actionSize.height.toPx()
        val massScale = actionMassScale.coerceIn(0.5f, 1f)
        val massHeightPx = actionHeightPx * massScale
        val massWidthPx = if (massScale < 0.999f) massHeightPx else actionWidthPx
        val progress = motion.travel.value
        val shape = deformation.value
        val profileExponent = xLiquidProfileExponent(
            aspect = massWidthPx / massHeightPx,
            tension = shape.tension,
            eigenmode = shape.eigenmode,
        )
        val actionLeftPx = xLiquidActionLeft(
            bodyLeftPx,
            bodyWidthPx,
            actionWidthPx,
            splitGap.toPx(),
            progress,
        )
        builder.uniform("bodyCenter", bodyLeftPx + bodyWidthPx / 2f, bodyTopPx + bodyHeightPx / 2f)
        builder.uniform("bodyHalfSize", bodyWidthPx / 2f, bodyHeightPx / 2f)
        builder.uniform("bodyStrain", bodyStrainX, bodyStrainY)
        builder.uniform("actionCenter", actionLeftPx + actionWidthPx / 2f, actionTop.toPx() + actionHeightPx / 2f)
        builder.uniform("actionHalfSize", massWidthPx / 2f, massHeightPx / 2f)
        builder.uniform("actionScale", shape.scaleX, shape.scaleY)
        builder.uniform("actionExponent", profileExponent)
        builder.uniform("fluid", if (fluidAction) 1f else 0f)
        builder.uniform("unionRadius", massHeightPx * 0.10f)
        builder.uniform("separation", progress)
        builder.uniform("topology", motion.topology.value)
        builder.uniform("tension", shape.tension)
        builder.uniform("capillaryEigenmode", shape.eigenmode)
        builder.uniform("actionColor", actionColor.red, actionColor.green, actionColor.blue, actionColor.alpha)
        builder.uniform("actionTint", actionTint.coerceIn(0f, 1f) * lerp(0.18f, 1f, progress))
        builder.uniform("enabled", if (enabled) 1f else 0f)
        builder.uniform("merging", if (merging) 1f else 0f)

        val shader = builder.makeShader()
        paint.shader = shader.asComposeShader()
        drawContext.canvas.drawRect(Rect(0f, 0f, size.width, size.height), paint)
        paint.shader = null
        shader.close()
    }
}

internal const val X_LIQUID_SHADER = """
    uniform float2 bodyCenter;
    uniform float2 bodyHalfSize;
    uniform float2 bodyStrain;
    uniform float2 actionCenter;
    uniform float2 actionHalfSize;
    uniform float2 actionScale;
    uniform float actionExponent;
    uniform float fluid;
    uniform float unionRadius;
    uniform float separation;
    uniform float topology;
    uniform float tension;
    uniform float capillaryEigenmode;
    uniform float4 actionColor;
    uniform float actionTint;
    uniform float enabled;
    uniform float merging;

    float roundedBox(float2 point, float2 halfSize, float radius) {
        float2 q = abs(point) - halfSize + radius;
        return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
    }

    float smoothUnion(float first, float second, float radius) {
        float h = clamp(0.5 + 0.5 * (second - first) / radius, 0.0, 1.0);
        return mix(second, first, h) - radius * h * (1.0 - h);
    }

    float mergeRecoilAt() {
        return merging * smoothstep(0.0, 0.20, max(-separation, 0.0));
    }

    float2 actionHalfSizeAt() {
        return actionHalfSize * actionScale;
    }

    // Polynomial smooth-union is the analytic equivalent of a thresholded
    // metaball field. Unlike the old tapered box, it cannot produce a wedge:
    // the ligament is derived from the same continuous distance field as both
    // oil masses, then narrows naturally as surface tension releases it.
    float blendRadiusAt() {
        float phase = clamp(topology, 0.0, 1.0);
        float extrusion = smoothstep(0.04, 0.30, phase);
        float releasePhase = smoothstep(0.72, 1.0, phase);
        float release = pow(releasePhase, 1.6);
        float adhesion = mix(0.12, 1.32, extrusion);
        float splitBlend = mix(adhesion, 0.44, release);
        float seal = smoothstep(0.0, 0.24, 1.0 - phase);
        float mergeBlend = mix(0.44, adhesion, seal);
        return actionHalfSize.y * mix(splitBlend, mergeBlend, merging);
    }

    float bodyDistanceAt(float2 point) {
        float localY = point.y - bodyCenter.y;
        float shoulder = smoothstep(
            bodyCenter.x + bodyHalfSize.x - bodyHalfSize.y * 1.55,
            bodyCenter.x + bodyHalfSize.x + bodyHalfSize.y * 0.20,
            point.x
        );
        float vertical = clamp(
            abs(point.y - bodyCenter.y) / bodyHalfSize.y,
            0.0,
            1.0
        );
        float vertical2 = vertical * vertical;
        float envelope = 1.0 - vertical2;
        float fundamentalMode = envelope * envelope * envelope;
        float capillaryMode = fundamentalMode * (1.0 - 9.0 * vertical2);
        float membraneMode = mix(fundamentalMode, capillaryMode, 0.32);
        float axialPressure =
            tension * 0.30 -
            capillaryEigenmode * 0.42 +
            mergeRecoilAt() * 0.12 +
            bodyStrain.x * 0.90 -
            bodyStrain.y * 0.22;
        float radialPressure =
            bodyStrain.y * 0.72 -
            bodyStrain.x * 0.12;
        float axialDisplacement =
            bodyHalfSize.y * axialPressure * membraneMode;
        float radialMode = vertical * (2.0 - vertical);
        float radialDisplacement =
            bodyHalfSize.y * radialPressure * radialMode;
        float2 warpedPoint = point - float2(
            shoulder * axialDisplacement,
            sign(localY) * radialDisplacement
        );
        return roundedBox(
            warpedPoint - bodyCenter,
            bodyHalfSize,
            bodyHalfSize.y * 0.55
        );
    }

    float actionDistanceAt(float2 point) {
        float2 halfSize = actionHalfSizeAt();
        float radius = min(halfSize.x, halfSize.y) * 0.98;
        float2 local = point - actionCenter;
        float capsule = roundedBox(local, halfSize, radius);
        float2 normalized = abs(local / max(halfSize, float2(0.001)));
        float bubble = (
            pow(
                pow(normalized.x, actionExponent) + pow(normalized.y, actionExponent),
                1.0 / actionExponent
            ) - 1.0
        ) * min(halfSize.x, halfSize.y);
        return mix(capsule, bubble, fluid);
    }

    float liquidDistance(float2 point) {
        return smoothUnion(
            bodyDistanceAt(point),
            actionDistanceAt(point),
            max(blendRadiusAt(), unionRadius)
        );
    }

    half4 main(float2 point) {
        float bodyDistance = bodyDistanceAt(point);
        float actionDistance = actionDistanceAt(point);
        float blendRadius = blendRadiusAt();
        float distance = smoothUnion(
            bodyDistance,
            actionDistance,
            max(blendRadius, unionRadius)
        );
        float antialias = 1.25;
        float coverage = 1.0 - smoothstep(-antialias, antialias, distance);
        if (coverage <= 0.0) return half4(0.0);
        float weightBlendRadius = max(blendRadius, 1.0);
        float actionWeight = clamp(0.5 + 0.5 * (bodyDistance - actionDistance) / weightBlendRadius, 0.0, 1.0);
        float resizeMagnitude = length(bodyStrain);
        float resizeMotion = resizeMagnitude / (resizeMagnitude + 0.08);
        float strain = max(tension, resizeMotion * 0.45);
        float capillaryMotion = abs(capillaryEigenmode);
        float mergeRecoil = mergeRecoilAt();
        float shoulderDistance = abs(point.x - (bodyCenter.x + bodyHalfSize.x));
        float shoulderWeight = 1.0 - smoothstep(0.0, actionHalfSize.x * 1.65, shoulderDistance);
        float ligamentWeight = strain * shoulderWeight;
        float liquidWeight = max(actionWeight, ligamentWeight);

        float available = mix(0.40, 1.0, enabled);
        float3 oilDepth = float3(0.015, 0.003, 0.021) * liquidWeight;
        float3 tensionTint = float3(0.054, 0.010, 0.075) * ligamentWeight;
        float3 surface = oilDepth + tensionTint + actionColor.rgb * available * actionWeight * actionTint * actionColor.a;
        float capillaryFocus = clamp(
            ligamentWeight +
            actionWeight * capillaryMotion * 0.55 +
            shoulderWeight * mergeRecoil * 0.35,
            0.0,
            1.0
        );
        float rimWidth = mix(1.20, 0.70, capillaryFocus);
        float rim = (1.0 - smoothstep(0.0, rimWidth, abs(distance))) * coverage;
        float epsilon = 1.0;
        float2 normal = normalize(float2(
            liquidDistance(point + float2(epsilon, 0.0)) - liquidDistance(point - float2(epsilon, 0.0)),
            liquidDistance(point + float2(0.0, epsilon)) - liquidDistance(point - float2(0.0, epsilon))
        ) + float2(0.0001));
        float light = clamp(dot(normal, normalize(float2(-0.58, -0.82))) * 0.5 + 0.5, 0.0, 1.0);
        float grazing = pow(1.0 - abs(normal.x), 2.0);
        float rimAlpha = mix(0.048, 0.30 * available, liquidWeight) * mix(0.50, 1.0, light);
        float3 ritualRim = mix(float3(0.30, 0.13, 0.40), max(actionColor.rgb, float3(0.24)), actionTint);
        float3 rimColor = mix(float3(1.0), ritualRim, liquidWeight * 0.88);
        float focusedGrazing = pow(grazing, 1.35);
        float meniscusSheen = rim * focusedGrazing * capillaryFocus *
            (0.18 + max(strain, capillaryMotion) * 0.26) * mix(0.42, 1.0, light);
        float3 meniscusColor = mix(
            float3(0.46, 0.16, 0.62),
            float3(0.86, 0.70, 0.94),
            pow(light, 2.2)
        );
        float3 color = surface + rimColor * rim * rimAlpha +
            meniscusColor * meniscusSheen;
        return half4(half3(color * coverage), half(coverage));
    }
"""
