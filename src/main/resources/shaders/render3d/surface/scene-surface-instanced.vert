#version 460 core

layout(location = 0) in vec3 aPos;
layout(location = 3) in mat4 aInstanceMatrix;

layout(std430, binding = 12) readonly buffer PreviousInstanceBlock {
    mat4 uPreviousInstanceMatrices[];
};

layout(std140) uniform CameraBlock {
    mat4 uProjection;
    mat4 uView;
};

uniform mat4 uCurrentStableViewProjection;
uniform mat4 uPreviousStableViewProjection;
uniform mat4 uPreviousView;
uniform vec3 uCameraPosition;
uniform int uPreviousValid;

out vec2 vTexCoord;
out vec3 vWorldNormal;
out vec3 vWorldPosition;
out vec4 vCurrentClip;
out vec4 vPreviousClip;
out float vPreviousViewDepth;
out float vPreviousValid;

void main() {
    vec4 world = aInstanceMatrix * vec4(aPos, 1.0);
    mat4 previousInstance = uPreviousValid != 0
        ? uPreviousInstanceMatrices[gl_InstanceID] : aInstanceMatrix;
    vec4 previousWorld = previousInstance * vec4(aPos, 1.0);

    vTexCoord = vec2(0.0);
    vWorldPosition = world.xyz;
    // The instanced vertex layout carries no normal attribute; use the
    // geometric surface normal from screen-space derivatives.
    vWorldNormal = vec3(0.0, 0.0, 1.0);
    vCurrentClip = uCurrentStableViewProjection * world;
    vPreviousClip = uPreviousStableViewProjection * previousWorld;
    vPreviousViewDepth = -(uPreviousView * previousWorld).z;
    vPreviousValid = uPreviousValid != 0 && vPreviousClip.w > 0.0 ? 1.0 : 0.0;
    gl_Position = uProjection * uView * world;
}
