#version 460 core

uniform sampler2D uColor;
uniform vec2 uViewportSize;
uniform float uIntensity;
uniform float uRadial;
uniform int uSamples;

layout (location = 0) out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / uViewportSize;
    vec2 centered = uv * 2.0 - 1.0;
    float radius = length(centered) * 0.70710678;
    float amount = uIntensity * mix(1.0, radius, uRadial);
    vec2 direction = centered * amount;
    int samples = clamp(uSamples, 1, 8);
    vec3 red = vec3(0.0);
    vec3 blue = vec3(0.0);
    for (int i = 0; i < samples; i++) {
        float t = (float(i) + 0.5) / float(samples);
        red += texture(uColor, clamp(uv + direction * t, vec2(0.0), vec2(1.0))).rgb;
        blue += texture(uColor, clamp(uv - direction * t, vec2(0.0), vec2(1.0))).rgb;
    }
    red /= float(samples);
    blue /= float(samples);
    vec4 center = texture(uColor, uv);
    fragColor = vec4(red.r, center.g, blue.b, center.a);
}
