#version 330 core
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
layout (location = 3) in mat4 aInstanceMatrix;
out vec3 vColor;
layout (std140) uniform CameraBlock {
    mat4 uProjection;
    mat4 uView;
};
void main() {
    vColor = mix(vec3(1, 0, 0), vec3(0, 1, 0), float(gl_InstanceID % 4) / 3.0);
    gl_Position = uProjection * uView * aInstanceMatrix * vec4(aPos, 1.0);
}
