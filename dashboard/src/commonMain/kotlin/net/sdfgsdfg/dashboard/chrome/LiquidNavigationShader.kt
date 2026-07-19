package net.sdfgsdfg.dashboard

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
internal data class LiquidNavigationMaterial(
    val coolRim: Color = Color(0xFF9DDBFC),
    val warmRim: Color = Color(0xFFFFC85A),
    val glassTop: Color = Color(0xD02B4355),
    val glassMiddle: Color = Color(0xE20A141E),
    val glassBottom: Color = Color(0xEE050A10),
    val opacity: Float = 1f,
)

/**
 * Analytic liquid field and material. One longitudinal C2 membrane continuously
 * advects from the rear reservoir into the leading drop; no hidden primitives,
 * CSG saddle mergers, or Kotlin-side topology switches are involved.
 */
internal const val LIQUID_NAVIGATION_SHADER = """
    // leading-drop x, reservoir x, transfer timeline, stable travel direction
    uniform float4 motion;
    // departure width, destination width, resting height, center y
    uniform float4 dimensions;
    // directed relative velocity, destination x, layer alpha, warmth
    uniform float4 dynamics;
    uniform float4 coolRim;
    uniform float4 warmRim;
    uniform float4 glassTop;
    uniform float4 glassMiddle;
    uniform float4 glassBottom;

    float unitClamp(float value) {
        return clamp(value, 0.0, 1.0);
    }

    float smoother(float value) {
        float x = unitClamp(value);
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    }

    float phaseAt() {
        return unitClamp(motion.z);
    }

    float directionAt() {
        return motion.w < 0.0 ? -1.0 : 1.0;
    }

    float radiusAt() {
        return dimensions.z * 0.5;
    }

    float stretchAt() {
        return abs(motion.x - motion.y) / max(dimensions.z, 1.0);
    }

    float extensionAt() {
        float stretch = stretchAt();
        float extension = stretch * stretch / (stretch * stretch + 0.16);
        return extension * smoother(phaseAt() / 0.24);
    }

    float velocityAt() {
        float raw = dynamics.x * 0.16;
        return raw / sqrt(1.0 + raw * raw);
    }

    float positiveVelocityAt() {
        float velocity = velocityAt();
        return 0.5 * (velocity + sqrt(velocity * velocity + 0.0025));
    }

    float transferAt() {
        return smoother(phaseAt());
    }

    float arrivalAt() {
        float distance = abs(motion.x - dynamics.y) / max(radiusAt() * 0.90, 1.0);
        return exp(-distance * distance);
    }

    float lifecycleAt() {
        float timeline = smoother(phaseAt());
        return 1.0 - (1.0 - timeline) * (1.0 - arrivalAt());
    }

    float restingWidthAt() {
        return mix(dimensions.x, dimensions.y, lifecycleAt());
    }

    float rearRadiusAt() {
        float residual = 0.10;
        float physical = radiusAt() * sqrt(residual + (1.0 - residual) * (1.0 - transferAt()));
        return mix(radiusAt(), physical, extensionAt());
    }

    float frontRadiusAt() {
        float residual = 0.10;
        float physical = radiusAt() * sqrt(residual + (1.0 - residual) * transferAt());
        float extension = extensionAt();
        float acceleratedEmergence =
            1.0 - (1.0 - extension) * (1.0 - extension) * (1.0 - extension);
        float emergence = mix(extension, acceleratedEmergence, smoother(phaseAt() / 0.24));
        return mix(radiusAt(), physical, emergence);
    }

    float massCenterAt() {
        return mix(motion.y, motion.x, transferAt());
    }

    float rearCenterAt() {
        return motion.y;
    }

    float frontCenterAt() {
        return motion.x;
    }

    float rearHalfWidthAt() {
        float reservoir = max(rearRadiusAt() * 1.14, radiusAt() * 0.62);
        return mix(restingWidthAt() * 0.5, reservoir, extensionAt());
    }

    float impactAt() {
        float velocity = velocityAt();
        float speed = sqrt(velocity * velocity + 0.0025) - 0.05;
        return arrivalAt() * speed;
    }

    float frontDeformationAt() {
        float travelStretch = positiveVelocityAt() * extensionAt() * 0.30;
        float impactSquash = impactAt() * 0.16;
        return 1.0 + travelStretch - impactSquash;
    }

    float frontHalfWidthAt() {
        return frontRadiusAt() * frontDeformationAt();
    }

    float frontHalfHeightAt() {
        return frontRadiusAt() / frontDeformationAt();
    }

    float2 frontCenter2At() {
        return float2(frontCenterAt(), dimensions.w);
    }

    float neckFractionAt() {
        return rearRadiusAt() / max(rearRadiusAt() + frontHalfHeightAt(), 0.001);
    }

    float capillaryTensionAt() {
        float extension = extensionAt();
        return extension * extension * (3.0 - 2.0 * extension);
    }

    float rearOuterAt() {
        float radius = radiusAt();
        float restFlat = max(restingWidthAt() - radius * 2.0, 0.0) * 0.5;
        float rearFlat = max(rearHalfWidthAt() - rearRadiusAt(), 0.0);
        float rest = directionAt() * massCenterAt() - restFlat;
        float stretched = directionAt() * rearCenterAt() - rearFlat;
        return mix(rest, stretched, extensionAt());
    }

    float frontOuterAt() {
        float radius = radiusAt();
        float restFlat = max(restingWidthAt() - radius * 2.0, 0.0) * 0.5;
        float frontFlat = max(frontHalfWidthAt() - frontHalfHeightAt(), 0.0);
        float rest = directionAt() * massCenterAt() + restFlat;
        float stretched = directionAt() * frontCenterAt() + frontFlat;
        return mix(rest, stretched, extensionAt());
    }

    float membraneRadiusAt(float along) {
        float rear = rearRadiusAt();
        float front = frontHalfHeightAt();
        float neckAt = neckFractionAt();
        float base = mix(rear, front, smoother(along));
        float baseAtNeck = mix(rear, front, smoother(neckAt));
        float effective = 2.0 * rear * front / max(rear + front, 0.001);
        float targetNeck = sqrt(
            effective * effective * 0.2116 + radiusAt() * radiusAt() * 0.0324
        );
        float rearPressure = smoother(along / max(neckAt, 0.001));
        float frontPressure = smoother((1.0 - along) / max(1.0 - neckAt, 0.001));
        float pressure = rearPressure * frontPressure;
        return base - (baseAtNeck - targetNeck) * capillaryTensionAt() * pressure;
    }

    float membraneArcAt(float along) {
        float basis = along * (1.0 - along);
        return 64.0 * basis * basis * basis;
    }

    float liquidDistance(float2 point) {
        float rawRear = rearOuterAt();
        float rawFront = frontOuterAt();
        float midpoint = (rawRear + rawFront) * 0.5;
        float span = sqrt((rawFront - rawRear) * (rawFront - rawRear) + 0.0004);
        float rearOuter = midpoint - span * 0.5;
        float frontOuter = midpoint + span * 0.5;
        float orientedX = directionAt() * point.x;

        float along = unitClamp((orientedX - rearOuter) / span);
        float arc = membraneArcAt(along);
        float centerY = dimensions.w + radiusAt() * extensionAt() * 0.070 * arc;
        centerY += sin(phaseAt() * 6.2831853 + along * 3.1415926) *
            radiusAt() * (
                extensionAt() * (1.0 - capillaryTensionAt() * 0.34) * 0.010 +
                arrivalAt() * abs(velocityAt()) * 0.014
            ) * arc;
        float nearestX = mix(rearOuter, frontOuter, along);
        return length(float2(orientedX - nearestX, point.y - centerY)) - membraneRadiusAt(along);
    }

    half4 main(float2 point) {
        float distance = liquidDistance(point);
        float antialias = 1.15;
        float coverage = 1.0 - smoothstep(-antialias, antialias, distance);
        float alpha = unitClamp(dynamics.z);
        if (coverage <= 0.0 && distance > radiusAt() * 0.45) {
            return half4(0.0);
        }

        float epsilon = 0.85;
        float2 gradient = float2(
            liquidDistance(point + float2(epsilon, 0.0)) - liquidDistance(point - float2(epsilon, 0.0)),
            liquidDistance(point + float2(0.0, epsilon)) - liquidDistance(point - float2(0.0, epsilon))
        );
        float2 normal = normalize(gradient + float2(0.0001));
        float lensRadial = unitClamp(1.0 + distance / max(radiusAt(), 1.0));
        float lensDepth = sqrt(max(1.0 - lensRadial * lensRadial, 0.0001));
        float3 lensNormal = normalize(float3(normal * lensRadial, lensDepth));
        float2 lightDirection = normalize(float2(-0.52, -0.86));
        float facing = unitClamp(dot(normal, lightDirection) * 0.5 + 0.5);
        float direct = unitClamp(dot(normal, lightDirection));
        float broadSpecular = pow(direct, 3.2);
        float sharpSpecular = pow(direct, 18.0);
        float fresnel = 0.0204 + 0.9796 * pow(1.0 - lensNormal.z, 5.0);
        float depth = smoothstep(0.0, radiusAt() * 0.58, -distance);
        float edge = (1.0 - smoothstep(0.0, 1.55, abs(distance))) * coverage;
        float innerRim = (1.0 - smoothstep(0.0, 3.8, max(-distance, 0.0))) * coverage;
        float outerGlow = (1.0 - smoothstep(0.0, radiusAt() * 0.42, max(distance, 0.0))) * (1.0 - coverage);

        float vertical = unitClamp((point.y - (dimensions.w - radiusAt())) / max(dimensions.z, 1.0));
        float4 upperGlass = mix(glassTop, glassMiddle, smoother(vertical / 0.52));
        float4 glass = mix(upperGlass, glassBottom, smoother((vertical - 0.44) / 0.56));
        float4 rim = mix(coolRim, warmRim, unitClamp(dynamics.w));

        float2 headDelta = (point - frontCenter2At()) /
            float2(max(frontHalfWidthAt() * 1.25, 1.0), max(frontHalfHeightAt(), 1.0));
        float caustic = exp(-dot(headDelta + float2(0.28, 0.42), headDelta + float2(0.28, 0.42)) * 3.2) * coverage;
        float travellingSheen = 0.5 + 0.5 * sin(phaseAt() * 8.4 + point.x * 0.030 + velocityAt());

        float surfaceAlpha = coverage * mix(glass.a * 0.74, glass.a * 0.92, depth);
        surfaceAlpha = unitClamp(surfaceAlpha + edge * 0.24 + innerRim * 0.05) * alpha;
        float3 absorption = exp(-float3(0.18, 0.095, 0.045) * lensDepth * 1.7);
        float3 surface = glass.rgb * absorption * mix(0.72, 0.98, depth);
        surface += rim.rgb * edge * mix(0.24, 0.76, facing);
        surface += float3(1.0) * broadSpecular * innerRim * 0.13;
        surface += float3(1.0) * sharpSpecular * innerRim * 0.24;
        surface += rim.rgb * fresnel * innerRim * 0.34;
        surface += mix(float3(1.0), rim.rgb, 0.28) * caustic * (0.07 + 0.035 * travellingSheen);

        float glowAlpha = outerGlow * (0.020 + 0.035 * extensionAt()) * alpha;
        float shadowDistance = liquidDistance(point - float2(0.0, 4.2));
        float shadowAlpha = (1.0 - smoothstep(-1.0, 8.0, shadowDistance)) *
            (1.0 - coverage) * 0.28 * alpha;
        float outputAlpha = surfaceAlpha;
        float3 outputColor = surface * surfaceAlpha;
        outputColor += rim.rgb * glowAlpha * (1.0 - outputAlpha);
        outputAlpha += glowAlpha * (1.0 - outputAlpha);
        outputAlpha += shadowAlpha * (1.0 - outputAlpha);
        return half4(half3(outputColor), half(unitClamp(outputAlpha)));
    }
"""
