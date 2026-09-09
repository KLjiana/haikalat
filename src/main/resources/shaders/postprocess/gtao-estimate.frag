#version 460 core

in vec2 vTexCoord;
layout(location = 0) out float FragVisibility;
layout(location = 1) out vec2 FragNormal;

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
    float neighborViewDepth = abs(neighborView.z);
    // Device depth is non-linear.  Compare reconstructed positive view depth
    // instead, with an absolute floor for near geometry and a relative term
    // for distant geometry.
    float tolerance = max(0.01, centerViewDepth * 0.025);
    return abs(neighborViewDepth - centerViewDepth) <= tolerance;
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
            ? texture(uDepth, leftUv).r : 1.0,
            centerViewDepth, leftView);
    bool rightValid = continuousNeighbor(rightUv, insideUv(rightUv)
            ? texture(uDepth, rightUv).r : 1.0,
            centerViewDepth, rightView);
    bool downValid = continuousNeighbor(downUv, insideUv(downUv)
            ? texture(uDepth, downUv).r : 1.0,
            centerViewDepth, downView);
    bool upValid = continuousNeighbor(upUv, insideUv(upUv)
            ? texture(uDepth, upUv).r : 1.0,
            centerViewDepth, upView);
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
    // Visible surfaces use the camera-facing hemisphere.  Keeping this
    // orientation stable is important because horizon elevation is signed.
    return normal.z < 0.0 ? -normal : normal;
}

vec2 encodeOctahedral(vec3 normal) {
    normal /= max(abs(normal.x) + abs(normal.y) + abs(normal.z), 1.0e-6);
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx))
                * vec2(normal.x >= 0.0 ? 1.0 : -1.0,
                       normal.y >= 0.0 ? 1.0 : -1.0);
    }
    return normal.xy * 0.5 + 0.5;
}

// x is the projected normal length, y its signed angle from the view ray.
vec2 projectSliceNormal(vec3 normal, vec3 viewDirection, vec3 screenDirection) {
    vec3 tangent = normalize(screenDirection
            - viewDirection * dot(screenDirection, viewDirection));
    vec3 sliceAxis = normalize(cross(tangent, viewDirection));
    vec3 projectedNormal = normal - sliceAxis * dot(normal, sliceAxis);
    float projectedLength = length(projectedNormal);
    if (projectedLength < 1.0e-6) return vec2(0.0);
    float normalAngle = atan(dot(projectedNormal, tangent),
            dot(projectedNormal, viewDirection));
    return vec2(projectedLength, normalAngle);
}

float integrateSliceVisibility(vec2 horizonCos, vec2 projectedNormal) {
    float n = projectedNormal.y;
    // Horizons are measured from the view ray; clip them against the surface
    // hemisphere before integrating cos(theta - n) * abs(sin(theta)).
    float h0 = max(-acos(clamp(horizonCos.y, -1.0, 1.0)), n - PI * 0.5);
    float h1 = min( acos(clamp(horizonCos.x, -1.0, 1.0)), n + PI * 0.5);
    float cosN = cos(n);
    float sinN = sin(n);
    float arc0 = (cosN + 2.0 * h0 * sinN - cos(2.0 * h0 - n)) * 0.25;
    float arc1 = (cosN + 2.0 * h1 * sinN - cos(2.0 * h1 - n)) * 0.25;
    return projectedNormal.x * (arc0 + arc1);
}

void main() {
    vec2 fullUv = vTexCoord;
    float centerDepth = texture(uDepth, fullUv).r;
    if (centerDepth >= 0.999999) {
        FragVisibility = 1.0;
        FragNormal = vec2(0.5);
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
    float visibility = 0.0;
    vec3 viewDirection = normalize(-centerView);

    // Horizon-based GTAO: for every screen-space direction retain the highest
    // elevation angle found along the ray, then integrate those horizons over
    // the visible azimuths.  This avoids treating every nearer depth tap as an
    // independent occluder (the old loop was SSAO-style depth counting).
    int sliceCount = max(directionCount / 2, 1);
    for (int slice = 0; slice < 3; slice++) {
        if (slice >= sliceCount) break;
        // Interleaved gradient rotation: temporal frames cover a different
        // deterministic azimuth while single-frame GTAO keeps phase zero.
        // Each slice searches both sides of one axis, so the integral is
        // explicitly two-sided instead of relying on a set of unrelated rays.
        float angle = PI * (float(slice) + 0.5 + uFramePhase)
                / float(sliceCount);
        vec2 axis = vec2(cos(angle), sin(angle));
        vec2 projectedNormal = projectSliceNormal(normal, viewDirection, vec3(axis, 0.0));
        // Visible geometry must face the view ray, including off-center pixels.
        // Invalid/degenerate reconstructed normals contribute no slice energy.
        if (projectedNormal.x < 1.0e-6) continue;
        vec2 lowHorizon = cos(projectedNormal.y + vec2(PI * 0.5, -PI * 0.5));
        vec2 horizon = lowHorizon;
        for (int side = 0; side < 2; side++) {
            vec2 sideAxis = side == 0 ? axis : -axis;
            for (int step = 1; step <= 6; step++) {
                if (step > stepCount) break;
                float distance = pixelRadius * float(step) / float(stepCount);
                vec2 sampleUv = fullUv + sideAxis * distance / uFullExtent;
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
                float horizonCos = dot(viewDirection, sampleDirection / sampleDistance);
                float thicknessBias = uThickness / max(sampleDistance, 1.0e-3);
                float distanceFade = 1.0 - smoothstep(uRadius * 0.2,
                        max(uRadius, uRadius * 1.001), sampleDistance);
                horizon[side] = max(horizon[side],
                        mix(lowHorizon[side],
                            clamp(horizonCos - thicknessBias, -1.0, 1.0), distanceFade));
            }
        }
        visibility += integrateSliceVisibility(horizon, projectedNormal);
    }
    float amount = 1.0 - clamp(visibility / float(sliceCount), 0.0, 1.0);
    FragVisibility = clamp(1.0 - amount * clamp(uStrength, 0.0, 4.0), 0.0, 1.0);
    FragNormal = encodeOctahedral(normal);
}
