#version 330 core
out vec4 FragColor;
in vec2 vUv;

uniform sampler2D uHdrScene;
uniform sampler2D uBloom;
uniform int uBloomEnabled;
uniform float uExposure;
uniform sampler2D uExposureTexture;
uniform int uAutoExposure;
uniform float uBloomIntensity;
uniform sampler2D uColorGradingLut;
uniform int uColorGradingEnabled;
uniform float uColorGradingIntensity;
uniform float uColorGradingSize;

vec3 acesFitted(vec3 color) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return (color * (a * color + b)) / (color * (c * color + d) + e);
}

vec3 sampleColorGradingLut(vec3 color) {
    float blue = clamp(color.b, 0.0, 1.0) * (uColorGradingSize - 1.0);
    float blue0 = floor(blue);
    float blue1 = min(blue0 + 1.0, uColorGradingSize - 1.0);
    float width = uColorGradingSize * uColorGradingSize;
    vec2 withinSlice = vec2(clamp(color.rg, 0.0, 1.0)
            * (uColorGradingSize - 1.0) + 0.5);
    vec2 uv0 = vec2(blue0 * uColorGradingSize + withinSlice.x,
            withinSlice.y) / vec2(width, uColorGradingSize);
    vec2 uv1 = vec2(blue1 * uColorGradingSize + withinSlice.x,
            withinSlice.y) / vec2(width, uColorGradingSize);
    return mix(texture(uColorGradingLut, uv0).rgb,
            texture(uColorGradingLut, uv1).rgb, fract(blue));
}

void main() {
    vec3 hdrColor = texture(uHdrScene, vUv).rgb;
    if (uBloomEnabled != 0) {
        hdrColor += texture(uBloom, vUv).rgb * uBloomIntensity;
    }
    float exposure = uAutoExposure != 0
            ? texelFetch(uExposureTexture, ivec2(0), 0).r
            : uExposure;
    vec3 exposed = hdrColor * exposure;
    vec3 mapped = clamp(acesFitted(exposed), 0.0, 1.0);
    if (uColorGradingEnabled != 0) {
        mapped = mix(mapped, sampleColorGradingLut(mapped), uColorGradingIntensity);
    }
    vec3 displayColor = pow(mapped, vec3(1.0 / 2.2));
    FragColor = vec4(displayColor, 1.0);
}
