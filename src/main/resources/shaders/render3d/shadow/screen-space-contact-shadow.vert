#version 460 core

// Full-screen quad for the screen-space contact-shadow composite. The pass
// writes into its own colour target, so the vertex stage only needs clip-space
// position; the fragment stage derives UV from gl_FragCoord and the viewport
// uniform.
layout(location = 0) in vec3 aPosition;

void main() {
    gl_Position = vec4(aPosition.xy, 0.0, 1.0);
}
