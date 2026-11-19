#version 460 core

// Stochastic transparency sample raster (S4 #37).
//
// Each selected transparent surface is rasterised as an opaque sample with
// probability p(alpha).  The sample framebuffer owns a private depth
// attachment, so the nearest accepted fragment wins per sample regardless of
// draw order.  The sample is unblended; the separate accumulate pass adds each
// winner colour and its hit flag into an RGBA32F frame-summation target, and
// every accepted fragment writes 1.0 into alpha, so the alpha channel is the
// accepted sample count.  The resolve pass divides both by the sample count.
// No shared scene depth is bound, sampled or written here; the private depth is
// cleared between samples by the pass.

#define ST_BAYER_SIZE 4
#define ST_MAX_OUTPUT 6.5504e4

layout(location = 0) out vec4 oSampleColor;

in vec4 vColor;
flat in uint vInstanceId;

uniform float uAlphaThreshold;
uniform float uCoverageGamma;
uniform int uJitterMode;
uniform int uSeed;
uniform int uFrameIndex;
uniform int uSampleIndex;

// 32-bit integer avalanche (same constants as the JVM reference).
uint stHash32(uint value) {
    value ^= value >> 16;
    value *= 0x7feb352dU;
    value ^= value >> 15;
    value *= 0x846ca68bU;
    value ^= value >> 16;
    return value;
}

// White-noise threshold: pixel and layer identities are mixed with the frame,
// sample and configured seed.  A per-instance term decorrelates overlapping
// layers so their independent acceptances do not alias.
float stUnitHash(ivec2 pixel) {
    uint key = uint(pixel.x) ^ (uint(pixel.y) << 16);
    key ^= vInstanceId * 0x9E3779B9U;
    key ^= uint(uSampleIndex) * 0x85EBCA6BU;
    key ^= uint(uFrameIndex) * 0xC2B2AE35U;
    key ^= uint(uSeed);
    return float(stHash32(key) >> 8) * (1.0 / 16777216.0);
}

// Per-instance, per-seed continuous phase.  Structured thresholds need this
// because the discrete 4x4 pattern and the IGN lattice would otherwise repeat
// for overlapping layers (and for more than four instances).  Two independent
// hashes give a full-lattice shift, not a small modulo-4 rotation.
vec2 stInstancePhase() {
    uint key = vInstanceId * 0x9E3779B9U ^ uint(uSeed) * 0x85EBCA6BU;
    uint other = key ^ 0x68E31DA4U;
    return vec2(float(stHash32(key) >> 8), float(stHash32(other) >> 8))
            * (1.0 / 16777216.0) * 1024.0;
}

// Interleaved-gradient dither; per-sample, per-frame, per-instance and
// per-seed offsets decorrelate the structured pattern so overlapping layers
// do not accept in lockstep.
float stInterleavedGradient(ivec2 pixel) {
    vec2 p = vec2(pixel)
            + vec2(0.7548776662, 0.5698402910) * float(uSampleIndex)
            + vec2(0.131719, 0.927663) * float(uFrameIndex & 1023)
            + stInstancePhase();
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// Ordered 4x4 Bayer dither.  A continuous per-instance/seed phase keeps the
// 16-level pattern unbiased over pixels while decorrelating overlapping
// layers; the integer rotation still varies with sample and frame.
float stBayer4(ivec2 pixel) {
    const float pattern[16] = float[16](
        0.0 / 16.0,  8.0 / 16.0,  2.0 / 16.0, 10.0 / 16.0,
        12.0 / 16.0, 4.0 / 16.0, 14.0 / 16.0,  6.0 / 16.0,
        3.0 / 16.0, 11.0 / 16.0,  1.0 / 16.0,  9.0 / 16.0,
        15.0 / 16.0, 7.0 / 16.0, 13.0 / 16.0,  5.0 / 16.0);
    int x = (pixel.x + uSampleIndex * 3 + uFrameIndex * 5) & 3;
    int y = (pixel.y + uSampleIndex * 5 + uFrameIndex * 3) & 3;
    vec2 phase = stInstancePhase();
    return fract(pattern[y * ST_BAYER_SIZE + x]
            + fract(phase.x * 0.6180339887 + phase.y * 0.3819660113));
}

float stThreshold() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (uJitterMode == 0) {
        return stUnitHash(pixel);
    }
    if (uJitterMode == 1) {
        return stInterleavedGradient(pixel);
    }
    return stBayer4(pixel);
}

void main() {
    float alpha = vColor.a;
    if (isnan(alpha) || isinf(alpha)) {
        discard;
    }
    alpha = clamp(alpha, 0.0, 1.0);
    if (!(alpha > uAlphaThreshold)) {
        discard;
    }
    // Unbiased when uCoverageGamma == 1; other values deliberately remap
    // coverage and are documented on StochasticTransparencySettings.
    float probability = alpha >= 1.0
            ? 1.0
            : clamp(pow(alpha, uCoverageGamma), 0.0, 1.0);
    if (isnan(probability) || isinf(probability)) {
        discard;
    }
    if (!(stThreshold() < probability)) {
        discard;
    }
    vec3 radiance = vColor.rgb;
    if (any(isnan(radiance)) || any(isinf(radiance))) {
        radiance = vec3(0.0);
    }
    radiance = clamp(radiance, vec3(0.0), vec3(ST_MAX_OUTPUT));
    // Alpha is the sample hit flag: the additive blend accumulates one per
    // accepted sample and the resolve divides by uSamplesPerFrame.
    oSampleColor = vec4(radiance, 1.0);
}
