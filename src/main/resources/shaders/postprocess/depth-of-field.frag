#version 460 core

in vec2 vUv;
layout (location = 0) out vec4 fragColor;

uniform sampler2D uColor;
uniform sampler2D uDepth;
uniform mat4 uInverseProjection;
uniform vec2 uViewportSize;
uniform float uFocusDistance;
uniform float uFocalLengthMeters;
uniform float uAperture;
uniform float uMaxCocPixels;

const int TAP_COUNT = 16;
const float GOLDEN_ANGLE = 2.39996323;
const float SENSOR_WIDTH_METERS = 0.036;
const float MIN_VIEW_DISTANCE = 0.001;

float viewDistance(float deviceDepth) {
    float safeDepth = clamp(deviceDepth, 0.0, 1.0);
    vec4 clip = vec4(0.0, 0.0, safeDepth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    float w = abs(view.w) < 1.0e-6 ? (view.w < 0.0 ? -1.0e-6 : 1.0e-6) : view.w;
    return max(-view.z / w, MIN_VIEW_DISTANCE);
}

float cocRadiusPixels(float distance) {
    float denominator = max(uAperture * distance
            * max(uFocusDistance - uFocalLengthMeters, 0.001), 1.0e-6);
    float diameterMeters = uFocalLengthMeters * uFocalLengthMeters
            * abs(distance - uFocusDistance) / denominator;
    float radiusPixels = 0.5 * diameterMeters * (uViewportSize.x / SENSOR_WIDTH_METERS);
    return min(radiusPixels, uMaxCocPixels);
}

void main() {
    vec2 viewport = max(uViewportSize, vec2(1.0));
    vec2 uv = gl_FragCoord.xy / viewport;
    ivec2 depthExtent = textureSize(uDepth, 0);
    ivec2 centerTexel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), depthExtent - 1);
    vec4 center = texture(uColor, uv);
    float centerDistance = viewDistance(texelFetch(uDepth, centerTexel, 0).r);
    float centerCoc = cocRadiusPixels(centerDistance);

    vec3 sum = center.rgb;
    float weight = 1.0;
    for (int i = 0; i < TAP_COUNT; ++i) {
        float fraction = (float(i) + 0.5) / float(TAP_COUNT);
        float radius = sqrt(fraction) * uMaxCocPixels;
        float angle = float(i) * GOLDEN_ANGLE;
        vec2 offsetPixels = vec2(cos(angle), sin(angle)) * radius;
        vec2 sampleUv = clamp(uv + offsetPixels / viewport,
                0.5 / viewport, 1.0 - 0.5 / viewport);
        // Depth is a resolved single-sample or MSAA-resolved R32F surface.  A
        // nearest texel fetch avoids interpolating device depth across an edge
        // (which would fabricate CoC) while colour keeps its bilinear filter.
        ivec2 sampleTexel = clamp(ivec2(sampleUv * vec2(depthExtent)),
                ivec2(0), depthExtent - 1);
        float sampleDistance = viewDistance(texelFetch(uDepth, sampleTexel, 0).r);
        float sampleCoc = cocRadiusPixels(sampleDistance);

        if (centerCoc < 0.5) {
            // An in-focus receiver only accepts nearer out-of-focus samples.
            // This keeps in-focus pixels sharp and lets foreground bokeh spread
            // over a focused background without pulling distant colour into a
            // sharp foreground edge.
            if (!(sampleDistance < centerDistance - 1.0e-3 && sampleCoc > 0.5)) continue;
            float coverage = clamp(sampleCoc - radius + 0.5, 0.0, 1.0);
            if (coverage <= 0.0) continue;
            sum += texture(uColor, sampleUv).rgb * coverage;
            weight += coverage;
            continue;
        }

        // A farther sample whose own blur disk is larger than the receiver's
        // may not leak over a sharper receiver.  Nearer samples still spread.
        if (sampleDistance > centerDistance + max(centerDistance * 0.01, 1.0e-3)
                && sampleCoc > centerCoc + 0.25) continue;
        // Weight by the tap's own blur disk: a sharp foreground sample does not
        // bleed into a defocused background just because the receiver is blurred.
        float coverage = clamp(sampleCoc - radius + 0.5, 0.0, 1.0);
        if (coverage <= 0.0) continue;
        sum += texture(uColor, sampleUv).rgb * coverage;
        weight += coverage;
    }
    fragColor = vec4(sum / max(weight, 1.0), center.a);
}
