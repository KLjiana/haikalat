#version 460 core

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec2 aLocal;

uniform vec2 uViewport;

out vec2 vUv;
out vec4 vColor;
out vec2 vLocal;

void main() {
    vec2 normalized = aPosition / uViewport;
    gl_Position = vec4(normalized.x * 2.0 - 1.0,
                       1.0 - normalized.y * 2.0,
                       0.0, 1.0);
    vUv = aUv;
    vColor = aColor;
    vLocal = aLocal;
}
