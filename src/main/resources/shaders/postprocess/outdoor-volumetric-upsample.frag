#version 460 core

layout (location = 0) in vec2 vUv;
layout (location = 0) out vec4 fragColor;
uniform sampler2D uSceneColor;
uniform sampler2D uVolumeColor;
uniform sampler2D uSceneDepth;
uniform vec2 uVolumeTexel;

void main() {
    vec3 scene = texture(uSceneColor, vUv).rgb;
    float centerDepth = texture(uSceneDepth, vUv).r;
    vec3 scattering = vec3(0.0);
    float transmittance = 0.0;
    float weightSum = 0.0;
    // Four taps and a depth weight retain silhouettes at MASK/opaque edges
    // without paying for a full-resolution volume march.
    for (int y = 0; y <= 1; ++y) for (int x = 0; x <= 1; ++x) {
        vec2 uv = vUv + (vec2(x, y) - 0.5) * uVolumeTexel;
        vec4 sampleValue = texture(uVolumeColor, uv);
        float sampleDepth = texture(uSceneDepth, uv).r;
        float depthWeight = exp(-abs(sampleDepth - centerDepth) * 80.0);
        float weight = max(depthWeight, 1.0e-3);
        scattering += sampleValue.rgb * weight;
        transmittance += sampleValue.a * weight;
        weightSum += weight;
    }
    scattering /= weightSum;
    transmittance /= weightSum;
    fragColor = vec4(scene * transmittance + scattering, 1.0);
}
