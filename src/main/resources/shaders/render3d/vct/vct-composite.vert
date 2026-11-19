#version 460 core

// Full-screen vertex stage for the HDR indirect composite. The ScreenQuad
// supplies location 0 (position) and location 1 (texcoord).

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

out vec2 vUv;

void main() {
    vUv = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
