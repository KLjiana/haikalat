#version 330 core
out vec4 FragColor;
in vec2 vUv;

uniform sampler2D uHdrScene;
uniform float uExposure;

vec3 acesFitted(vec3 color) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return (color * (a * color + b)) / (color * (c * color + d) + e);
}

void main() {
    vec3 exposed = texture(uHdrScene, vUv).rgb * uExposure;
    vec3 mapped = clamp(acesFitted(exposed), 0.0, 1.0);
    vec3 displayColor = pow(mapped, vec3(1.0 / 2.2));
    FragColor = vec4(displayColor, 1.0);
}
