#version 460 core

in vec2 vTexCoord;
layout(location = 0) out vec2 FragTemporal;

uniform sampler2D uRaw;
uniform sampler2D uDepth;
uniform sampler2D uHistory;
uniform mat4 uInverseViewProjection;
uniform mat4 uInverseProjection;
uniform mat4 uPreviousViewProjection;
uniform mat4 uPreviousInverseProjection;
uniform float uHistoryWeight;
uniform float uDepthReject;
uniform float uNearPlane;
uniform float uFarPlane;
uniform int uHistoryValid;
uniform vec2 uHalfExtent;

vec3 reconstructWorld(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = uInverseViewProjection * clip;
    return world.xyz / max(abs(world.w), 1.0e-6);
}

// History stores normalized camera-space depth, not world-space Z.  World Z
// remains unchanged when the camera rotates/translates, so it cannot reject a
// disoccluded sample reliably.
float normalizedLinearDepth(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    float viewDepth = abs(view.z / max(abs(view.w), 1.0e-6));
    return clamp((viewDepth - uNearPlane) / max(uFarPlane - uNearPlane, 1.0e-4),
            0.0, 1.0);
}

float normalizedPreviousLinearDepth(vec4 previousClip) {
    vec4 view = uPreviousInverseProjection * previousClip;
    float viewDepth = abs(view.z / max(abs(view.w), 1.0e-6));
    return clamp((viewDepth - uNearPlane) / max(uFarPlane - uNearPlane, 1.0e-4),
            0.0, 1.0);
}

void main() {
    float currentDepth = texture(uDepth, vTexCoord).r;
    float currentAo = texture(uRaw, vTexCoord).r;
    float depthValue = currentDepth >= 0.999999
            ? 1.0 : normalizedLinearDepth(vTexCoord, currentDepth);
    float historyAo = currentAo;
    float weight = 0.0;
    if (uHistoryValid != 0 && currentDepth < 0.999999) {
        vec3 world = reconstructWorld(vTexCoord, currentDepth);
        vec4 previousClip = uPreviousViewProjection * vec4(world, 1.0);
        bool finiteClip = !any(isnan(previousClip)) && !any(isinf(previousClip));
        if (previousClip.w > 0.0 && finiteClip) {
            vec2 previousUv = previousClip.xy / previousClip.w * 0.5 + 0.5;
            if (all(greaterThanEqual(previousUv, vec2(0.0)))
                    && all(lessThanEqual(previousUv, vec2(1.0)))) {
                vec2 texel = 1.0 / max(uHalfExtent, vec2(1.0));
                vec2 minimumAo = vec2(1.0);
                vec2 maximumAo = vec2(0.0);
                for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
                    float value = texture(uRaw, clamp(vTexCoord + vec2(x, y) * texel,
                            vec2(0.0), vec2(1.0))).r;
                    minimumAo.x = min(minimumAo.x, value);
                    maximumAo.x = max(maximumAo.x, value);
                }
                vec2 history = texture(uHistory, previousUv).rg;
                float expectedDepth = normalizedPreviousLinearDepth(previousClip);
                float depthDelta = abs(history.g - expectedDepth);
                // Keep the public threshold normalized, but scale it slightly at
                // far depth so quantization does not reject every stable sample.
                float reject = uDepthReject * max(1.0, expectedDepth * 4.0);
                if (depthDelta <= reject) {
                    historyAo = clamp(history.r, minimumAo.x, maximumAo.x);
                    weight = clamp(uHistoryWeight, 0.0, 0.98);
                }
            }
        }
    }
    FragTemporal = vec2(mix(currentAo, historyAo, weight), depthValue);
}
