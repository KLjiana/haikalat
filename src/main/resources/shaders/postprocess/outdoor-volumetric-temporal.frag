#version 460 core

layout (location = 0) in vec2 vUv;
layout (location = 0) out vec4 fragColor;
uniform sampler2D uCurrent;
uniform sampler2D uHistory;
uniform sampler2D uDepth;
uniform sampler2D uHistoryDepth;
uniform float uHistoryWeight;
uniform float uDepthRejectThreshold;
uniform mat4 uInverseViewProjection;
uniform mat4 uPreviousViewProjection;
uniform int uHistoryValid;

vec3 reconstructWorld(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = uInverseViewProjection * clip;
    return world.xyz / max(abs(world.w), 1.0e-6);
}

void main() {
    vec4 current = texture(uCurrent, vUv);
    float depth = texture(uDepth, vUv).r;
    vec4 minimumValue = current;
    vec4 maximumValue = current;
    for (int y = -1; y <= 1; ++y) for (int x = -1; x <= 1; ++x) {
        vec4 neighbour = texture(uCurrent, vUv + vec2(x, y) / vec2(textureSize(uCurrent, 0)));
        minimumValue = min(minimumValue, neighbour);
        maximumValue = max(maximumValue, neighbour);
    }
    vec4 clampedHistory = current;
    float weight = 0.0;
    if (uHistoryValid != 0 && depth < 0.999999) {
        vec3 world = reconstructWorld(vUv, depth);
        vec4 previousClip = uPreviousViewProjection * vec4(world, 1.0);
        bool finiteClip = !any(isnan(previousClip)) && !any(isinf(previousClip));
        if (finiteClip && previousClip.w > 0.0) {
            vec2 historyUv = previousClip.xy / previousClip.w * 0.5 + 0.5;
            if (all(greaterThanEqual(historyUv, vec2(0.0)))
                    && all(lessThanEqual(historyUv, vec2(1.0)))) {
                vec4 history = texture(uHistory, historyUv);
                float historyDepth = texture(uHistoryDepth, historyUv).r;
                float expectedDepth = previousClip.z / previousClip.w * 0.5 + 0.5;
                float reject = step(uDepthRejectThreshold,
                        abs(expectedDepth - historyDepth));
                clampedHistory = clamp(history, minimumValue, maximumValue);
                weight = mix(uHistoryWeight, 0.0, reject);
            }
        }
    }
    fragColor = mix(current, clampedHistory, clamp(weight, 0.0, 0.98));
}
