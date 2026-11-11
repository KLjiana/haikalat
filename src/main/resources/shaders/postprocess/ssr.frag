#version 460 core

// Bounded screen-space reflection (SSR / S3 #20), spatial path only.
//
// Inputs: resolved linear HDR colour, single-sample device depth, world-space
// geometric normal (octahedral encoded, same convention as scene-surface.frag)
// and material roughness. All spatial inputs are the single-sample resolved
// channels, never a multisampled attachment. The pass writes base + Fresnel *
// confidence * (hitRadiance - environmentRadiance), so a pixel without a
// confident hit is written exactly as it was read: the existing forward PBR
// environment/IBL specular remains the fallback.
//
// Coordinates: pixel centre -> UV in [0,1]; view space is -Z forward with the
// raster projection inverse; the march advances world-space distance along the
// reflected ray and projects each sample with the stable view-projection. The
// screen-space depth test compares positive view depths; a ray separation below
// uThickness is a hit, a larger separation is treated as occlusion.
//
// Bounded cost: at most SSR_MAX_STEPS march samples plus SSR_REFINE_STEPS
// bisection samples per pixel; the step count is clamped by the pass.

#define SSR_MAX_STEPS 64
#define SSR_REFINE_STEPS 4

in vec2 vUv;
layout(location = 0) out vec4 FragColor;

uniform sampler2D uColor;
uniform sampler2D uDepth;
uniform sampler2D uNormal;
uniform sampler2D uRoughness;
uniform samplerCube uPrefilteredMap;

uniform vec2 uViewportSize;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform mat4 uView;
uniform mat4 uViewProjection;

uniform int uStepCount;
uniform float uMaxDistance;
uniform float uThickness;
uniform float uRoughnessCutoff;
uniform float uMinimumConfidence;
uniform float uStrength;
uniform float uEdgeFadePixels;
uniform int uEnvironmentAvailable;
uniform float uEnvironmentIntensity;
uniform float uEnvironmentRotation;
uniform float uPrefilterMaxLod;

// Mirrors octEncode() in shaders/render3d/surface/scene-surface.frag.
vec3 octDecode(vec2 oct) {
    vec3 n = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx))
                * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

// Mirrors rotateEnvironment() in shaders/render3d/pbr/pbr-forward.frag so the
// fallback colour is the same environment sample the forward pass uses.
vec3 rotateEnvironment(vec3 direction) {
    float c = cos(uEnvironmentRotation);
    float s = sin(uEnvironmentRotation);
    return vec3(c * direction.x - s * direction.z, direction.y,
                s * direction.x + c * direction.z);
}

float fresnelSchlickRoughness(float cosTheta, float f0, float roughness) {
    return f0 + (max(1.0 - roughness, f0) - f0)
            * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// Nearest texel lookup; depth and geometric normals must never be filtered,
// because a silhouette would mix unrelated depths and manufacture hits.
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

// Signed ray-minus-surface view depth at ray parameter t. Negative means the ray
// is still in front of the visible surface, or the texel is background, so the
// march continues. Positive values are candidates for a hit or an occlusion
// depending on uThickness. A sample that leaves the raster or the depth range
// ends the march (valid = false); one straight 3D segment cannot re-enter the
// projected screen rectangle afterwards.
float raySeparationAt(vec3 origin, vec3 direction, float t, out vec2 sampleUv,
                      out bool valid) {
    vec3 samplePosition = origin + direction * (uMaxDistance * t);
    vec4 clip = uViewProjection * vec4(samplePosition, 1.0);
    valid = clip.w > 0.0;
    if (!valid) {
        sampleUv = vec2(-1.0);
        return -1.0;
    }
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
    sampleUv = uv;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        valid = false;
        return -1.0;
    }
    float rayViewDepth = -(uView * vec4(samplePosition, 1.0)).z;
    if (rayViewDepth <= 0.0) {
        valid = false;
        return -1.0;
    }
    float depth = fetchPixel(uDepth, uv).r;
    if (depth >= 1.0) return -1.0;
    return rayViewDepth - viewDepthAt(uv, depth);
}

