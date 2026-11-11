#version 460 core

layout(location = 0) in vec3 aPosition;

layout(std140) uniform CameraBlock {
    mat4 uProjection;
    mat4 uView;
};

uniform mat4 uModel;
uniform int uClipSpace;

void main() {
    if (uClipSpace != 0) {
        // Camera-independent control: a quarter-screen quad at the centre.
        gl_Position = vec4(aPosition.xy * 0.5, 0.0, 1.0);
    } else {
        gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);
    }
}
