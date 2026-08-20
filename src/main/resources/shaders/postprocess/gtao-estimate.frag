#version 460 core

in vec2 vTexCoord;
layout(location = 0) out float FragVisibility;

uniform sampler2D uDepth;
uniform mat4 uInverseViewProjection;
uniform mat4 uInverseProjection;
uniform float uRadius;
uniform float uStrength;
uniform float uThickness;
uniform float uProjectionScaleY;
uniform int uDirections;
uniform int uSteps;
uniform float uFramePhase;
uniform vec2 uFullExtent;

const float PI = 3.14159265359;
const float HALF_PI = 1.57079632679;

vec3 reconstructWorld(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = uInverseViewProjection * clip;
    if (any(isnan(world)) || any(isinf(world)) || abs(world.w) < 1.0e-6) {
        return vec3(0.0);
    }
    return world.xyz / world.w;
}

vec3 reconstructView(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    if (any(isnan(view)) || any(isinf(view)) || abs(view.w) < 1.0e-6) {
        return vec3(0.0);
    }
    return view.xyz / view.w;
}

vec3 reconstructNormal(vec2 uv, float centerDepth, vec3 centerView) {
    vec2 texel = 1.0 / max(uFullExtent, vec2(1.0));
    float leftDepth = texture(uDepth, clamp(uv + vec2(-texel.x, 0.0),
            vec2(0.0), vec2(1.0))).r;
    float rightDepth = texture(uDepth, clamp(uv + vec2(texel.x, 0.0),
            vec2(0.0), vec2(1.0))).r;
    float downDepth = texture(uDepth, clamp(uv + vec2(0.0, -texel.y),
            vec2(0.0), vec2(1.0))).r;
    float upDepth = texture(uDepth, clamp(uv + vec2(0.0, texel.y),
            vec2(0.0), vec2(1.0))).r;
    bool leftValid = leftDepth < 0.999999 && abs(leftDepth - centerDepth) < 0.08;
    bool rightValid = rightDepth < 0.999999 && abs(rightDepth - centerDepth) < 0.08;
    bool downValid = downDepth < 0.999999 && abs(downDepth - centerDepth) < 0.08;
    bool upValid = upDepth < 0.999999 && abs(upDepth - centerDepth) < 0.08;
    vec3 tangentX = leftValid && rightValid
            ? reconstructView(uv + vec2(texel.x, 0.0), rightDepth)
                - reconstructView(uv - vec2(texel.x, 0.0), leftDepth)
            : rightValid ? reconstructView(uv + vec2(texel.x, 0.0), rightDepth) - centerView
            : leftValid ? centerView - reconstructView(uv - vec2(texel.x, 0.0), leftDepth)
            : vec3(1.0, 0.0, 0.0);
    vec3 tangentY = downValid && upValid
            ? reconstructView(uv + vec2(0.0, texel.y), upDepth)
                - reconstructView(uv - vec2(0.0, texel.y), downDepth)
            : upValid ? reconstructView(uv + vec2(0.0, texel.y), upDepth) - centerView
            : downValid ? centerView - reconstructView(uv - vec2(0.0, texel.y), downDepth)
            : vec3(0.0, 1.0, 0.0);
    vec3 normal = cross(tangentX, tangentY);
    if (any(isnan(normal)) || any(isinf(normal)) || length(normal) < 1.0e-5) {
        return vec3(0.0, 0.0, 1.0);
    }
    normal = normalize(normal);
    // Visible surfaces use the camera-facing hemisphere.  Keeping this
    // orientation stable is important because horizon elevation is signed.
    return normal.z < 0.0 ? -normal : normal;
}

void main() {
    vec2 fullUv = vTexCoord;
    float centerDepth = texture(uDepth, fullUv).r;
    if (centerDepth >= 0.999999) {
        FragVisibility = 1.0;
        return;
    }
    vec3 centerView = reconstructView(fullUv, centerDepth);
    vec3 normal = reconstructNormal(fullUv, centerDepth, centerView);
    int directionCount = clamp(uDirections, 2, 6);
    int stepCount = clamp(uSteps, 1, 6);
    float projectedRadius = uRadius * 0.5 * uFullExtent.y * uProjectionScaleY
            / max(abs(centerView.z), 0.05);
    float maxPixelRadius = max(8.0, uFullExtent.y * 0.22);
    float pixelRadius = clamp(projectedRadius, 1.0, maxPixelRadius);
    float occlusion = 0.0;

    // Horizon-based GTAO: for every screen-space direction retain the highest
    // elevation angle found along the ray, then integrate those horizons over
    // the visible azimuths.  This avoids treating every nearer depth tap as an
    // independent occluder (the old loop was SSAO-style depth counting).
    for (int direction = 0; direction < 6; direction++) {
        if (direction >= directionCount) break;
        // Interleaved gradient rotation: temporal frames cover a different
        // deterministic azimuth while single-frame GTAO keeps phase zero.
        float angle = 2.0 * PI * (float(direction) + 0.5 + uFramePhase)
                / float(directionCount);
        vec2 axis = vec2(cos(angle), sin(angle));
        float maxHorizon = 0.0;
        bool foundSample = false;
        for (int step = 1; step <= 6; step++) {
            if (step > stepCount) break;
            float distance = pixelRadius * float(step) / float(stepCount);
            vec2 sampleUv = fullUv + axis * distance / uFullExtent;
            if (any(lessThan(sampleUv, vec2(0.0)))
                    || any(greaterThan(sampleUv, vec2(1.0)))) {
                continue;
            }
            float sampleDepth = texture(uDepth, sampleUv).r;
            if (sampleDepth >= 0.999999) continue;
            vec3 sampleView = reconstructView(sampleUv, sampleDepth);
            vec3 sampleDirection = sampleView - centerView;
            float sampleDistance = length(sampleDirection);
            if (sampleDistance <= 1.0e-4) continue;
            foundSample = true;
            float elevation = dot(normal, sampleDirection / sampleDistance);
            float thicknessBias = uThickness / max(sampleDistance, 1.0e-3);
            float distanceFade = 1.0 - smoothstep(uRadius * 0.2,
                    max(uRadius, uRadius * 1.001), sampleDistance);
            maxHorizon = max(maxHorizon,
                    clamp(elevation - thicknessBias, 0.0, 1.0) * distanceFade);
        }
        float horizonAngle = foundSample ? asin(clamp(maxHorizon, 0.0, 1.0)) : 0.0;
        occlusion += clamp(horizonAngle / HALF_PI, 0.0, 1.0);
    }
    float amount = occlusion / float(directionCount);
    FragVisibility = clamp(1.0 - amount * clamp(uStrength, 0.0, 4.0), 0.0, 1.0);
}
