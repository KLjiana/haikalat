#version 460 core

in vec2 vTexCoord;
layout(location = 0) out float FragVisibility;

uniform sampler2D uInput;
uniform sampler2D uDepth;
uniform sampler2D uNormal;
uniform mat4 uInverseProjection;
uniform vec2 uHalfExtent;
uniform vec2 uFullExtent;
uniform float uDepthReject;

vec3 reconstructView(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    if (any(isnan(view)) || any(isinf(view)) || abs(view.w) < 1.0e-6) {
        return vec3(0.0);
    }
    return view.xyz / view.w;
}

bool finiteView(vec3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

bool insideUv(vec2 uv) {
    return all(greaterThanEqual(uv, vec2(0.0)))
            && all(lessThanEqual(uv, vec2(1.0)));
}

bool continuousNeighbor(vec2 uv, float depth, float centerViewDepth,
                       out vec3 neighborView) {
    neighborView = vec3(0.0);
    if (!insideUv(uv) || depth >= 0.999999) return false;
    neighborView = reconstructView(uv, depth);
    if (!finiteView(neighborView)) return false;
    float tolerance = max(0.01, centerViewDepth * 0.025);
    return abs(abs(neighborView.z) - centerViewDepth) <= tolerance;
}

vec3 reconstructNormal(vec2 uv, float centerDepth, vec3 centerView) {
    vec2 texel = 1.0 / max(uFullExtent, vec2(1.0));
    float centerViewDepth = abs(centerView.z);
    vec2 leftUv = uv - vec2(texel.x, 0.0);
    vec2 rightUv = uv + vec2(texel.x, 0.0);
    vec2 downUv = uv - vec2(0.0, texel.y);
    vec2 upUv = uv + vec2(0.0, texel.y);
    vec3 leftView;
    vec3 rightView;
    vec3 downView;
    vec3 upView;
    bool leftValid = continuousNeighbor(leftUv, insideUv(leftUv)
            ? texture(uDepth, leftUv).r : 1.0, centerViewDepth, leftView);
    bool rightValid = continuousNeighbor(rightUv, insideUv(rightUv)
            ? texture(uDepth, rightUv).r : 1.0, centerViewDepth, rightView);
    bool downValid = continuousNeighbor(downUv, insideUv(downUv)
            ? texture(uDepth, downUv).r : 1.0, centerViewDepth, downView);
    bool upValid = continuousNeighbor(upUv, insideUv(upUv)
            ? texture(uDepth, upUv).r : 1.0, centerViewDepth, upView);
    vec3 tangentX = leftValid && rightValid
            ? rightView - leftView
            : rightValid ? rightView - centerView
            : leftValid ? centerView - leftView
            : vec3(1.0, 0.0, 0.0);
    vec3 tangentY = downValid && upValid
            ? upView - downView
            : upValid ? upView - centerView
            : downValid ? centerView - downView
            : vec3(0.0, 1.0, 0.0);
    vec3 normal = cross(tangentX, tangentY);
    if (!finiteView(normal) || length(normal) < 1.0e-5) {
        return vec3(0.0, 0.0, 1.0);
    }
    normal = normalize(normal);
    return normal.z < 0.0 ? -normal : normal;
}

float normalWeight(vec3 centerNormal, vec3 tapNormal) {
    float similarity = max(dot(centerNormal, tapNormal), 0.0);
    return smoothstep(0.55, 0.98, similarity);
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

void main() {
    float depth = texture(uDepth, vTexCoord).r;
    if (depth >= 0.999999) {
        FragVisibility = 1.0;
        return;
    }
    vec3 centerView = reconstructView(vTexCoord, depth);
    vec3 centerNormal = reconstructNormal(vTexCoord, depth, centerView);
    ivec2 lowSize = max(ivec2(1), ivec2(uHalfExtent));
    vec2 lowSizeFloat = vec2(lowSize);
    // vTexCoord denotes a full-resolution pixel center.  Subtracting 0.5
    // before floor keeps the low-resolution sample centers aligned for both
    // even and odd extents.
    vec2 lowPosition = vTexCoord * lowSizeFloat - 0.5;
    ivec2 base = ivec2(floor(lowPosition));
    vec2 fraction = fract(lowPosition);
    float sum = 0.0;
    float weights = 0.0;
    for (int x = 0; x <= 1; x++) for (int y = 0; y <= 1; y++) {
        ivec2 tap = clamp(base + ivec2(x, y), ivec2(0), lowSize - 1);
        vec2 uv = (vec2(tap) + 0.5) / lowSizeFloat;
        float wx = x == 0 ? 1.0 - fraction.x : fraction.x;
        float wy = y == 0 ? 1.0 - fraction.y : fraction.y;
        float spatialWeight = wx * wy;
        float tapDepth = texture(uDepth, uv).r;
        if (tapDepth >= 0.999999) continue;
        vec3 tapView = reconstructView(uv, tapDepth);
        float depthDifference = abs(tapView.z - centerView.z);
        if (depthDifference > uDepthReject * max(abs(centerView.z), 1.0)) continue;
        float edgeWeight = normalWeight(centerNormal,
                decodeOctahedral(texture(uNormal, uv).rg));
        float weight = spatialWeight * edgeWeight;
        sum += texelFetch(uInput, tap, 0).r * weight;
        weights += weight;
    }
    FragVisibility = weights > 0.0 ? clamp(sum / weights, 0.0, 1.0) : 1.0;
}
