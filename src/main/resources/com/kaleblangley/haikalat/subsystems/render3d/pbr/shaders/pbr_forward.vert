#version 460 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in vec3 aNormal;
layout(location = 3) in vec4 aTangent;

layout(std140) uniform CameraBlock {
    mat4 uProjection;
    mat4 uView;
};

uniform mat4 uModel;
uniform mat4 uDirectionalLightSpace;

out vec2 vTexCoord;
out vec3 vWorldPosition;
out vec3 vNormal;
out vec3 vTangent;
out float vTangentHandedness;
out vec4 vDirectionalLightPosition;

void main() {
    vec4 world = uModel * vec4(aPosition, 1.0);
    mat3 normalMatrix = transpose(inverse(mat3(uModel)));
    vec3 n = normalize(normalMatrix * aNormal);
    vec3 t = normalize(normalMatrix * aTangent.xyz);
    vTexCoord = aTexCoord;
    vWorldPosition = world.xyz;
    vNormal = n;
    vTangent = normalize(t - n * dot(n, t));
    vTangentHandedness = aTangent.w;
    vDirectionalLightPosition = uDirectionalLightSpace * world;
    gl_Position = uProjection * uView * world;
}