float edgeDistancePixels(vec2 uv) {
    vec2 pixels = uv * uViewportSize;
    vec2 border = min(pixels, uViewportSize - pixels);
    return min(border.x, border.y);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewportSize;
    vec3 base = texture(uColor, uv).rgb;
    float depth = fetchPixel(uDepth, uv).r;

    // Background pixels and sky have no reflecting surface.
    if (depth >= 1.0 || uEnvironmentAvailable == 0) {
        FragColor = vec4(base, 1.0);
        return;
    }

    float roughness = clamp(fetchPixel(uRoughness, uv).r, 0.0, 1.0);
    vec3 normal = octDecode(fetchPixel(uNormal, uv).rg);
    vec4 viewClip = uInverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 viewPosition = viewClip.xyz / viewClip.w;
    vec3 worldPosition = (uInverseView * vec4(viewPosition, 1.0)).xyz;
    vec3 cameraPosition = (uInverseView * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
    vec3 toCamera = normalize(cameraPosition - worldPosition);
    float nDotV = clamp(dot(normal, toCamera), 0.0, 1.0);

    // Rough, backfacing and grazing surfaces keep the existing environment
    // reflection instead of a screen-space probe.
    if (roughness > uRoughnessCutoff || nDotV <= 0.05) {
        FragColor = vec4(base, 1.0);
        return;
    }

    vec3 reflected = reflect(-toCamera, normal);
    vec3 environmentRadiance = textureLod(uPrefilteredMap,
            rotateEnvironment(reflected), roughness * uPrefilterMaxLod).rgb
            * uEnvironmentIntensity;

    float hitWeight = 0.0;
    vec3 hitRadiance = vec3(0.0);
    {
        int stepCount = clamp(uStepCount, 1, SSR_MAX_STEPS);
        float depthBias = max(uThickness * 0.02, 0.001);
        float previousT = 0.0;
        float hitT = -1.0;
        for (int step = 1; step <= SSR_MAX_STEPS; step++) {
            if (step > stepCount) break;
            float t = float(step) / float(stepCount);
            vec2 sampleUv;
            bool sampleValid;
            float separation = raySeparationAt(worldPosition, reflected, t, sampleUv,
                    sampleValid);
            if (!sampleValid) break;
            if (separation > depthBias) {
                if (separation <= uThickness) hitT = t;
                break;
            }
            previousT = t;
        }

        if (hitT > 0.0) {
            // Bounded bisection of the crossing interval; the hit stays inside
            // the marched screen segment and never adds more than a few taps.
            float low = previousT;
            float high = hitT;
            for (int refine = 0; refine < SSR_REFINE_STEPS; refine++) {
                float mid = 0.5 * (low + high);
                vec2 midUv;
                bool midValid;
                float separation = raySeparationAt(worldPosition, reflected, mid, midUv,
                        midValid);
                if (midValid && separation > depthBias && separation <= uThickness) {
                    high = mid;
                } else {
                    low = mid;
                }
            }
            vec3 hitWorld = worldPosition + reflected * (uMaxDistance * high);
            vec4 hitClip = uViewProjection * vec4(hitWorld, 1.0);
            if (hitClip.w > 0.0) {
                vec2 hitUv = hitClip.xy / hitClip.w * 0.5 + 0.5;
                if (all(greaterThanEqual(hitUv, vec2(0.0)))
                        && all(lessThanEqual(hitUv, vec2(1.0)))) {
                    float hitDepth = fetchPixel(uDepth, hitUv).r;
                    if (hitDepth < 1.0) {
                        float hitRayDepth = -(uView * vec4(hitWorld, 1.0)).z;
                        float separation = max(hitRayDepth - viewDepthAt(hitUv, hitDepth),
                                0.0);
                        vec3 hitNormal = octDecode(fetchPixel(uNormal, hitUv).rg);
                        float facing = clamp(-dot(hitNormal, reflected), 0.0, 1.0);
                        float edgePixels = edgeDistancePixels(hitUv);
                        float edgeConfidence = uEdgeFadePixels <= 0.0 ? 1.0
                                : smoothstep(0.0, uEdgeFadePixels, edgePixels);
                        float depthConfidence = 1.0
                                - clamp(separation / max(uThickness, 1.0e-4), 0.0, 1.0);
                        float grazingConfidence = smoothstep(0.05, 0.25, nDotV);
                        float distanceConfidence = 1.0
                                - smoothstep(0.75, 1.0, high);
                        float confidence = depthConfidence * edgeConfidence * facing
                                * grazingConfidence * distanceConfidence;
                        // Soft confidence floor: samples under the floor keep
                        // the environment fallback exactly.
                        hitWeight = confidence >= uMinimumConfidence
                                ? confidence * smoothstep(uMinimumConfidence, 1.0, confidence)
                                : 0.0;
                        if (hitWeight > 0.0) {
                            float roughnessBlend = clamp(
                                    roughness / max(uRoughnessCutoff, 1.0e-4), 0.0, 1.0);
                            hitRadiance = mix(texture(uColor, hitUv).rgb,
                                    environmentRadiance, roughnessBlend);
                        }
                    }
                }
            }
        }
    }

    float weight = uStrength * fresnelSchlickRoughness(nDotV, 0.04, roughness)
            * hitWeight;
    // Where the hit weight is zero this is exactly the input colour: the
    // forward IBL specular term remains the environment fallback.
    FragColor = vec4(base + weight * (hitRadiance - environmentRadiance), 1.0);
}
