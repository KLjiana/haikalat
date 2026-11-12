#version 460 core

// Adds one depth-tested stochastic sample into the RGBA32F frame summation.
//
// The sample target was rasterised with blending disabled and a private depth
// test, so its colour is exactly the nearest accepted surface of that sample
// (alpha 0 means "no surface accepted").  This pass reads that sample and adds
// it with GL_ONE / GL_ONE: rgb accumulates the winner colour, alpha accumulates
// the hit count.  Keeping the accumulation in a separate target is what makes
// the estimator order independent: a nearer accepted fragment replaces a
// farther one inside the sample, instead of adding to it.

in vec2 vUv;

layout(location = 0) out vec4 fragColor;

uniform sampler2D uSampleColor;

const float ST_MAX_COLOR = 6.5504e4;

void main() {
    vec4 sampleColour = texture(uSampleColor, vUv);
    if (any(isnan(sampleColour)) || any(isinf(sampleColour))) {
        fragColor = vec4(0.0);
        return;
    }
    float hit = clamp(sampleColour.a, 0.0, 1.0);
    fragColor = vec4(clamp(sampleColour.rgb, vec3(0.0), vec3(ST_MAX_COLOR)), hit);
}
