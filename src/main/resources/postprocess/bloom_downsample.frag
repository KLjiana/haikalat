#version 330 core
out vec4 FragColor;
in vec2 vUv;

uniform sampler2D uSource;
uniform vec2 uSourceTexelSize;

void main() {
    vec2 texel = uSourceTexelSize;
    vec3 color = texture(uSource, vUv).rgb * 0.25;
    color += texture(uSource, vUv + vec2( texel.x, 0.0)).rgb * 0.125;
    color += texture(uSource, vUv + vec2(-texel.x, 0.0)).rgb * 0.125;
    color += texture(uSource, vUv + vec2(0.0,  texel.y)).rgb * 0.125;
    color += texture(uSource, vUv + vec2(0.0, -texel.y)).rgb * 0.125;
    color += texture(uSource, vUv + vec2( texel.x,  texel.y)).rgb * 0.0625;
    color += texture(uSource, vUv + vec2(-texel.x,  texel.y)).rgb * 0.0625;
    color += texture(uSource, vUv + vec2( texel.x, -texel.y)).rgb * 0.0625;
    color += texture(uSource, vUv + vec2(-texel.x, -texel.y)).rgb * 0.0625;
    FragColor = vec4(color, 1.0);
}
