#version 460 core

// Bounded unsharp (RCAS-style local contrast limit) for the resolved temporal
// upscale.  The filter adds the local Laplacian scaled by the sharpness amount
// and clamps every channel to the range already present in the 3x3 cross, so it
// cannot create halos outside the existing contrast.  Pixels covered by an
// already composited UI mask are copied through untouched.
//
// Bounded cost: 5 texel fetches; no unbounded loop, no readback.

in vec2 vUv;
layout(location = 0) out vec4 outColor;

uniform sampler2D uResolved;
uniform sampler2D uUiMask;
uniform vec2 uOutputExtent;
uniform vec2 uInputExtent;
uniform int uHasUiMask;
uniform float uSharpness;

void main() {
    ivec2 extent = ivec2(uOutputExtent);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), extent - ivec2(1));
    vec4 center = texelFetch(uResolved, pixel, 0);

    if (uHasUiMask != 0) {
        vec2 uv = (vec2(pixel) + 0.5) / uOutputExtent;
        ivec2 inputPixel = clamp(ivec2(floor(uv * uInputExtent)),
                ivec2(0), ivec2(uInputExtent) - ivec2(1));
        if (texelFetch(uUiMask, inputPixel, 0).r > 0.5) {
            outColor = center;
            return;
        }
    }
    if (!(uSharpness > 0.0)) {
        outColor = center;
        return;
    }

    ivec2 maximum = extent - ivec2(1);
    vec3 north = texelFetch(uResolved,
            clamp(pixel + ivec2(0, 1), ivec2(0), maximum), 0).rgb;
    vec3 south = texelFetch(uResolved,
            clamp(pixel + ivec2(0, -1), ivec2(0), maximum), 0).rgb;
    vec3 east = texelFetch(uResolved,
            clamp(pixel + ivec2(1, 0), ivec2(0), maximum), 0).rgb;
    vec3 west = texelFetch(uResolved,
            clamp(pixel + ivec2(-1, 0), ivec2(0), maximum), 0).rgb;

    vec3 average = (north + south + east + west) * 0.25;
    vec3 low = min(min(north, south), min(east, west));
    vec3 high = max(max(north, south), max(east, west));
    vec3 sharpened = center.rgb + (center.rgb - average) * uSharpness;
    sharpened = clamp(sharpened, min(low, center.rgb), max(high, center.rgb));
    outColor = vec4(sharpened, center.a);
}
