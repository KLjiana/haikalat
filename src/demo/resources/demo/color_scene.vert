#version 330 core
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
layout (location = 2) in vec3 aNormal;
out vec3 vColor;
out vec3 vWorldPosition;
out vec3 vNormal;
out vec4 vDirectionalLightPosition;
layout (std140) uniform CameraBlock {
    mat4 uProjection;
    mat4 uView;
};
uniform mat4 uModel;
uniform mat4 uDirectionalLightSpace;
void main() {
    vec4 worldPosition = uModel * vec4(aPos, 1.0);
    vColor = aColor;
    vWorldPosition = worldPosition.xyz;
    vNormal = mat3(transpose(inverse(uModel))) * aNormal;
    vDirectionalLightPosition = uDirectionalLightSpace * worldPosition;
    gl_Position = uProjection * uView * worldPosition;
}
