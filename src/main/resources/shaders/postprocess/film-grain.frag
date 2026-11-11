#version 460 core

uniform sampler2D uColor;
uniform vec2 uViewportSize;
uniform float uIntensity;
uniform float uResponse;
uniform int uSeed;

layout (location = 0) out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030));
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewportSize;
    vec4 color = texture(uColor, uv);
    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    float midtone = 1.0 - abs(luma * 2.0 - 1.0);
    float response = mix(1.0, midtone, uResponse);
    float noise = hash(floor(gl_FragCoord.xy) + vec2(uSeed * 37.0, uSeed * 17.0));
    color.rgb = clamp(color.rgb + (noise - 0.5) * uIntensity * response, 0.0, 1.0);
    fragColor = color;
}
