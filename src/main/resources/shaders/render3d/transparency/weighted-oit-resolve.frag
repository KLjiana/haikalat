#version 460 core

// Weighted blended OIT resolve.
//
// Samples the accumulation attachment 0 (weighted colour rgb + revealage a)
// and attachment 1 (sum of alpha * weight), normalises the weighted average and
// writes (average colour, coverage) to the currently bound HDR target.  The
// caller's blend state is SRC_ALPHA / ONE_MINUS_SRC_ALPHA, so the target
// receives average * coverage + target * revealage.  An empty pixel writes
// coverage 0 and leaves the target exactly unchanged.
//
// Finite-output guard: attachment 1 is R16F and attachment 0 is RGBA16F, so a
// sufficiently deep/bright collection can overflow to +Inf (or driver-clamped
// 65504).  A non-finite or over-cap accumulation is dropped as transparent
// instead of being divided, and the resolved colour is clamped below the
// half-float maximum.  The written target is therefore always finite.

in vec2 vUv;

layout(location = 0) out vec4 fragColor;

uniform sampler2D uAccumulation;
uniform sampler2D uWeight;
uniform float uWeightEpsilon;

// Attachment values above this bound are treated as overflow.  The surface
// stage caps a single layer at 3.0e4, so this keeps at least two maximal
// layers representable while rejecting values near the half-float maximum.
const float OIT_MAX_ACCUMULATION = 6.5e4;
const float OIT_MAX_WEIGHT_SUM = 6.5e4;
const float OIT_MAX_RESOLVED_COLOR = 6.5504e4;

void main() {
    vec4 accumulation = texture(uAccumulation, vUv);
    float weightSum = texture(uWeight, vUv).r;

    bool invalid = any(isnan(accumulation)) || any(isinf(accumulation))
            || isnan(weightSum) || isinf(weightSum)
            || weightSum < 0.0
            || weightSum > OIT_MAX_WEIGHT_SUM
            || any(greaterThan(accumulation.rgb, vec3(OIT_MAX_ACCUMULATION)))
            || accumulation.a < 0.0 || accumulation.a > 1.0;
    if (invalid) {
        // Overflow/NaN contributes nothing: leave the target unchanged rather
        // than writing an Inf/NaN or a fabricated colour.
        fragColor = vec4(0.0, 0.0, 0.0, 0.0);
        return;
    }

    float revealage = clamp(accumulation.a, 0.0, 1.0);
    float coverage = 1.0 - revealage;
    if (coverage <= 0.0 || weightSum <= uWeightEpsilon) {
        fragColor = vec4(0.0, 0.0, 0.0, 0.0);
        return;
    }
    vec3 average = max(accumulation.rgb / weightSum, vec3(0.0));
    fragColor = vec4(min(average, vec3(OIT_MAX_RESOLVED_COLOR)), coverage);
}
