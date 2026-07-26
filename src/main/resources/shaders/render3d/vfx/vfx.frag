#version 460 core

uniform vec4 uColor;
uniform sampler2D uTexture;
uniform int uMaskMode;
uniform int uAdditive;
uniform float uEmissiveIntensity;
uniform float uAlphaCutoff;
uniform sampler2D uSceneDepth;
uniform vec2 uViewportSize;
uniform mat4 uInverseProjection;
uniform float uSoftParticleDistance;
uniform int uDepthEnabled;
uniform float uFlipbookBlend;

in vec2 vUv;
in vec2 vNextUv;

layout (location = 0) out vec4 fragColor;

float viewDistance(vec2 uv, float depth) {
    vec4 view = uInverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return abs(view.z / view.w);
}

void main() {
    vec4 sampled = mix(texture(uTexture, vUv), texture(uTexture, vNextUv), uFlipbookBlend);
    float mask = 1.0;
    vec3 sampledColor = vec3(1.0);
    if (uMaskMode == 1 || uMaskMode == 2 || uMaskMode == 3) {
        mask = sampled.r;
    } else if (uMaskMode == 4) {
        mask = sampled.a;
        sampledColor = sampled.rgb;
    }
    float alpha = uColor.a * mask;
    if (uDepthEnabled != 0) {
        vec2 screenUv = gl_FragCoord.xy / uViewportSize;
        float sceneDistance = viewDistance(screenUv, texture(uSceneDepth, screenUv).r);
        float fragmentDistance = viewDistance(screenUv, gl_FragCoord.z);
        float separation = sceneDistance - fragmentDistance;
        if (separation <= 0.0) discard;
        if (uSoftParticleDistance > 0.0) {
            alpha *= clamp(separation / uSoftParticleDistance, 0.0, 1.0);
        }
    }
    if (alpha < uAlphaCutoff) discard;
    vec3 rgb = sampledColor * uColor.rgb * uEmissiveIntensity;
    if (uAdditive != 0) rgb *= alpha;
    fragColor = vec4(rgb, alpha);
}
