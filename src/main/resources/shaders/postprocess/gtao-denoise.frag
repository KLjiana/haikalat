#version 460 core

in vec2 vTexCoord;
layout(location = 0) out float FragVisibility;

uniform sampler2D uInput;
uniform sampler2D uDepth;
uniform sampler2D uNormal;
uniform mat4 uInverseProjection;
uniform vec2 uHalfExtent;
uniform float uDepthReject;

vec3 reconstructView(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    if (any(isnan(view)) || any(isinf(view)) || abs(view.w) < 1.0e-6) {
        return vec3(0.0);
    }
    return view.xyz / view.w;
}

vec3 decodeOctahedral(vec2 encoded) {
    vec3 normal = vec3(encoded * 2.0 - 1.0, 1.0
            - abs(encoded.x * 2.0 - 1.0)
            - abs(encoded.y * 2.0 - 1.0));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx))
                * vec2(normal.x >= 0.0 ? 1.0 : -1.0,
                       normal.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(normal);
}

float normalWeight(vec3 centerNormal, vec3 tapNormal) {
    float similarity = max(dot(centerNormal, tapNormal), 0.0);
    return smoothstep(0.55, 0.98, similarity);
}

void main() {
    float centerDepth = texture(uDepth, vTexCoord).r;
    if (centerDepth >= 0.999999) {
        FragVisibility = 1.0;
        return;
    }
    vec3 centerView = reconstructView(vTexCoord, centerDepth);
    vec3 centerNormal = decodeOctahedral(texture(uNormal, vTexCoord).rg);
    vec2 texel = 1.0 / max(uHalfExtent, vec2(1.0));
    float sum = 0.0;
    float weight = 0.0;
    // One bounded 3x3 bilateral neighborhood replaces the former horizontal
    // and vertical passes.  Center/axis taps receive a small spatial bias,
    // while depth and reconstructed-normal weights keep silhouettes isolated.
    for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            vec2 uv = clamp(vTexCoord + vec2(x, y) * texel,
                    vec2(0.0), vec2(1.0));
            float tapDepth = texture(uDepth, uv).r;
            if (tapDepth >= 0.999999) continue;
            vec3 tapView = reconstructView(uv, tapDepth);
            float depthDifference = abs(tapView.z - centerView.z);
            if (depthDifference > uDepthReject * max(abs(centerView.z), 1.0)) continue;
            vec3 tapNormal = decodeOctahedral(texture(uNormal, uv).rg);
            float edgeWeight = normalWeight(centerNormal, tapNormal);
            float spatialWeight = (x == 0 && y == 0) ? 4.0
                    : (x == 0 || y == 0) ? 2.0 : 1.0;
            float contribution = spatialWeight * edgeWeight;
            sum += texture(uInput, uv).r * contribution;
            weight += contribution;
    }
    FragVisibility = clamp(sum / max(weight, 1.0), 0.0, 1.0);
}
