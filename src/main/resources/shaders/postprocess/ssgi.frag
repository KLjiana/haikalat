#version 460 core

// Bounded screen-space indirect diffuse (SSGI / S5 #39), spatial path only.
//
// Inputs: resolved linear HDR radiance, single-sample device depth, world-space
// geometric normal (octahedral encoded, same convention as scene-surface.frag),
// material roughness and material albedo. The pass traces one cosine-weighted
// hemisphere per pixel with a fixed sample budget and a fixed march budget.
//
// Every ray that finds a visible surface inside the raster adds that surface's
// radiance scaled by its depth/normal/roughness/edge confidence. A ray that
// leaves the raster adds the receiver's ambient radiance approximation instead,
// so an offscreen hemisphere never becomes black and never marches unboundedly;
// that miss budget is clamped to a fraction of the receiver irradiance. A ray
// that hits the sky/background contributes nothing because the forward IBL
// ambient already carries the environment-to-receiver path, and a ray that is
// occluded by a thick surface contributes nothing.
//
// The result is one extra diffuse bounce:
//   out = base + albedo / PI * (PI / n * (hits + clampedOffscreen)) * strength
// The receiver's own colour is only read, never re-added, so direct light is not
// counted twice. No existing ambient is subtracted and no AO is reapplied.
//
// Coordinates: pixel centre -> UV in [0,1]; view space is -Z forward. Samples
// are offset in view space and projected with the raster projection; the depth
// test compares positive view depths in metres.
//
// Bounded cost: at most SSGI_MAX_SAMPLES * SSGI_MAX_STEPS march samples.

#define SSGI_MAX_SAMPLES 16
#define SSGI_MAX_STEPS 16
#define SSGI_PI 3.14159265359
#define SSGI_GOLDEN_RATIO 0.61803398875
#define SSGI_DEPTH_BIAS 0.02
#define SSGI_MIN_HIT_DISTANCE_METRES 0.05
#define SSGI_BACKFACE_EPSILON 0.05

in vec2 vUv;
layout(location = 0) out vec4 FragColor;

uniform sampler2D uColor;
uniform sampler2D uDepth;
uniform sampler2D uNormal;
uniform sampler2D uRoughness;
uniform sampler2D uAlbedo;
uniform samplerCube uIrradiance;

uniform vec2 uViewportSize;
uniform mat4 uInverseProjection;
uniform mat4 uProjection;
uniform mat4 uView;

uniform int uSampleCount;
uniform int uStepCount;
uniform float uMaxDistance;
uniform float uThickness;
uniform float uStrength;
uniform float uMinimumSampleRoughness;
uniform float uMaximumOffscreenFraction;
uniform float uEdgeFadePixels;
uniform vec3 uAmbientFallback;
uniform int uEnvironmentAvailable;
uniform float uEnvironmentIntensity;
uniform float uEnvironmentRotation;

// Mirrors octDecode() in shaders/postprocess/ssr.frag.
vec3 octDecode(vec2 oct) {
    vec3 n = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx))
                * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

// Mirrors rotateEnvironment() in shaders/render3d/pbr/pbr-forward.frag.
vec3 rotateEnvironment(vec3 direction) {
    float c = cos(uEnvironmentRotation);
    float s = sin(uEnvironmentRotation);
    return vec3(c * direction.x - s * direction.z, direction.y,
                s * direction.x + c * direction.z);
}

// Nearest texel lookup; depth and geometric normals must never be filtered.
vec4 fetchPixel(sampler2D source, vec2 uv) {
    ivec2 extent = ivec2(uViewportSize);
    ivec2 pixel = clamp(ivec2(uv * uViewportSize), ivec2(0), extent - ivec2(1));
    return texelFetch(source, pixel, 0);
}

// Positive view-space depth of the device depth stored at uv.
float viewDepthAt(vec2 uv, float depth) {
    vec4 clip = uInverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    if (clip.w <= 0.0) return 0.0;
    return -clip.z / clip.w;
}

// Interleaved gradient noise; rotates the fixed hemisphere sequence per pixel
// without any frame history or GPU read-back.
float interleavedGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// Deterministic cosine-weighted hemisphere direction. The estimator for the
// incoming irradiance of n cosine-weighted samples is PI / n * sum(radiance).
vec3 cosineHemisphere(vec3 normal, int index, int sampleCount, float rotation) {
    float count = float(sampleCount);
    float u1 = (float(index) + 0.5) / count;
    float u2 = fract((float(index) + 0.5) * SSGI_GOLDEN_RATIO + rotation);
    float radius = sqrt(u1);
    float phi = 2.0 * SSGI_PI * u2;
    vec3 up = abs(normal.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(up, normal));
    vec3 bitangent = cross(normal, tangent);
    return normalize(tangent * (radius * cos(phi))
            + bitangent * (radius * sin(phi))
            + normal * sqrt(max(0.0, 1.0 - u1)));
}

float borderFade(vec2 uv) {
    if (uEdgeFadePixels <= 0.0) return 1.0;
    vec2 pixels = uv * uViewportSize;
    vec2 border = min(pixels, uViewportSize - pixels);
    return smoothstep(0.0, uEdgeFadePixels, min(border.x, border.y));
}

