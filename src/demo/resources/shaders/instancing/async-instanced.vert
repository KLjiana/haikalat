#version 330 core
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
out vec3 vColor;

layout (std140) uniform AsyncInstances {
    mat4 uInstanceMatrices[16];
};
uniform mat4 uProjView;

void main() {
    vColor = aColor;
    gl_Position = uProjView * uInstanceMatrices[gl_InstanceID] * vec4(aPos, 1.0);
}
