#version 460 core

in vec2 vUv;

#ifdef SCENE_SLOT_NORMAL
layout(location = SCENE_SLOT_NORMAL) out vec4 outNormal;
uniform sampler2DMS uNormal;
#endif
#ifdef SCENE_SLOT_VELOCITY
layout(location = SCENE_SLOT_VELOCITY) out vec2 outVelocity;
uniform sampler2DMS uVelocity;
#endif
#ifdef SCENE_SLOT_PREVIOUS_SURFACE_DEPTH
layout(location = SCENE_SLOT_PREVIOUS_SURFACE_DEPTH) out float outPreviousDepth;
uniform sampler2DMS uPreviousDepth;
#endif
#ifdef SCENE_SLOT_VALIDITY
layout(location = SCENE_SLOT_VALIDITY) out float outValidity;
uniform sampler2DMS uValidity;
#endif
layout(location = SCENE_SLOT_DEPTH) out float outDepth;
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
#ifndef SCENE_EXACT_CLEAR
    if (best < 0 || bestDepth >= 0.999999) {
#ifdef SCENE_SLOT_NORMAL
        outNormal = vec4(0.0);
#endif
#ifdef SCENE_SLOT_VELOCITY
        outVelocity = vec2(0.0);
#endif
#ifdef SCENE_SLOT_PREVIOUS_SURFACE_DEPTH
        outPreviousDepth = 0.0;
#endif
#ifdef SCENE_SLOT_VALIDITY
        outValidity = 0.0;
#endif
        outDepth = 1.0;
        return;
    }
#endif
#ifdef SCENE_SLOT_NORMAL
    outNormal = texelFetch(uNormal, pixel, best);
#endif
#ifdef SCENE_SLOT_VELOCITY
    outVelocity = texelFetch(uVelocity, pixel, best).rg;
#endif
#ifdef SCENE_SLOT_PREVIOUS_SURFACE_DEPTH
    outPreviousDepth = texelFetch(uPreviousDepth, pixel, best).r;
#endif
#ifdef SCENE_SLOT_VALIDITY
    outValidity = texelFetch(uValidity, pixel, best).r;
#endif
    outDepth = bestDepth;
}
