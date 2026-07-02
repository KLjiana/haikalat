#version 330 core
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
layout (location = 2) in mat4 aInstanceMatrix;
out vec3 vColor;
uniform mat4 uProjection;
uniform mat4 uView;
void main() {
    float band = float(gl_InstanceID % 4) / 3.0;
    vColor = mix(vec3(1.0, 0.92, 0.18), vec3(0.1, 0.9, 1.0), band);
    gl_Position = uProjection * uView * aInstanceMatrix * vec4(aPos, 1.0);
}
