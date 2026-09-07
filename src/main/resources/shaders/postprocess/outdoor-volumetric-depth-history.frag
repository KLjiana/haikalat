#version 460 core

layout (location = 0) in vec2 vUv;
layout (location = 0) out float fragDepth;

uniform sampler2D uSceneDepth;

void main() {
    // A point sample keeps the history deterministic. The temporal pass uses
    // a separate depth history rather than comparing two samples of current
    // depth, so camera motion can reject disocclusions correctly.
    fragDepth = texture(uSceneDepth, vUv).r;
}
