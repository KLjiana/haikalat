#version 330 core
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
out vec3 vColor;
layout (std140) uniform CameraBlock {
    mat4 uProjection;
    mat4 uView;
};
uniform mat4 uModel;
void main() {
    vColor = aColor;
    gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0);
}
