#version 460 core

in vec2 vTexCoord;
in vec3 vWorldNormal;
in vec3 vWorldPosition;
in vec4 vCurrentClip;
in vec4 vPreviousClip;
in float vPreviousViewDepth;
in float vPreviousValid;

layout(location = 0) out vec4 outNormal;
layout(location = 1) out vec2 outVelocity;
layout(location = 2) out float outPreviousDepth;
layout(location = 3) out float outValidity;

uniform int uMasked;
uniform int uDerivativeNormal;
uniform float uAlphaCutoff;
uniform vec4 uBaseColorFactor;
uniform vec3 uCameraPosition;
uniform sampler2D uBaseColorMap;

vec2 octEncode(vec3 normal) {
    vec3 n = normalize(normal);
    vec2 oct = n.xy / (abs(n.x) + abs(n.y) + abs(n.z) + 1.0e-8);
    oct = n.z >= 0.0 ? oct : (1.0 - abs(oct.yx)) * sign(oct + vec2(1.0e-8));
    return oct;
}

vec3 surfaceNormal() {
    if (uDerivativeNormal == 0) {
        return normalize(vWorldNormal);
    }
    vec3 n = normalize(cross(dFdx(vWorldPosition), dFdy(vWorldPosition)));
    if (dot(n, vWorldPosition - uCameraPosition) > 0.0) {
        n = -n;
    }
    return n;
}

void main() {
    if (uMasked != 0) {
        vec4 base = texture(uBaseColorMap, vTexCoord) * uBaseColorFactor;
        if (base.a < uAlphaCutoff) {
            discard;
        }
    }
    vec2 currentUv = (vCurrentClip.xy / vCurrentClip.w) * 0.5 + 0.5;
    vec2 previousUv = (vPreviousClip.xy / vPreviousClip.w) * 0.5 + 0.5;
    vec2 velocity = previousUv - currentUv;
    bool finiteVelocity = all(lessThan(abs(velocity), vec2(4.0)));
    bool valid = vPreviousValid > 0.5 && finiteVelocity;

    outNormal = vec4(octEncode(surfaceNormal()), 0.0, 1.0);
    outVelocity = valid ? velocity : vec2(0.0);
    outPreviousDepth = valid ? vPreviousViewDepth : 0.0;
    outValidity = valid ? 1.0 : 0.0;
}
