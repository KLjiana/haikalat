#version 460 core

// Adaptive step-budget variant of the bounded screen-space reflection march
// (SSR #20, roadmap adaptive sampling #41).
//
// `ssr.frag` remains the fixed-budget shader and the exact disabled/control
// path; this file mirrors its input/output, fallback and confidence contract and
// adds a measured budget. Keep both files in sync when the shared march
// contract changes.
//
// Budget = max(budgetFor(tileError), budgetFor(pixelResidual)) clamped to
// [uMinimumSteps, uMaximumSteps]:
//
//  * tileError     is a tile-quantized variance of the shared world normal and
//                  roughness probes. Every pixel of a tile evaluates the same
//                  four probes, so the term is identical inside a tile.
//  * pixelResidual is measured by the minimum-sample coarse march of this
//                  pixel's ray: the largest positive depth jump at a surface
//                  crossing and the screen-space step length of that crossing.
//                  A coarse step that crosses a surface with a large depth gap
//                  can tunnel through thin geometry; the measured gap is the
//                  error estimate that earns extra samples.
//
// Both terms are measured, monotone and bounded, and the coarse march always
// spends the floor first. Extra budget can only add samples: when the finer
// march does not find a hit the coarse hit is kept.
//
// Coordinates and fallback are identical to ssr.frag: pixel centre -> UV,
// positive view depths, stable view-projection for march samples, and a pixel
// without a confident hit is written exactly as it was read.

#define SSR_MAX_STEPS 64
#define SSR_REFINE_STEPS 4
#define SSR_TILE_PROBE_COUNT 4
#define SSR_TILE_NORMAL_SPREAD_SCALE 0.5
#define SSR_TILE_ROUGHNESS_SPREAD_SCALE 0.5

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

uniform int uMinimumSteps;
uniform int uMaximumSteps;
uniform float uErrorThreshold;
uniform float uTileSizePixels;
uniform float uScreenStepScalePixels;
uniform float uDepthJumpScale;
// Test-only diagnostic: 0 writes alpha = 1 (production), 1 writes
// budget / maximumSteps so the real-GL acceptance test can read the allocation.
uniform int uBudgetDebug;

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
// ends the march (valid = false). surfacePresent distinguishes a background
// texel from a real surface so the residual only compares real crossings.
float raySeparationAt(vec3 origin, vec3 direction, float t, out vec2 sampleUv,
                      out bool valid, out bool surfacePresent) {
    vec3 samplePosition = origin + direction * (uMaxDistance * t);
    vec4 clip = uViewProjection * vec4(samplePosition, 1.0);
    valid = clip.w > 0.0;
    surfacePresent = false;
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
    surfacePresent = true;
    return rayViewDepth - viewDepthAt(uv, depth);
}

float edgeDistancePixels(vec2 uv) {
    vec2 pixels = uv * uViewportSize;
    vec2 border = min(pixels, uViewportSize - pixels);
    return min(border.x, border.y);
}

// Tile-quantized variance of the shared normal and roughness channels the
// march reads. Four probes at the tile quarter points are evaluated by every
// pixel of the tile, so the resulting budget is uniform inside a tile even
// though it is recomputed per fragment. Background probes are ignored; a tile
// without at least two foreground probes has no measured spread. Depth is not
// part of this term: a receding floor has a large smooth depth spread that
// says nothing about a silhouette, while a normal/roughness discontinuity is
// the signal that marks the thin-geometry band where coarse steps can tunnel.
float tileErrorEstimate() {
    ivec2 extent = ivec2(uViewportSize);
    ivec2 maximumPixel = extent - ivec2(1);
    float tileSize = max(uTileSizePixels, 1.0);
    ivec2 origin = ivec2(floor(gl_FragCoord.xy / tileSize) * tileSize);
    int quarter = int(max(tileSize * 0.25, 0.0));
    int threeQuarter = int(max(tileSize * 0.75, 0.0));
    ivec2 offsets[SSR_TILE_PROBE_COUNT] = ivec2[](
            ivec2(quarter, quarter), ivec2(threeQuarter, quarter),
            ivec2(quarter, threeQuarter), ivec2(threeQuarter, threeQuarter));

    float minimumRoughness = 1.0;
    float maximumRoughness = 0.0;
    float minimumDot = 1.0;
    vec3 firstNormal = vec3(0.0, 0.0, 1.0);
    int foreground = 0;
    for (int probe = 0; probe < SSR_TILE_PROBE_COUNT; probe++) {
        ivec2 pixel = clamp(origin + offsets[probe], ivec2(0), maximumPixel);
        float deviceDepth = texelFetch(uDepth, pixel, 0).r;
        if (deviceDepth >= 1.0) continue;
        vec3 normal = octDecode(texelFetch(uNormal, pixel, 0).rg);
        float roughness = clamp(texelFetch(uRoughness, pixel, 0).r, 0.0, 1.0);
        if (foreground == 0) firstNormal = normal;
        minimumDot = min(minimumDot, dot(normal, firstNormal));
        minimumRoughness = min(minimumRoughness, roughness);
        maximumRoughness = max(maximumRoughness, roughness);
        foreground++;
    }
    if (foreground < 2) return 0.0;
    float normalTerm = clamp((1.0 - minimumDot) / SSR_TILE_NORMAL_SPREAD_SCALE, 0.0, 1.0);
    float roughnessTerm = clamp((maximumRoughness - minimumRoughness)
            / SSR_TILE_ROUGHNESS_SPREAD_SCALE, 0.0, 1.0);
    return max(normalTerm, roughnessTerm);
}