float sampleRoughnessWeight(float roughness) {
    float floorValue = clamp(uMinimumSampleRoughness, 0.0, 0.99);
    return smoothstep(floorValue, min(floorValue + 0.2, 1.0), clamp(roughness, 0.0, 1.0));
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewportSize;
    vec3 base = texture(uColor, uv).rgb;
    float depth = fetchPixel(uDepth, uv).r;

    // Background pixels are not receivers: the pass is an exact no-op there.
    if (depth >= 1.0) {
        FragColor = vec4(base, 1.0);
        return;
    }

    vec3 albedo = clamp(fetchPixel(uAlbedo, uv).rgb, 0.0, 1.0);
    if (albedo.r + albedo.g + albedo.b <= 0.0) {
        FragColor = vec4(base, 1.0);
        return;
    }
    vec3 normal = octDecode(fetchPixel(uNormal, uv).rg);
    vec4 viewClip = uInverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 viewPosition = viewClip.xyz / viewClip.w;

    // Receiver ambient baseline: the irradiance map stores PI * average
    // cosine-weighted radiance, exactly like the forward IBL diffuse term.
    vec3 ambientIrradiance;
    if (uEnvironmentAvailable != 0) {
        ambientIrradiance = texture(uIrradiance, rotateEnvironment(normal)).rgb
                * max(uEnvironmentIntensity, 0.0);
    } else {
        ambientIrradiance = uAmbientFallback * SSGI_PI;
    }
    ambientIrradiance = max(ambientIrradiance, vec3(0.0));
    vec3 ambientRadiance = ambientIrradiance / SSGI_PI;

    float rotation = interleavedGradientNoise(gl_FragCoord.xy);
    int sampleCount = clamp(uSampleCount, 1, SSGI_MAX_SAMPLES);
    int stepCount = clamp(uStepCount, 1, SSGI_MAX_STEPS);
    float maximumDistance = max(uMaxDistance, 1.0e-4);
    float thickness = clamp(uThickness, 1.0e-4, maximumDistance);
    ivec2 centerPixel = ivec2(gl_FragCoord.xy);

    vec3 hitRadiance = vec3(0.0);
    int offscreenSamples = 0;

    for (int sample = 0; sample < SSGI_MAX_SAMPLES; sample++) {
        if (sample >= sampleCount) break;
        vec3 worldDirection = cosineHemisphere(normal, sample, sampleCount, rotation);
        vec3 viewDirection = normalize(mat3(uView) * worldDirection);
        bool offscreen = false;

        for (int step = 0; step < SSGI_MAX_STEPS; step++) {
            if (step >= stepCount) break;
            float distance = maximumDistance * (float(step) + 0.5) / float(stepCount);
            vec3 samplePosition = viewPosition + viewDirection * distance;
            vec4 clip = uProjection * vec4(samplePosition, 1.0);
            if (clip.w <= 0.0) {
                offscreen = true;
                break;
            }
            vec2 sampleUv = clip.xy / clip.w * 0.5 + 0.5;
            if (any(lessThan(sampleUv, vec2(0.0))) || any(greaterThan(sampleUv, vec2(1.0)))) {
                offscreen = true;
                break;
            }
            ivec2 samplePixel = clamp(ivec2(sampleUv * uViewportSize),
                    ivec2(0), ivec2(uViewportSize) - ivec2(1));
            if (samplePixel == centerPixel) continue;
            float sceneDepth = fetchPixel(uDepth, sampleUv).r;
            if (sceneDepth >= 1.0) break;
            float rayDepth = -samplePosition.z;
            float sceneViewDepth = viewDepthAt(sampleUv, sceneDepth);
            float separation = rayDepth - sceneViewDepth;
            float bias = max(SSGI_DEPTH_BIAS, 0.002 * sceneViewDepth);
            if (separation <= bias) continue;
            if (separation > thickness) break;
            if (distance < SSGI_MIN_HIT_DISTANCE_METRES) continue;
            vec3 hitNormal = octDecode(fetchPixel(uNormal, sampleUv).rg);
            float facing = clamp(-dot(hitNormal, worldDirection), 0.0, 1.0);
            if (facing <= SSGI_BACKFACE_EPSILON) break;
            float roughWeight = sampleRoughnessWeight(fetchPixel(uRoughness, sampleUv).r);
            float edge = borderFade(sampleUv);
            float confidence = facing * roughWeight * edge;
            if (confidence > 0.0) {
                hitRadiance += texture(uColor, sampleUv).rgb * confidence;
            }
            break;
        }
        if (offscreen) offscreenSamples++;
    }

    vec3 gathered = hitRadiance * (SSGI_PI / float(sampleCount));
    float offscreenFraction = float(offscreenSamples) / float(sampleCount);
    float offscreenBudget = min(offscreenFraction, max(uMaximumOffscreenFraction, 0.0));
    gathered += ambientRadiance * offscreenBudget * SSGI_PI;

    vec3 added = albedo * max(gathered, vec3(0.0)) * clamp(uStrength, 0.0, 1.0) / SSGI_PI;
    vec3 result = base + added;
    if (any(isnan(result)) || any(isinf(result))) {
        FragColor = vec4(base, 1.0);
        return;
    }
    FragColor = vec4(result, 1.0);
}
