#version 460 core

layout (location = 0) in vec4 gColor;
layout (location = 1) in vec2 gUv;

layout (location = 0) out vec4 fragColor;

void main() {
    float radiusSquared = dot(gUv, gUv);
    if (radiusSquared > 1.0) {
        discard;
    }
    // GLSL smoothstep requires edge0 < edge1; a descending-edge form would be
    // undefined. This is the same falloff expressed with ascending edges.
    float falloff = exp(-3.5 * radiusSquared) * (1.0 - smoothstep(0.65, 1.0, radiusSquared));
    // Premultiplied additive output; the owner records GL_ONE/GL_ONE blending.
    fragColor = vec4(gColor.rgb * falloff * gColor.a, gColor.a * falloff);
}
