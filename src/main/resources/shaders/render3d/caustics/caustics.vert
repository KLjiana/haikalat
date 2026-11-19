#version 460 core

// Full-screen quad for the bounded water-caustics projection. The pass draws
// into its own colour target, so the vertex stage only needs clip-space
// position; no UV attribute is stored because the fragment pass derives UV from
// gl_FragCoord and the viewport uniform.
layout(location = 0) in vec3 aPosition;

void main() {
    gl_Position = vec4(aPosition.xy, 0.0, 1.0);
}
