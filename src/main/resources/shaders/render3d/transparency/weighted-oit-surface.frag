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

// Documented in WeightedOitSettings: weight is bounded and monotone
// decreasing in |view depth|; depth weighting disabled means exactly one.
float oitWeight(float viewDepth) {
    if (uDepthWeight == 0) {
        return 1.0;
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
    oAccumulation = vec4(vColor.rgb * alpha * weight, alpha);
    oWeightSum = alpha * weight;
}
