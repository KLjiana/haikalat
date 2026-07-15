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

vec3 acesFitted(vec3 color) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return (color * (a * color + b)) / (color * (c * color + d) + e);
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
    vec3 displayColor = pow(mapped, vec3(1.0 / 2.2));
    FragColor = vec4(displayColor, 1.0);
}
