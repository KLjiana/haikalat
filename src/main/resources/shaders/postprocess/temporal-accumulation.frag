#version 330 core
out vec4 FragColor;
in vec2 vUv;
uniform sampler2D uCurrent;
uniform sampler2D uHistory;
uniform float uHistoryWeight;
void main() {
    vec3 current = texture(uCurrent, vUv).rgb;
    vec3 history = texture(uHistory, vUv).rgb;
    FragColor = vec4(mix(current, history, clamp(uHistoryWeight, 0.0, 0.95)), 1.0);
}
