#version 460 core

// Weighted blended OIT resolve.
//
// Samples the accumulation attachment (weighted colour rgb + revealage a) and
// the weight attachment (sum of alpha * weight), normalises the weighted
// average and writes (average colour, coverage) to the currently bound HDR
// target.  The caller's blend state is SRC_ALPHA / ONE_MINUS_SRC_ALPHA, so the
// target receives average * coverage + target * revealage.  An empty pixel
// writes coverage 0 and leaves the target exactly unchanged.

in vec2 vUv;

layout(location = 0) out vec4 fragColor;

uniform sampler2D uAccumulation;
uniform sampler2D uWeight;
uniform float uWeightEpsilon;

void main() {
    vec4 accumulation = texture(uAccumulation, vUv);
    float weightSum = texture(uWeight, vUv).r;
    float revealage = clamp(accumulation.a, 0.0, 1.0);
    float coverage = 1.0 - revealage;
    if (coverage <= 0.0 || weightSum <= uWeightEpsilon) {
        fragColor = vec4(0.0, 0.0, 0.0, 0.0);
        return;
    }
    vec3 average = max(accumulation.rgb / weightSum, vec3(0.0));
    fragColor = vec4(average, coverage);
}
