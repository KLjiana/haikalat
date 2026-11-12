#version 460 core

layout(location = 0) out vec4 fragColor;

void main() {
    // A deliberately simple straight-alpha transparent collector: the blue
    // contribution is independent of any particle colour, so the test can
    // distinguish "collector after particles" from "particles after collector".
    fragColor = vec4(0.0, 0.0, 0.4, 0.5);
}
