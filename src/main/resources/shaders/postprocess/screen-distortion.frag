#version 460 core

uniform sampler2D uColor;
uniform sampler2D uSceneDepth;
uniform vec2 uViewportSize;
uniform float uMaximumOffsetUv;
uniform float uEdgeFeatherUv;
uniform int uContributionCount;

// centerUv.xy, extentUv.xy
uniform vec4 uDistortionShape[8];
// uvOffset.xy, mask, effectDepth
uniform vec4 uDistortionOffset[8];
// depthThickness, haze, phase, ring
uniform vec4 uDistortionDepth[8];

layout (location = 0) out vec4 fragColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

vec2 hazeOffset(vec2 uv, float phase) {
    vec2 p = uv * 12.0 + vec2(phase);
    return vec2(hash21(p) - 0.5, hash21(p + vec2(19.19, 7.77)) - 0.5);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewportSize;
    float feather = max(uEdgeFeatherUv, 1.0e-4);
    float edge = smoothstep(0.0, feather, uv.x)
            * smoothstep(0.0, feather, 1.0 - uv.x)
            * smoothstep(0.0, feather, uv.y)
            * smoothstep(0.0, feather, 1.0 - uv.y);
    float sceneDepth = texture(uSceneDepth, uv).r;
    vec2 composed = vec2(0.0);
    int count = clamp(uContributionCount, 0, 8);
    // Ordered far-to-near by the host: a nearer mask overwrites a farther one.
    for (int i = 0; i < count; i++) {
        vec4 shape = uDistortionShape[i];
        vec4 offsetData = uDistortionOffset[i];
        vec4 depthData = uDistortionDepth[i];
        if (offsetData.z <= 0.0) continue;
        vec2 radial = (uv - shape.xy) / max(shape.zw, vec2(1.0e-5));
        float radius = clamp(length(radial), 0.0, 1.0);
        float blob = 1.0 - radius;
        float band = smoothstep(0.45, 0.8, radius) * (1.0 - smoothstep(0.8, 1.0, radius));
        float shapeMask = mix(blob, band, clamp(depthData.w, 0.0, 1.0));
        if (shapeMask <= 0.0) continue;
        // Foreground rejection: geometry closer than the effect plane is never sampled.
        if (sceneDepth < offsetData.w - depthData.x) continue;
        float mask = clamp(offsetData.z, 0.0, 1.0) * shapeMask * edge;
        if (mask <= 0.0) continue;
        vec2 contribution = offsetData.xy
                + clamp(depthData.y, 0.0, 1.0) * hazeOffset(uv, depthData.z) * 0.5;
        composed = mix(composed, contribution, mask);
    }
    float lengthSquared = dot(composed, composed);
    if (lengthSquared > uMaximumOffsetUv * uMaximumOffsetUv) {
        composed *= uMaximumOffsetUv * inversesqrt(lengthSquared);
    }
    vec2 target = uv + composed;
    // UV edge fallback: an offset that leaves the image is discarded instead of
    // smearing the clamped border, and in-range sampling stays inside half a texel.
    vec2 halfTexel = 0.5 / uViewportSize;
    vec2 sampleUv = (any(lessThan(target, vec2(0.0)))
            || any(greaterThan(target, vec2(1.0))))
            ? uv : clamp(target, halfTexel, vec2(1.0) - halfTexel);
    fragColor = texture(uColor, sampleUv);
}
