#version 330 core
out vec4 FragColor;
in vec2 vUv;

uniform sampler2D uHigh;
uniform sampler2D uLow;
uniform vec2 uLowTexelSize;

void main() {
    vec2 texel = uLowTexelSize;
    vec3 low = texture(uLow, vUv).rgb * 0.25;
    low += texture(uLow, vUv + vec2( texel.x, 0.0)).rgb * 0.125;
    low += texture(uLow, vUv + vec2(-texel.x, 0.0)).rgb * 0.125;
    low += texture(uLow, vUv + vec2(0.0,  texel.y)).rgb * 0.125;
    low += texture(uLow, vUv + vec2(0.0, -texel.y)).rgb * 0.125;
    low += texture(uLow, vUv + vec2( texel.x,  texel.y)).rgb * 0.0625;
    low += texture(uLow, vUv + vec2(-texel.x,  texel.y)).rgb * 0.0625;
    low += texture(uLow, vUv + vec2( texel.x, -texel.y)).rgb * 0.0625;
    low += texture(uLow, vUv + vec2(-texel.x, -texel.y)).rgb * 0.0625;
    FragColor = vec4(texture(uHigh, vUv).rgb + low, 1.0);
}
