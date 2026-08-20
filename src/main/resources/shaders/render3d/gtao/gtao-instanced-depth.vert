#version 460 core

layout(location = 0) in vec3 aPosition;
layout(location = 3) in mat4 aInstanceMatrix;
layout(std140) uniform CameraBlock { mat4 uProjection; mat4 uView; };

void main() {
    gl_Position = uProjection * uView * aInstanceMatrix * vec4(aPosition, 1.0);
}
