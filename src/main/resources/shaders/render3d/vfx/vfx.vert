#version 460 core

layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec2 aUv;
layout (location = 2) in vec3 aNormal;

uniform mat4 uMvp;
uniform mat4 uProjectionView;
uniform mat4 uModel;
uniform mat4 uNormalMatrix;
uniform int uMesh;
uniform int uHasNormals;
uniform vec2 uRibbonWidths;
uniform int uRibbon;
uniform vec2 uRibbonUvRange;
uniform vec3 uRibbonStart;
uniform vec3 uRibbonEnd;
uniform vec3 uRibbonStartOffset;
uniform vec3 uRibbonEndOffset;

out vec2 vLocalUv;
out vec3 vWorldPosition;
out vec3 vWorldNormal;
flat out int vHasNormals;

void main() {
    vec2 localUv = aUv;
    vec3 worldPosition;
    vec3 worldNormal = vec3(0.0);
    if (uRibbon != 0) {
        float side = aUv.y * 2.0 - 1.0;
        vec3 center = mix(uRibbonStart, uRibbonEnd, aUv.x);
        vec3 offset = mix(uRibbonStartOffset, uRibbonEndOffset, aUv.x);
        worldPosition = center + offset * side;
        gl_Position = uProjectionView * vec4(worldPosition, 1.0);
        localUv = vec2(aUv.y, mix(uRibbonUvRange.x, uRibbonUvRange.y, aUv.x));
    } else if (uMesh != 0) {
        vec4 world = uModel * vec4(aPosition, 1.0);
        worldPosition = world.xyz;
        if (uHasNormals != 0) {
            worldNormal = mat3(uNormalMatrix) * aNormal;
        }
        gl_Position = uMvp * vec4(aPosition, 1.0);
    } else {
        vec3 position = aPosition;
        position.y *= mix(uRibbonWidths.x, uRibbonWidths.y, aUv.x);
        vec4 world = uModel * vec4(position, 1.0);
        worldPosition = world.xyz;
        gl_Position = uMvp * vec4(position, 1.0);
    }
    vLocalUv = localUv;
    vWorldPosition = worldPosition;
    vWorldNormal = worldNormal;
    vHasNormals = uHasNormals;
}
