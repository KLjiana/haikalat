#version 460 core

in vec2 vUv;

layout(location = 0) out vec4 outNormal;
layout(location = 1) out vec2 outVelocity;
layout(location = 2) out float outPreviousDepth;
layout(location = 3) out float outValidity;
layout(location = 4) out float outDepth;

uniform sampler2DMS uNormal;
uniform sampler2DMS uVelocity;
uniform sampler2DMS uPreviousDepth;
uniform sampler2DMS uValidity;
uniform sampler2DMS uDepth;
uniform int uSamples;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int best = -1;
    float bestDepth = 1.0;
    for (int sampleIndex = 0; sampleIndex < uSamples; sampleIndex++) {
        float depth = texelFetch(uDepth, pixel, sampleIndex).r;
        if (depth < bestDepth) {
            bestDepth = depth;
            best = sampleIndex;
        }
    }
    if (best < 0 || bestDepth >= 0.999999) {
        outNormal = vec4(0.0);
        outVelocity = vec2(0.0);
        outPreviousDepth = 0.0;
        outValidity = 0.0;
        outDepth = 1.0;
        return;
    }
    outNormal = texelFetch(uNormal, pixel, best);
    outVelocity = texelFetch(uVelocity, pixel, best).rg;
    outPreviousDepth = texelFetch(uPreviousDepth, pixel, best).r;
    outValidity = texelFetch(uValidity, pixel, best).r;
    outDepth = bestDepth;
}
