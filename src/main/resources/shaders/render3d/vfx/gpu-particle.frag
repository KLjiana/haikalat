#version 460 core

layout (location = 0) in vec4 gColor;
layout (location = 1) in vec2 gUv;

layout (location = 0) out vec4 fragColor;

void main() {
    float radiusSquared = dot(gUv, gUv);
    if (radiusSquared > 1.0) discard;
    float falloff = exp(-3.5 * radiusSquared) * smoothstep(1.0, 0.65, radiusSquared);
    fragColor = vec4(gColor.rgb * falloff * gColor.a, gColor.a * falloff);
}
