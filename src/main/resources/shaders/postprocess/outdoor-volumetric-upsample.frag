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
    ivec2 volumeSize = textureSize(uVolumeColor, 0);
    vec2 samplePosition = vUv / uVolumeTexel - 0.5;
    ivec2 base = ivec2(floor(samplePosition));
    vec2 fraction = fract(samplePosition);
    // Four taps and a depth weight retain silhouettes at MASK/opaque edges
    // without paying for a full-resolution volume march.
    for (int y = 0; y <= 1; ++y) for (int x = 0; x <= 1; ++x) {
        ivec2 pixel = clamp(base + ivec2(x,y), ivec2(0), volumeSize - 1);
        vec2 uv = (vec2(pixel) + 0.5) * uVolumeTexel;
        vec4 sampleValue = texelFetch(uVolumeColor, pixel, 0);
        float sampleDepth = texture(uSceneDepth, uv).r;
        float depthWeight = exp(-abs(sampleDepth - centerDepth) / max(1e-5, (1.0 - centerDepth) * 0.025));
        float spatialWeight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                * (y == 0 ? 1.0 - fraction.y : fraction.y);
        float weight = max(depthWeight, 1.0e-5) * spatialWeight;
        scattering += sampleValue.rgb * weight;
        transmittance += sampleValue.a * weight;
        weightSum += weight;
    }
    scattering /= weightSum;
    transmittance /= weightSum;
    fragColor = vec4(scene * transmittance + scattering, 1.0);
}
