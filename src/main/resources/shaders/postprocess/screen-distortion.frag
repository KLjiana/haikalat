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

vec2 hazeLattice(vec2 cell) {
    return vec2(hash21(cell), hash21(cell + vec2(19.19, 7.77))) - 0.5;
}

vec2 hazeOffset(vec2 uv, float phase) {
    // Hash only lattice sites; smooth interpolation keeps the offset field
    // continuous in both screen position and time.
    vec2 p = uv * vec2(uViewportSize.x / uViewportSize.y, 1.0) * 12.0 + vec2(phase);
    vec2 cell = floor(p);
    vec2 f = smoothstep(vec2(0.0), vec2(1.0), fract(p));
    vec2 bottom = mix(hazeLattice(cell), hazeLattice(cell + vec2(1.0, 0.0)), f.x);
    vec2 top = mix(hazeLattice(cell + vec2(0.0, 1.0)), hazeLattice(cell + vec2(1.0)), f.x);
    return mix(bottom, top, f.y);
}

vec2 boundedOffset(vec2 offset) {
    float lengthSquared = dot(offset, offset);
    if (lengthSquared > uMaximumOffsetUv * uMaximumOffsetUv) {
        return offset * (uMaximumOffsetUv * inversesqrt(lengthSquared));
    }
    return offset;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int count = clamp(uContributionCount, 0, 8);
    if (count == 0) {
        fragColor = texelFetch(uColor, pixel, 0);
        return;
    }
    vec2 uv = gl_FragCoord.xy / uViewportSize;
    vec2 halfTexel = 0.5 / uViewportSize;
    float feather = max(uEdgeFeatherUv, 1.0e-4);
    float edge = smoothstep(0.0, feather, uv.x)
            * smoothstep(0.0, feather, 1.0 - uv.x)
            * smoothstep(0.0, feather, uv.y)
            * smoothstep(0.0, feather, 1.0 - uv.y);
    float sceneDepth = texelFetch(uSceneDepth, pixel, 0).r;
    vec2 composed = vec2(0.0);
    vec2 sampleUv = uv;
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
        float foregroundDepth = offsetData.w - depthData.x;
        if (sceneDepth < foregroundDepth) continue;
        float mask = clamp(offsetData.z, 0.0, 1.0) * shapeMask * edge;
        if (mask <= 0.0) continue;
        vec2 contribution = offsetData.xy;
        if (depthData.y > 0.0) {
            contribution += hazeOffset(uv, depthData.z) * (depthData.y * uMaximumOffsetUv);
        }
        vec2 candidate = mix(composed, contribution, mask);
        vec2 target = uv + boundedOffset(candidate);
        if (any(lessThan(target, vec2(0.0))) || any(greaterThan(target, vec2(1.0)))) continue;
        target = clamp(target, halfTexel, vec2(1.0) - halfTexel);
        if (any(notEqual(target, uv))) {
            // A background pixel must not pull foreground color into the warp,
            // even if only one tap of the color's bilinear footprint is blocked.
            vec4 depths = textureGather(uSceneDepth, target);
            if (any(lessThan(depths, vec4(foregroundDepth)))) continue;
        }
        composed = candidate;
        sampleUv = target;
    }
    // With no valid shift, copy the original texel, including opaque blockers.
    fragColor = all(equal(uv, sampleUv)) ? texelFetch(uColor, pixel, 0)
                                        : textureLod(uColor, sampleUv, 0.0);
}