// Documented allocation formula shared with AdaptiveSamplingSettings.budgetFor.
// Errors at or below the threshold spend only the floor; the ramp is linear over
// (threshold, 1] and rounds half up.
int budgetForError(float error) {
    int floorSteps = clamp(uMinimumSteps, 1, SSR_MAX_STEPS);
    int ceilingSteps = clamp(uMaximumSteps, floorSteps, SSR_MAX_STEPS);
    float span = float(ceilingSteps - floorSteps);
    float threshold = clamp(uErrorThreshold, 0.0, 0.99);
    float denominator = max(1.0 - threshold, 1.0e-4);
    float normalized = clamp((clamp(error, 0.0, 1.0) - threshold) / denominator,
            0.0, 1.0);
    return floorSteps + int(floor(normalized * span + 0.5));
}

// Marches `steps` samples of the reflected ray and reports the first hit
// interval plus the measured convergence residual of this march. The residual
// is the largest positive depth jump at a surface crossing normalized by the
// hit thickness (a coarse step that crosses more depth than the hit window can
// tunnel) and the screen-space length of that crossing step.
void marchReflection(vec3 origin, vec3 direction, int steps, float depthBias,
                     out float hitT, out float previousT, out float residual) {
    hitT = -1.0;
    previousT = 0.0;
    residual = 0.0;
    int sampleCount = clamp(steps, 1, SSR_MAX_STEPS);
    vec2 previousUv = vec2(-1.0);
    float previousSeparation = 0.0;
    bool hasPrevious = false;
    for (int step = 1; step <= SSR_MAX_STEPS; step++) {
        if (step > sampleCount) break;
        float t = float(step) / float(sampleCount);
        vec2 sampleUv;
        bool sampleValid;
        bool surfacePresent;
        float separation = raySeparationAt(origin, direction, t, sampleUv, sampleValid,
                surfacePresent);
        if (!sampleValid) break;
        if (hasPrevious && surfacePresent) {
            float depthJump = max(separation - previousSeparation, 0.0);
            if (depthJump > 0.0) {
                float depthTerm = clamp(depthJump
                        / max(uThickness * uDepthJumpScale, 1.0e-4), 0.0, 1.0);
                residual = max(residual, depthTerm);
            }
        }
        if (separation > depthBias) {
            // The crossing step is where a coarse sample can tunnel through a
            // thin surface; its screen-space length is the second measured term.
            if (hasPrevious) {
                float pixelStep = length((sampleUv - previousUv) * uViewportSize);
                float screenTerm = clamp(pixelStep
                        / max(uScreenStepScalePixels, 1.0e-3), 0.0, 1.0);
                residual = max(residual, screenTerm);
            }
            if (separation <= uThickness) hitT = t;
            break;
        }
        previousT = t;
        if (surfacePresent) {
            previousUv = sampleUv;
            previousSeparation = separation;
            hasPrevious = true;
        } else {
            hasPrevious = false;
        }
    }
}

// Bounded bisection of the crossing interval; the hit stays inside the marched
// screen segment and never adds more than SSR_REFINE_STEPS taps.
float refineHitT(vec3 origin, vec3 direction, float low, float high, float depthBias) {
    for (int refine = 0; refine < SSR_REFINE_STEPS; refine++) {
        float mid = 0.5 * (low + high);
        vec2 midUv;
        bool midValid;
        bool midSurface;
        float separation = raySeparationAt(origin, direction, mid, midUv, midValid,
                midSurface);
        if (midValid && separation > depthBias && separation <= uThickness) {
            high = mid;
        } else {
            low = mid;
        }
    }
    return high;
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

    int floorSteps = clamp(uMinimumSteps, 1, SSR_MAX_STEPS);
    int ceilingSteps = clamp(uMaximumSteps, floorSteps, SSR_MAX_STEPS);
    float depthBias = min(max(uThickness * 0.02, 1.0e-5), uThickness * 0.5);

    // Phase 1: the mandatory floor and its measured residual.
    float coarseHitT;
    float coarsePreviousT;
    float coarseResidual;
    marchReflection(worldPosition, reflected, floorSteps, depthBias, coarseHitT,
            coarsePreviousT, coarseResidual);

    int tileBudget = budgetForError(tileErrorEstimate());
    int pixelBudget = budgetForError(coarseResidual);
    int budget = clamp(max(tileBudget, pixelBudget), floorSteps, ceilingSteps);

    // Phase 2: only pixels whose measured error or tile variance earned more
    // than the floor pay for the finer march. A coarse hit is never discarded
    // by a finer march that finds nothing (stability).
    float hitT = coarseHitT;
    float previousT = coarsePreviousT;
    if (budget > floorSteps) {
        float refinedHitT;
        float refinedPreviousT;
        float unusedResidual;
        marchReflection(worldPosition, reflected, budget, depthBias, refinedHitT,
                refinedPreviousT, unusedResidual);
        if (refinedHitT > 0.0 || coarseHitT <= 0.0) {
            hitT = refinedHitT;
            previousT = refinedPreviousT;
        }
    }

    float hitWeight = 0.0;
    vec3 hitRadiance = vec3(0.0);
    if (hitT > 0.0) {
        float high = refineHitT(worldPosition, reflected, previousT, hitT, depthBias);
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
                    // Soft confidence floor: samples under the floor keep the
                    // environment fallback exactly.
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

    float weight = uStrength * fresnelSchlickRoughness(nDotV, 0.04, roughness)
            * hitWeight;
    // Where the hit weight is zero this is exactly the input colour: the
    // forward IBL specular term remains the environment fallback.
    FragColor = vec4(base + weight * (hitRadiance - environmentRadiance),
            uBudgetDebug != 0 ? float(budget) / float(ceilingSteps) : 1.0);
}
