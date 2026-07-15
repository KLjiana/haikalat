#version 330 core
out vec4 FragColor;
in vec2 vUv;

uniform sampler2D uSource;
uniform vec2 uSourceTexelSize;
uniform float uThreshold;
uniform float uSoftKnee;

void main() {
    vec2 offset = uSourceTexelSize * 0.5;
    vec3 color = (
        texture(uSource, vUv + vec2(-offset.x, -offset.y)).rgb +
        texture(uSource, vUv + vec2( offset.x, -offset.y)).rgb +
        texture(uSource, vUv + vec2(-offset.x,  offset.y)).rgb +
        texture(uSource, vUv + vec2( offset.x,  offset.y)).rgb
    ) * 0.25;

    float brightness = max(max(color.r, color.g), color.b);
    float knee = max(uThreshold * uSoftKnee, 1.0e-5);
    float soft = clamp(brightness - uThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1.0e-5);
    float contribution = max(brightness - uThreshold, soft) / max(brightness, 1.0e-5);
    FragColor = vec4(color * contribution, 1.0);
}
