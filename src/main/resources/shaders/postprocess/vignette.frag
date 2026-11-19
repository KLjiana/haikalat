#version 460 core

uniform sampler2D uColor;
uniform vec2 uViewportSize;
uniform float uIntensity;
uniform float uSmoothness;
uniform float uRoundness;

layout (location = 0) out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / uViewportSize;
    vec2 centered = uv * 2.0 - 1.0;
    // Compare radial and box distance in an aspect-corrected space so a
    // non-square viewport does not produce a horizontally stretched vignette.
    float aspect = uViewportSize.x / max(uViewportSize.y, 1.0);
    vec2 corrected = centered * vec2(aspect, 1.0);
    float radial = length(corrected) / length(vec2(aspect, 1.0));
    float box = max(abs(corrected.x) / aspect, abs(corrected.y));
    float distance = mix(radial, box, uRoundness);
    float width = max(uSmoothness, 0.001);
    float edge = smoothstep(1.0 - width, 1.0, distance);
    vec4 color = texture(uColor, uv);
    color.rgb *= 1.0 - uIntensity * edge;
    fragColor = color;
}
