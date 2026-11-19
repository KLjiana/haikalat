#version 460 core
// Bounded volumetric cloud layer, low-resolution view march.
//
// The vertical band [uLayerBase, uLayerTop] is the only participating medium.
// The shared #10 mask sets horizontal coverage. The optional 3D body and erosion
// shape the density seen by both the view and the light march.
// Output is premultiplied scatter in rgb and remaining scene weight in a. Each
// segment contributes T_i * (1 - exp(-sigma_i * delta)) to opacity and scatters
// sunlight (optional higher orders) plus optional diffuse sky fill. Aerial perspective weights both
// contributions toward zero with distance; it does not change physical T_i.
// The pass never samples the scene colour here; compositing happens after the
// low-resolution result has been reconstructed.
layout (binding = 0) uniform sampler2D uSceneDepth;

uniform vec2 uFullExtent;
uniform vec2 uLowExtent;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform float uLayerBase;
uniform float uLayerTop;
uniform vec3 uLightDirection;
uniform vec3 uLightColor;
uniform float uShapeDetail;
uniform float uCloudBodyStrength;
uniform float uMultipleScatteringStrength;
uniform vec3 uSkyLightColor;
uniform float uAerialPerspectiveDistance;
uniform float uDensity;
uniform float uAbsorption;
uniform float uScattering;
uniform float uAnisotropy;
uniform float uStepSizeMetres;
uniform int uMaximumSteps;
uniform int uLightSteps;
uniform float uMaximumLightDistance;
uniform float uCloudShadowCoverage;
uniform float uCloudShadowSoftness;
uniform float uCloudShadowScale;
uniform float uCloudShadowSpeed;
uniform float uCloudShadowTime;

layout (location = 0) out vec4 outCloud;

#define CLOUD_MAX_OPTICAL_DEPTH 88.0
#define CLOUD_MAX_HORIZONTAL_DISTANCE 30000.0

// CLOUD_MODEL_HELPERS

// Distance from the near-plane ray origin to the opaque surface, sampled at the
// full-resolution pixel that contains this low-resolution fragment. A cleared
// depth is "no occluder".
float cloudSurfaceDistance(vec2 ndcFull, vec3 viewOrigin, ivec2 fullPixel) {
    float rawDepth = texelFetch(uSceneDepth, fullPixel, 0).r;
    if (!(rawDepth < 1.0)) {
        return 1.0e30;
    }
    vec4 viewPosition = uInverseProjection * vec4(ndcFull, 2.0 * rawDepth - 1.0, 1.0);
    viewPosition.xyz /= viewPosition.w;
    return length(viewPosition.xyz - viewOrigin);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 maxFullPixel = ivec2(uFullExtent) - 1;
    vec2 ndc = 2.0 * gl_FragCoord.xy / uLowExtent - 1.0;
    ivec2 fullPixel = clamp(ivec2((gl_FragCoord.xy / uLowExtent) * uFullExtent),
            ivec2(0), maxFullPixel);
    vec2 ndcFull = 2.0 * (vec2(fullPixel) + 0.5) / uFullExtent - 1.0;

    vec4 nearPosition = uInverseProjection * vec4(ndc, -1.0, 1.0);
    nearPosition.xyz /= nearPosition.w;
    vec4 farPosition = uInverseProjection * vec4(ndc, 1.0, 1.0);
    farPosition.xyz /= farPosition.w;
    vec3 viewOrigin = nearPosition.xyz;
    vec3 viewDirection = normalize(farPosition.xyz - viewOrigin);
    vec3 rayOrigin = (uInverseView * vec4(viewOrigin, 1.0)).xyz;
    vec3 rayDirection = normalize((uInverseView * vec4(viewDirection, 0.0)).xyz);

    bool fadeWithDistance = uAerialPerspectiveDistance > 0.0;
    float entry;
    float exit;
    if (abs(rayDirection.y) < 1.0e-6) {
        if (rayOrigin.y < uLayerBase || rayOrigin.y > uLayerTop) {
            outCloud = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        entry = 0.0;
        exit = fadeWithDistance ? uAerialPerspectiveDistance : CLOUD_MAX_HORIZONTAL_DISTANCE;
    } else {
        float first = (uLayerBase - rayOrigin.y) / rayDirection.y;
        float second = (uLayerTop - rayOrigin.y) / rayDirection.y;
        entry = max(min(first, second), 0.0);
        exit = max(first, second);
    }

    // Choose the budget before clipping so it does not lose whole slices when
    // approaching the distance limit (which can create visible rings).
    float layerSpan = exit - entry;
    exit = min(exit, cloudSurfaceDistance(ndcFull, viewOrigin, fullPixel));
    if (fadeWithDistance) {
        exit = min(exit, uAerialPerspectiveDistance);
    }
    if (!(exit > entry)) {
        outCloud = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    float span = exit - entry;
    float budgetSpan = fadeWithDistance ? layerSpan : span;
    float stepLength = max(budgetSpan / float(uMaximumSteps), uStepSizeMetres);
    int steps = int(clamp(ceil(budgetSpan / stepLength), 1.0, float(uMaximumSteps)));
    float delta = span / float(steps);

    float extinction = uDensity * (uAbsorption + uScattering);
    float albedo = extinction > 0.0 ? (uDensity * uScattering) / extinction : 0.0;
    vec3 toLight = -uLightDirection;
    float cosine = clamp(dot(uLightDirection, -rayDirection), -1.0, 1.0);
    vec3 phase = vec3(cloudPhase(uAnisotropy, cosine), 0.0, 0.0);
    float higherOrderWeight = toLight.y > 1.0e-4 ? uMultipleScatteringStrength * albedo : 0.0;
    if (higherOrderWeight > 0.0) {
        phase.y = cloudPhase(0.45 * uAnisotropy, cosine);
        phase.z = cloudPhase(0.20 * uAnisotropy, cosine);
    }

    vec3 scatter = vec3(0.0);
    float transmittance = 1.0;
    float visibleOpacity = 0.0;
    for (int step = 0; step < uMaximumSteps; step++) {
        if (step >= steps) {
            break;
        }
        float distance = entry + (float(step) + 0.5) * delta;
        vec3 position = rayOrigin + rayDirection * distance;
        float sigma = cloudDensityAt(position, delta, rayDirection);
        if (!(sigma > 0.0)) {
            continue;
        }
        float lightTau = cloudLightTau(position, toLight);
        float slabTransmittance = exp(-min(sigma * delta, CLOUD_MAX_OPTICAL_DEPTH));
        float weight = transmittance * (1.0 - slabTransmittance);
        if (fadeWithDistance) {
            weight *= 1.0 - smoothstep(0.25, 1.0, distance / uAerialPerspectiveDistance);
            visibleOpacity += weight;
        }
        float height = clamp((position.y - uLayerBase) / (uLayerTop - uLayerBase), 0.0, 1.0);
        vec3 sunScatter = uLightColor * cloudSunScatter(lightTau, phase, higherOrderWeight);
        vec3 skyFill = uSkyLightColor * mix(0.54, 1.0, height);
        scatter += (sunScatter + skyFill) * (albedo * weight);
        transmittance *= slabTransmittance;
    }
    float sceneWeight = fadeWithDistance ? 1.0 - visibleOpacity : transmittance;
    outCloud = vec4(max(scatter, vec3(0.0)), clamp(sceneWeight, 0.0, 1.0));
}
