#version 460 core

// Full-screen triangle for the bounded underwater volume pass (#11/#30).
// The vertex stage only emits clip-space positions; every fragment reconstructs
// its world position from the single-sample scene depth.
layout(location = 0) in vec3 aPosition;

void main() {
    gl_Position = vec4(aPosition.xy, 0.0, 1.0);
}
