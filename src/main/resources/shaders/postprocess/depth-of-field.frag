#version 460 core

in vec2 vUv;
layout (location = 0) out vec4 fragColor;

uniform sampler2D uColor;
uniform sampler2D uDepth;
uniform mat4 uInverseProjection;
uniform vec2 uViewportSize;
uniform float uFocusDistance;
uniform float uAperture;
uniform float uMaxCocPixels;

const int TAP_COUNT = 16;
const float GOLDEN_ANGLE = 2.39996323;
const float FOCAL_LENGTH_METERS = 0.05;
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
            * max(uFocusDistance - FOCAL_LENGTH_METERS, 0.001), 1.0e-6);
    float diameterMeters = FOCAL_LENGTH_METERS * FOCAL_LENGTH_METERS
            * abs(distance - uFocusDistance) / denominator;
    float radiusPixels = 0.5 * diameterMeters * (uViewportSize.x / SENSOR_WIDTH_METERS);
    return min(radiusPixels, uMaxCocPixels);
}

void main() {
    vec2 viewport = max(uViewportSize, vec2(1.0));
    vec2 uv = gl_FragCoord.xy / viewport;
    vec4 center = texture(uColor, uv);
    float centerDistance = viewDistance(texture(uDepth, uv).r);
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
        float sampleDistance = viewDistance(texture(uDepth, sampleUv).r);
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
