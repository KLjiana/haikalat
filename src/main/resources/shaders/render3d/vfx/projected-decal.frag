#version 460 core

// Projected receiver volume.  The vertex stage rasterizes the oriented box's
// far-side faces; every covered pixel reconstructs the visible receiver from
// depth and keeps its contribution only when the receiver satisfies the
// facing and depth-thickness contract.  This is not a camera-facing quad.

uniform sampler2D uSceneDepth;
uniform sampler2D uTexture;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform mat4 uInverseModel;
uniform vec3 uCameraPosition;
uniform vec3 uDecalNormal;
uniform vec2 uViewportSize;
uniform vec4 uColor;
uniform float uEmissive;
uniform float uFacingCosine;
uniform int uReceiveBackFaces;
uniform int uAlphaMask;

layout (location = 0) out vec4 fragColor;

vec3 reconstructWorldPosition(vec2 uv, float depth) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * ndc;
    view /= view.w;
    return (uInverseView * vec4(view.xyz, 1.0)).xyz;
}

void main() {
    vec2 screenUv = gl_FragCoord.xy / uViewportSize;
    float sceneDepth = texture(uSceneDepth, screenUv).r;
    vec3 receiver = reconstructWorldPosition(screenUv, sceneDepth);
    // Derivatives are evaluated before any discard so the reconstructed
    // receiver normal is defined in uniform control flow.
    vec3 receiverNormal = cross(dFdx(receiver), dFdy(receiver));

    // Cleared depth is the sky/background: no receiver exists there.
    if (sceneDepth >= 1.0) discard;

    // Receiver-volume test in decal space: x/y are the rectangle, z is the
    // signed depth interval [-thickness, +surfaceOffset].
    vec3 local = (uInverseModel * vec4(receiver, 1.0)).xyz;
    if (any(lessThan(local, vec3(-0.5, -0.5, 0.0)))
            || any(greaterThan(local, vec3(0.5, 0.5, 1.0)))) discard;

    float normalLengthSquared = dot(receiverNormal, receiverNormal);
    if (normalLengthSquared <= 1.0e-12) discard;
    receiverNormal *= inversesqrt(normalLengthSquared);
    // Only camera-facing surfaces are visible in the depth buffer, so the
    // derivative normal is oriented toward the camera before the filter.
    if (dot(receiverNormal, uCameraPosition - receiver) < 0.0) {
        receiverNormal = -receiverNormal;
    }

    float alignment = dot(receiverNormal, uDecalNormal);
    float facing = uReceiveBackFaces != 0 ? abs(alignment) : alignment;
    if (facing < uFacingCosine) discard;

    vec2 uv = local.xy + 0.5;
    vec4 sampled = texture(uTexture, uv);
    float coverage = clamp(uColor.a, 0.0, 1.0);
    if (uAlphaMask != 0) coverage *= sampled.a;
    if (coverage <= 0.0) discard;

    vec3 rgb = sampled.rgb * uColor.rgb * uEmissive;
    fragColor = vec4(rgb, coverage);
}
