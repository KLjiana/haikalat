#version 460 core
// Bounded volumetric cloud layer, low-resolution view march.
//
// The vertical band [uLayerBase, uLayerTop] is the only participating medium.
// Density = extinction * cloudLayerMask(world.xz) * cloudLayerProfile(world.y)
// with the mask copied from the #10 cloud-shadow feature (same hash, noise,
// octave count, threshold and softness mapping). The march is front to back
// with a hard per-pixel step budget; every non-empty sample runs a bounded light
// march toward the light (-uLightDirection) so thick regions shade themselves.
// Output is premultiplied single scatter in rgb and transmittance in a:
//   rgb = sum(T_i * light * phase * albedo * lightTransmittance_i * (1 - exp(-sigma_i * delta)))
//   a   = product(exp(-sigma_i * delta))
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

#define CLOUD_SHADOW_OCTAVES 3
#define CLOUD_PROFILE_EDGE 0.25
#define CLOUD_PI 3.14159265358979323846
#define CLOUD_MAX_OPTICAL_DEPTH 88.0
#define CLOUD_MAX_HORIZONTAL_DISTANCE 30000.0

// --- shared #10 cloud-shadow mask (identical source text, see the shader test) ---
float cloudShadowHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float cloudShadowNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 local = fract(p);
    vec2 weight = local * local * (3.0 - 2.0 * local);
    float a = cloudShadowHash(cell);
    float b = cloudShadowHash(cell + vec2(1.0, 0.0));
    float c = cloudShadowHash(cell + vec2(0.0, 1.0));
    float d = cloudShadowHash(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, weight.x), mix(c, d, weight.x), weight.y);
}

float cloudShadowDensity(vec2 p) {
    float density = 0.0;
    float amplitude = 0.5;
    for (int octave = 0; octave < CLOUD_SHADOW_OCTAVES; octave++) {
        density += amplitude * cloudShadowNoise(p);
        p = p * 2.03 + vec2(17.1, 9.2);
        amplitude *= 0.5;
    }
    return density / 0.875;
}
// --- end shared #10 cloud-shadow mask ---

// World-xz coverage of the shared cloud field. The wind offset and threshold /
// softness mapping are the same expressions as cloudShadowVisibility() in
// pbr-forward.frag; only the projection differs (this layer uses the cloud's
// own world xz instead of a light-projected receiver plane).
float cloudLayerMask(vec2 worldXZ) {
    vec2 projected = worldXZ + vec2(0.8, 0.6) * (uCloudShadowTime * max(uCloudShadowSpeed, 0.0));
    float scale = max(uCloudShadowScale, 1.0e-4);
    float noise = cloudShadowDensity(projected * scale);
    float coverage = clamp(uCloudShadowCoverage, 0.0, 1.0);
    float threshold = mix(0.78, 0.22, coverage);
    float edge = mix(0.004, 0.28, clamp(uCloudShadowSoftness, 0.0, 1.0));
    return smoothstep(threshold - edge, threshold + edge, noise);
}

float cloudLayerProfile(float y) {
    float span = max(uLayerTop - uLayerBase, 1.0e-3);
    float t = (y - uLayerBase) / span;
    return smoothstep(0.0, CLOUD_PROFILE_EDGE, t)
            * (1.0 - smoothstep(1.0 - CLOUD_PROFILE_EDGE, 1.0, t));
}

float cloudDensityAt(vec3 worldPosition) {
    float mask = cloudLayerMask(worldPosition.xz);
    if (!(mask > 0.0)) {
        return 0.0;
    }
    return uDensity * (uAbsorption + uScattering) * mask * cloudLayerProfile(worldPosition.y);
}

// Normalized Henyey-Greenstein phase, the same convention and denominator floor
// as the volumetric-fog and particle media.
float cloudPhase(float anisotropy, float cosine) {
    float denominator = max(0.01, 1.0 + anisotropy * anisotropy - 2.0 * anisotropy * cosine);
    return (1.0 - anisotropy * anisotropy) / (4.0 * CLOUD_PI * denominator * sqrt(denominator));
}

// Bounded optical depth toward the light. The light travel direction is
// uLightDirection, so the direction toward the light is its negation. A light
// at or below the layer (toLight.y <= 0) contributes no direct in-scatter.
float cloudLightTau(vec3 samplePosition, vec3 toLight) {
    if (!(toLight.y > 1.0e-4)) {
        return CLOUD_MAX_OPTICAL_DEPTH;
    }
    float span = (uLayerTop - samplePosition.y) / toLight.y;
    span = clamp(span, 0.0, uMaximumLightDistance);
    if (!(span > 0.0)) {
        return 0.0;
    }
    float delta = span / float(uLightSteps);
    float opticalDepth = 0.0;
    for (int step = 0; step < uLightSteps; step++) {
        vec3 position = samplePosition + toLight * ((float(step) + 0.5) * delta);
        opticalDepth += cloudDensityAt(position) * delta;
    }
    return opticalDepth;
}

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

    float entry;
    float exit;
    if (abs(rayDirection.y) < 1.0e-6) {
        if (rayOrigin.y < uLayerBase || rayOrigin.y > uLayerTop) {
            outCloud = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        entry = 0.0;
        exit = entry + CLOUD_MAX_HORIZONTAL_DISTANCE;
    } else {
        float first = (uLayerBase - rayOrigin.y) / rayDirection.y;
        float second = (uLayerTop - rayOrigin.y) / rayDirection.y;
        entry = max(min(first, second), 0.0);
        exit = max(first, second);
    }

    exit = min(exit, cloudSurfaceDistance(ndcFull, viewOrigin, fullPixel));
    if (!(exit > entry)) {
        outCloud = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    float span = exit - entry;
    float stepLength = max(span / float(uMaximumSteps), uStepSizeMetres);
    int steps = int(clamp(ceil(span / stepLength), 1.0, float(uMaximumSteps)));
    float delta = span / float(steps);

    float extinction = uDensity * (uAbsorption + uScattering);
    float albedo = extinction > 0.0 ? (uDensity * uScattering) / extinction : 0.0;
    vec3 toLight = -uLightDirection;
    float cosine = clamp(dot(uLightDirection, -rayDirection), -1.0, 1.0);
    float phase = cloudPhase(uAnisotropy, cosine);

    vec3 scatter = vec3(0.0);
    float transmittance = 1.0;
    for (int step = 0; step < uMaximumSteps; step++) {
        if (step >= steps) {
            break;
        }
        vec3 position = rayOrigin + rayDirection * (entry + (float(step) + 0.5) * delta);
        float sigma = cloudDensityAt(position);
        if (!(sigma > 0.0)) {
            continue;
        }
        float lightTau = cloudLightTau(position, toLight);
        float slabTransmittance = exp(-min(sigma * delta, CLOUD_MAX_OPTICAL_DEPTH));
        float weight = phase * albedo * exp(-min(lightTau, CLOUD_MAX_OPTICAL_DEPTH))
                * (1.0 - slabTransmittance);
        scatter += transmittance * uLightColor * weight;
        transmittance *= slabTransmittance;
    }
    outCloud = vec4(max(scatter, vec3(0.0)), clamp(transmittance, 0.0, 1.0));
}
