#version 460 core

// Weighted blended OIT accumulation (McGuire / Bavoil style, bounded weight).
//
// Attachment 0 (RGBA16F) holds the weighted radiance in RGB and the surface
// coverage in A.  The fixed-function alpha blend (GL_ZERO,
// GL_ONE_MINUS_SRC_ALPHA) turns the A channel into prod(1 - alpha), i.e. the
// revealage.  Attachment 1 (R16F) holds sum(alpha * weight), used by the
// resolve to normalise the colour sum.  RGB factors (GL_ONE, GL_ONE) sum both
// attachments.  Neither attachment is the shared scene depth: this pass writes
// no depth at all.
//
// Finite-output guard: a legal OitSurface may carry colour up to 65504 and a
// weight up to 1000, so colour * alpha * weight can exceed the half-float
// maximum.  Each layer is capped well below that maximum; the resolve rejects
// any accumulation that still overflows.  This keeps every produced target
// finite instead of relying on driver-specific half-float saturation.

layout(location = 0) out vec4 oAccumulation;
layout(location = 1) out float oWeightSum;

in vec4 vColor;
in float vViewDepth;

uniform int uDepthWeight;
uniform float uDepthScale;
uniform float uReferenceDepth;
uniform float uMinimumWeight;
uniform float uMaximumWeight;
uniform float uAlphaThreshold;

// 3.0e4 leaves more than half of the half-float range (65504) free for
// multiple layers and blend rounding; the resolve has the matching hard cap.
const float OIT_MAX_LAYER_WEIGHT = 3.0e4;
const float OIT_MAX_LAYER_CONTRIBUTION = 3.0e4;

// Documented in WeightedOitSettings: weight is bounded and monotone
// decreasing in |view depth|; depth weighting disabled means exactly one.
float oitWeight(float viewDepth) {
    if (uDepthWeight == 0) {
        return 1.0;
    }
    // A huge or non-finite transformed position must not poison the half-float
    // attachment through NaN propagation.
    if (isnan(viewDepth) || isinf(viewDepth)) {
        return uMinimumWeight;
    }
    float normalized = abs(viewDepth) / max(uReferenceDepth, 1.0e-4);
    float weight = uDepthScale / (1.0 + normalized * normalized);
    return clamp(weight, uMinimumWeight, uMaximumWeight);
}

void main() {
    float alpha = vColor.a;
    if (!(alpha > uAlphaThreshold)) {
        discard;
    }
    float weight = oitWeight(vViewDepth);
    if (isnan(weight) || isinf(weight)) {
        weight = uMinimumWeight;
    }
    weight = clamp(weight, 0.0, OIT_MAX_LAYER_WEIGHT);

    vec3 radiance = vColor.rgb;
    if (any(isnan(radiance)) || any(isinf(radiance))) {
        radiance = vec3(0.0);
    }
    vec3 contribution = max(radiance, vec3(0.0)) * clamp(alpha, 0.0, 1.0) * weight;
    if (any(isnan(contribution)) || any(isinf(contribution))) {
        contribution = vec3(0.0);
    }
    contribution = min(contribution, vec3(OIT_MAX_LAYER_CONTRIBUTION));

    float layerWeight = clamp(alpha, 0.0, 1.0) * weight;
    if (isnan(layerWeight) || isinf(layerWeight)) {
        layerWeight = 0.0;
    }
    oAccumulation = vec4(contribution, clamp(alpha, 0.0, 1.0));
    oWeightSum = min(layerWeight, OIT_MAX_LAYER_WEIGHT);
}
