#version 460 core

// Dynamic DDGI probe relight. For every irradiance texel this stage casts one
// world-space ray from the probe centre through the rasterized probe depth
// atlas, evaluates a bounded analytic surface radiance at the nearest recorded
// hit and blends the result with the previous atlas value (hysteresis).
//
// The shared atlas layout, face basis, probe depth sampling and trilinear probe
// weighting come from ddgi-common.glsl, injected by DdgiShaderSource.
//
// This is not a static bake and not a hardware ray-tracing path: visibility
// comes from six rasterized depth faces per probe, so the result updates every
// time the probe is scheduled. Known bounded approximations are documented in
// docs/planning/v0.28.1-s5-ddgi.md (normal from depth neighbours, one ray per
// texel, analytic main light only, no emissive material capture, no instanced
// geometry).

uniform int uProbeIndex;
uniform int uFaceIndex;
uniform vec2 uTileOrigin;
uniform int uRaySteps;
uniform float uMaximumRayDistance;
uniform float uHysteresis;
uniform vec3 uSkyRadiance;
uniform vec3 uLightDirection;
uniform vec3 uLightRadiance;
uniform float uSurfaceAlbedo;
uniform float uMaximumIrradiance;

out vec4 fragColor;

// Reconstructs one depth-atlas texel position in world space. Cleared depth
// texels report "no surface" and return the caller's fallback position.
vec3 ddgiDepthPosition(int probe, int face, ivec2 texel, vec3 fallback) {
    ivec2 clamped = clamp(texel, ivec2(0), ivec2(uDepthFaceResolution - 1));
    float resolution = float(uDepthFaceResolution);
    vec2 uv = (vec2(clamped) + 0.5) / resolution;
    ivec2 coordinate = ivec2(
            (probe * 6 + face) * uDepthFaceResolution + clamped.x, clamped.y);
    float sampled = texelFetch(uProbeDepthAtlas, coordinate, 0).r;
    float linearDepth = ddgiLinearDepth(sampled);
    if (linearDepth >= uDepthFar * 0.999) {
        return fallback;
    }
    vec3 direction = ddgiFaceVector(face, uv);
    float axis = max(abs(dot(direction, ddgiFaceForward(face))), 1.0e-4);
    return ddgiProbePosition(probe) + direction * (linearDepth / axis);
}

// Conservative surface normal from the probe depth neighbours. Degenerate or
// cleared neighbours fall back to facing the probe, which keeps the direct
// light term bounded instead of producing NaN.
vec3 ddgiHitNormal(int probe, int face, vec2 uv, vec3 rayDirection, vec3 hitPosition) {
    float resolution = float(uDepthFaceResolution);
    ivec2 center = ivec2(clamp(uv, vec2(0.0), vec2(0.999999)) * resolution);
    ivec2 right = clamp(center + ivec2(1, 0), ivec2(0),
            ivec2(uDepthFaceResolution - 1, uDepthFaceResolution - 1));
    ivec2 up = clamp(center + ivec2(0, 1), ivec2(0),
            ivec2(uDepthFaceResolution - 1, uDepthFaceResolution - 1));
    vec3 rightPosition = ddgiDepthPosition(probe, face, right, hitPosition);
    vec3 upPosition = ddgiDepthPosition(probe, face, up, hitPosition);
    vec3 normal = cross(rightPosition - hitPosition, upPosition - hitPosition);
    if (!(dot(normal, normal) > 1.0e-12)) {
        return -rayDirection;
    }
    normal = normalize(normal);
    if (dot(normal, rayDirection) > 0.0) {
        normal = -normal;
    }
    if (any(isnan(normal)) || any(isinf(normal))) {
        return -rayDirection;
    }
    return normal;
}

vec3 ddgiPreviousTexel(int probe, int face, vec2 uv) {
    float resolution = float(uIrradianceFaceResolution);
    ivec2 local = clamp(ivec2(uv * resolution), ivec2(0),
            ivec2(uIrradianceFaceResolution - 1));
    return ddgiAtlasTexel(probe, face, local);
}

void main() {
    float resolution = float(uIrradianceFaceResolution);
    vec2 localUv = (gl_FragCoord.xy - uTileOrigin) / resolution;
    vec2 clampedUv = clamp(localUv, vec2(0.0), vec2(1.0));
    vec3 rayDirection = ddgiFaceVector(uFaceIndex, clampedUv);
    vec3 probePosition = ddgiProbePosition(uProbeIndex);

    float nearPlane = uDepthNear;
    float marchDistance = max(uMaximumRayDistance, nearPlane + 1.0e-3);
    int steps = max(uRaySteps, 1);
    vec3 incident = uSkyRadiance;
    vec3 lightDirection = normalize(uLightDirection);

    for (int step = 1; step <= steps; step++) {
        float distanceAlongRay = nearPlane
                + (marchDistance - nearPlane) * (float(step) / float(steps));
        vec3 samplePosition = probePosition + rayDirection * distanceAlongRay;
        float recorded = ddgiProbeDepthDistance(uProbeIndex,
                samplePosition - probePosition);
        if (recorded < distanceAlongRay) {
            float hitDistance = clamp(recorded, nearPlane, marchDistance);
            vec3 hitPosition = probePosition + rayDirection * hitDistance;
            // The depth atlas is indexed by the ray's dominant-axis face, not
            // by the irradiance tile face that spawned this ray.
            int depthFace = ddgiFaceIndex(rayDirection);
            vec2 depthUv = ddgiFaceUv(depthFace, rayDirection);
            vec3 normal = ddgiHitNormal(uProbeIndex, depthFace, depthUv,
                    rayDirection, hitPosition);
            float incidence = max(dot(normal, -lightDirection), 0.0);
            vec3 direct = uSurfaceAlbedo * (incidence / 3.14159265359) * uLightRadiance;
            vec3 bounce = uSurfaceAlbedo
                    * ddgiSampleIrradiance(hitPosition, -rayDirection, false);
            incident = direct + bounce;
            break;
        }
    }

    vec3 previous = ddgiPreviousTexel(uProbeIndex, uFaceIndex, localUv);
    vec3 blended = mix(incident, previous, clamp(uHysteresis, 0.0, 1.0));
    if (any(isnan(blended)) || any(isinf(blended))) {
        blended = vec3(0.0);
    }
    blended = clamp(blended, vec3(0.0), vec3(uMaximumIrradiance));
    fragColor = vec4(blended, 1.0);
}
