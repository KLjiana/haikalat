#version 460 core

layout(location = 0) in vec3 vWorldPosition;
layout(location = 0) out vec4 fragColor;

uniform mat4 uInverseProjection;
uniform mat4 uView;
uniform mat4 uInverseView;
uniform vec3 uCameraPosition;
uniform float uFarPlane;

#ifdef WATER_MSAA
uniform sampler2DMS uSceneDepth;
#else
uniform sampler2D uSceneDepth;
#endif

uniform int uHasEnvironment;
uniform samplerCube uEnvironment;
uniform float uEnvironmentIntensity;
uniform float uEnvironmentRotation;
// Optional planar-reflection colour target. When present it replaces the
// environment reflection at the current screen position; off-screen lookups
// fall back to the environment so the surface keeps a stable colour.
uniform int uHasPlanarReflection;
uniform sampler2D uPlanarReflection;
// Stable (non-jittered) view-projection of the planar reflection recording.
// The water surface is rasterised with the jittered projection, but the planar
// target is recorded without TAA jitter, so its screen-space address must be
// computed from the stable projection to compensate for the current jitter.
uniform mat4 uStableViewProjection;

uniform vec3 uWaterColor;
uniform vec3 uReflectionFallback;
uniform float uAbsorptionDensity;
uniform float uShoreFadeDistance;
uniform int uRepairNearPlane;
uniform float uWaveWavelength;
uniform float uWaveSpeed;
uniform float uWaveAmplitude;
uniform float uWaveDirection;
uniform float uTimeSeconds;
uniform int uDebugMode;

#ifdef WATER_REFRACTION
uniform sampler2D uSceneColor;
uniform mat4 uProjection;
uniform float uRefractionIor;
uniform float uRefractionStrength;
uniform float uRefractionMaxOffset;
uniform float uRefractionThicknessScale;
uniform float uRefractionForegroundBias;
uniform vec3 uRefractionFallbackColor;
uniform int uRefractionFallbackMode;
const int EDGE_FADE_TINT = 2;
#endif
const float TWO_PI = 6.283185307179586;
const float SECONDARY_FREQUENCY_SCALE = 1.7;
const float SECONDARY_WEIGHT = 0.5;
const float SECONDARY_PHASE = 1.3;
const float REFLECTION_F0 = 0.02;
const float GRAZING_EPSILON = 1.0e-3;
#ifdef WATER_REFRACTION
const float REFRACTION_MIN_DOWNWARD = 0.05;
const float REFRACTION_COVERAGE_EPSILON = 1.0e-5;
const float REFRACTION_DEPTH_EPSILON = 1.0e-6;
const float REFRACTION_MAX_PATH_METRES = 24.0;
// Allow for depth quantization on receivers flush with the pool wall.
const float REFRACTION_RECEIVER_TOLERANCE_METRES = 0.001;
#endif

vec3 rotateEnvironment(vec3 direction) {
    float c = cos(uEnvironmentRotation);
    float s = sin(uEnvironmentRotation);
    return vec3(c * direction.x - s * direction.z, direction.y,
                s * direction.x + c * direction.z);
}

// CPU counterpart: WaterSurfaceSettings.waveNormal(). Keep the constants and
// the two-train phase formula in sync; both are part of the public contract.
vec3 waveNormal(vec2 position, float timeSeconds) {
    float k1 = TWO_PI / max(uWaveWavelength, 1.0e-4);
    vec2 d1 = vec2(cos(uWaveDirection), sin(uWaveDirection));
    vec2 d2 = vec2(-d1.y, d1.x);
    float k2 = k1 * SECONDARY_FREQUENCY_SCALE;
    float omega1 = k1 * uWaveSpeed;
    float omega2 = k2 * uWaveSpeed;
    float phase1 = k1 * dot(d1, position) - omega1 * timeSeconds;
    float phase2 = k2 * dot(d2, position) - omega2 * timeSeconds + SECONDARY_PHASE;
    vec2 gradient = uWaveAmplitude
            * (k1 * d1 * cos(phase1) + SECONDARY_WEIGHT * k2 * d2 * cos(phase2));
    return normalize(vec3(-gradient.x, 1.0, -gradient.y));
}
#ifdef WATER_REFRACTION
// Validate each colour tap against the depth at that same texel. Hardware
// bilinear colour sampling after a single depth test still pulls foreground
// colour across the silhouette. Return zero coverage for invalid taps.
float receiverWetPath(ivec2 tap, ivec2 extent, float depth) {
    if (!(depth < 1.0)) return 0.0;
    vec2 ndc = (vec2(tap) + 0.5) / vec2(extent) * 2.0 - 1.0;
    vec4 viewH = uInverseProjection * vec4(ndc, depth * 2.0 - 1.0, 1.0);
    if (!(abs(viewH.w) > 1.0e-6)) return 0.0;
    vec3 receiver = (uInverseView * vec4(viewH.xyz / viewH.w, 1.0)).xyz;
    vec3 fromEye = receiver - uCameraPosition;
    vec2 tolerance = vec2(REFRACTION_RECEIVER_TOLERANCE_METRES);
    bool withinReceiverBounds = all(greaterThanEqual(receiver.xz, uWaterMinimum - tolerance))
            && all(lessThanEqual(receiver.xz, uWaterMaximum + tolerance));
    if (!(receiver.y < uWaterLevel) || !withinReceiverBounds
            || !(uCameraPosition.y > uWaterLevel) || !(fromEye.y < -1.0e-6)) return 0.0;
    float entry = (uWaterLevel - uCameraPosition.y) / fromEye.y;
    if (!(entry > 0.0 && entry < 1.0)
            || !insideWaterFootprint((uCameraPosition + entry * fromEye).xz)) return 0.0;
    return length(fromEye) * (1.0 - entry);
}

vec4 refractedTap(ivec2 tap, ivec2 extent, float shore, vec2 depthRange) {
    if (any(lessThan(tap, ivec2(0))) || any(greaterThanEqual(tap, extent))) return vec4(0.0);
    float depth = texelFetch(uSceneDepth, tap, 0).r;
    if (depth < depthRange.x || depth > depthRange.y) return vec4(0.0);
    float path = receiverWetPath(tap, extent, depth);
    if (!(path > 0.0)) return vec4(0.0);
    float confidence = smoothstep(0.0, max(uRefractionForegroundBias, 1.0e-4), path);
    float transmission = mix(1.0, exp(-uAbsorptionDensity * min(path, REFRACTION_MAX_PATH_METRES)), shore);
    vec3 transmitted = mix(uWaterColor, texelFetch(uSceneColor, tap, 0).rgb, transmission);
    return vec4(transmitted, 1.0) * confidence;
}

vec4 refractedTap(ivec2 tap, ivec2 extent, float shore) {
    return refractedTap(tap, extent, shore, vec2(0.0, 1.0));
}

vec4 sampleRefractedBackground(vec2 uv, ivec2 extent, float shore, float hitDepth) {
    // Allow two pixel widths of view-space depth change for a sloping receiver.
    // A wet but unrelated surface in this footprint must not tint the hit.
    vec4 view = uInverseProjection * vec4(uv * 2.0 - 1.0, hitDepth * 2.0 - 1.0, 1.0);
    float z = view.z / view.w;
    vec2 pixelSize = 2.0 / (abs(view.w) * vec2(extent) * abs(vec2(uProjection[0][0], uProjection[1][1])));
    float dz = 2.0 * max(pixelSize.x, pixelSize.y);
    vec2 zs = vec2(z + min(dz, -z * 0.5), z - dz);
    vec2 depthRange = 0.5 + 0.5 * (uProjection[2][2] * zs + uProjection[3][2])
            / (uProjection[2][3] * zs + uProjection[3][3]);
    depthRange += vec2(-REFRACTION_DEPTH_EPSILON, REFRACTION_DEPTH_EPSILON);
    vec2 address = uv * vec2(extent) - 0.5;
    ivec2 base = ivec2(floor(address));
    vec2 f = fract(address);
    return refractedTap(base, extent, shore, depthRange) * ((1.0 - f.x) * (1.0 - f.y))
            + refractedTap(base + ivec2(1, 0), extent, shore, depthRange) * (f.x * (1.0 - f.y))
            + refractedTap(base + ivec2(0, 1), extent, shore, depthRange) * ((1.0 - f.x) * f.y)
            + refractedTap(base + ivec2(1), extent, shore, depthRange) * (f.x * f.y);
}

// Perspective projection maps world travel to a different fraction of the
// screen segment. For orthographic projection both clip W values are equal.
float refractionProgress(float travelFraction, float startW, float endW) {
    return travelFraction * endW / mix(startW, endW, travelFraction);
}

// The opaque copy ends at the silhouette of a raised bank, not at the
// projected water rectangle. Compress the lookup towards that silhouette
// continuously; clamping every ray to its last wet texel stretches columns.
bool isDryBank(vec2 uv, ivec2 extent) {
    ivec2 p = ivec2(floor(uv * vec2(extent)));
    if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, extent))) return false;
    float depth = texelFetch(uSceneDepth, p, 0).r;
    if (depth >= 1.0) return false;
    vec2 ndc = (vec2(p) + 0.5) / vec2(extent) * 2.0 - 1.0;
    vec4 world = uInverseView * (uInverseProjection * vec4(ndc, depth * 2.0 - 1.0, 1.0));
    vec3 point = world.xyz / world.w;
    return point.y >= uWaterLevel && !insideWaterFootprint(point.xz);
}

float bankDisplacement(vec2 origin, vec2 offset, ivec2 extent) {
    float hi = 2.0;
    if (!isDryBank(origin + hi * offset, extent)) {
        hi = 1.0;
        if (!isDryBank(origin + offset, extent)) return 1.0;
    }
    float lo = 0.0;
    for (int i = 0; i < 8; ++i) {
        float mid = (lo + hi) * 0.5;
        if (isDryBank(origin + mid * offset, extent)) hi = mid;
        else lo = mid;
    }
    return smoothstep(0.0, 2.0, lo);
}

// A low, dry cap can hide a wet receiver in the opaque copy. Limit repair
// to the refraction footprint of its waterline. Never sample the cap colour.
bool nearProjectedWaterline(ivec2 tap, ivec2 extent, float depth) {
    vec2 uv = (vec2(tap) + 0.5) / vec2(extent);
    vec4 h = uInverseView * (uInverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0));
    vec3 p = h.xyz / h.w;
    if (p.y < uWaterLevel || !insideWaterFootprint(p.xz)) return false;
    p.y = uWaterLevel;
    vec4 clip = uProjection * uView * vec4(p, 1.0);
    return clip.w > 0.0 && length(clip.xy / clip.w * 0.5 + 0.5 - uv) <= uRefractionMaxOffset;
}

// Scan the projected Snell segment in screen space. Device depth at each
// texel determines where that surface would intersect the warped ray. Using
// the centre texel's depth for every candidate can show the same silhouette
// twice when the lookup crosses a depth discontinuity.
vec4 traceRefractedBackground(vec3 origin, vec3 direction, vec2 centreUv,
        ivec2 extent, float centreDepth, float thickness, float shore, float displacementFade, out float hitShore) {
    hitShore = shore;
    const int MAX_TRACE_STEPS = 48;
    const float SILHOUETTE_TOLERANCE_PIXELS = 2.0;
    float distance = waterInterval(origin, direction, REFRACTION_MAX_PATH_METRES).y;
    if (!(distance > 0.0)) return vec4(0.0);
    if (uRefractionThicknessScale == 0.0 || uRefractionStrength == 0.0
            || uRefractionMaxOffset == 0.0 || displacementFade == 0.0) {
        return refractedTap(ivec2(centreUv * vec2(extent)), extent, shore);
    }
    vec4 start = uProjection * uView * vec4(origin, 1.0);
    vec4 end = start + uProjection * uView * vec4(direction * distance, 0.0);
    if (!(end.w > 1.0e-6)) return vec4(0.0);
    vec2 rawOffset = (end.xy / end.w * 0.5 + 0.5 - centreUv) * uRefractionStrength;
    float maxProgress = min(1.0, uRefractionMaxOffset / max(length(rawOffset), 1.0e-8));
    rawOffset *= bankDisplacement(centreUv, rawOffset * maxProgress, extent);
    if (uRefractionFallbackMode == EDGE_FADE_TINT) {
        // Fade only the outward component, before both lookup paths. Over a
        // two-offset band the eased shift is always smaller than the room
        // to the last texel centre; neighbouring rows never collapse to it.
        vec2 room = mix(centreUv, 1.0 - centreUv, greaterThan(rawOffset, vec2(0.0)))
                - 0.5 / vec2(extent);
        rawOffset *= smoothstep(vec2(0.0), vec2(2.0 * uRefractionMaxOffset), room);
    }
    float thicknessToTravelFraction = uRefractionThicknessScale
            / (max(-direction.y, REFRACTION_MIN_DOWNWARD) * distance);

    // On a coherent receiver keep subpixel sampling even if the nearest
    // texel at the projected hit belongs to the other side of a silhouette.
    if (centreDepth < 1.0) {
        float travelFraction = min(thickness * thicknessToTravelFraction, 1.0);
        float progress = min(maxProgress, refractionProgress(travelFraction, start.w, end.w));
        vec2 uv = centreUv + rawOffset * (displacementFade * progress);
        vec2 border = min(uv, 1.0 - uv) * vec2(extent);
        float edgeWeight = uRefractionFallbackMode == EDGE_FADE_TINT ? 1.0
                : smoothstep(0.0, 2.0, min(border.x, border.y));
        if (edgeWeight > REFRACTION_COVERAGE_EPSILON) {
            vec2 sampleUv = mix(centreUv, uv, edgeWeight);
            ivec2 base = ivec2(floor(sampleUv * vec2(extent) - 0.5));
            for (int i = 0; i < 4; ++i) {
                ivec2 tap = base + ivec2(i & 1, i >> 1);
                if (any(lessThan(tap, ivec2(0))) || any(greaterThanEqual(tap, extent))) continue;
                if (abs(texelFetch(uSceneDepth, tap, 0).r - centreDepth) < REFRACTION_DEPTH_EPSILON) {
                    // At a subpixel edge the nearest wet texel owns the displaced
                    // pixel; the matching centre layer can have negligible weight.
                    ivec2 nearest = ivec2(sampleUv * vec2(extent));
                    float guide = texelFetch(uSceneDepth, nearest, 0).r;
                    if (!(receiverWetPath(nearest, extent, guide) > 0.0)) guide = centreDepth;
                    vec4 result = sampleRefractedBackground(sampleUv, extent, shore, guide);
                    if (result.a > REFRACTION_COVERAGE_EPSILON) return result;
                    break;
                }
            }
        } else {
            return vec4(0.0);
        }
    }

    // Evaluate candidate depths on the incident pixel ray to retain the
    // thickness-based displacement scale. Validate the actual receiver
    // independently at its own pixel when it can be sampled by the trace.
    vec2 ndc = centreUv * 2.0 - 1.0;
    vec4 depthOrigin = uInverseView * uInverseProjection * vec4(ndc, -1.0, 1.0);
    vec4 depthDelta = uInverseView * uInverseProjection * vec4(0.0, 0.0, 2.0, 0.0);
    vec2 pixelOrigin = centreUv * vec2(extent);
    // A different receiver must not inherit the direct-view object's shore fade.
    // Trace the whole ray and apply the displacement/shading fade of the candidate.
    vec2 pixelOffset = rawOffset * vec2(extent);
    float eyeElevation = max(normalize(uCameraPosition - origin).y, 1.0e-6);
    // A UV displacement spans more texels at higher resolutions. Keep exact
    // texel traversal for short rays; longer rays must still reach their end
    // within the same budget, or the original silhouette returns in patches.
    vec2 span = abs(pixelOffset) * maxProgress;
    bool strided = 1.0 + ceil(span.x) + ceil(span.y) > float(MAX_TRACE_STEPS);
    ivec2 tap = ivec2(pixelOrigin);
    ivec2 stepDirection = ivec2(sign(pixelOffset));
    vec2 cellDuration = 1.0 / max(abs(pixelOffset), vec2(1.0e-8));
    vec2 nextBoundary = (vec2(tap) + step(vec2(0.0), pixelOffset) - pixelOrigin)
            * vec2(stepDirection) * cellDuration;
    if (stepDirection.x == 0) nextBoundary.x = cellDuration.x;
    if (stepDirection.y == 0) nextBoundary.y = cellDuration.y;

    // A depth step can end just before the estimated intersection when the
    // opaque layer hides the rest of that receiver. Keep one nearby wet tap
    // as a last resort, only when the march sees a different receiver depth.
    float nearestMiss = SILHOUETTE_TOLERANCE_PIXELS;
    ivec2 nearHitTap = ivec2(0);
    float nearHitShore = 0.0;
    float screenTravel = length(pixelOffset);
    // If a submerged silhouette hides the true hit in this single-layer
    // snapshot, retain the closest approach on the *far* side of that step.
    ivec2 farTap = ivec2(0);
    float farMiss = screenTravel + 1.0, farDepth = 0.0, farShore = 0.0;
    float occluderDepth = 1.0;
    float segmentStart = 0.0;
    for (int step = 0; step < MAX_TRACE_STEPS; ++step) {
        float segmentEnd = min(min(nextBoundary.x, nextBoundary.y), maxProgress);
        if (strided) {
            segmentEnd = maxProgress * (float(step + 1) / float(MAX_TRACE_STEPS));
            tap = ivec2(floor(pixelOrigin + pixelOffset * segmentEnd));
        }
        if (any(lessThan(tap, ivec2(0))) || any(greaterThanEqual(tap, extent))) break;
        float depth = texelFetch(uSceneDepth, tap, 0).r;
        if (depth < 1.0) {
            vec4 receiver = depthOrigin + depthDelta * depth;
            float candidateThickness = max(0.0, uWaterLevel - receiver.y / receiver.w);
            float candidateShore = uShoreFadeDistance <= 0.0 ? 1.0
                    : smoothstep(0.0, uShoreFadeDistance, candidateThickness);
            float depthFade = smoothstep(0.0, max(uRefractionForegroundBias, 1.0e-4), candidateThickness / eyeElevation);
            float travelFraction = clamp(candidateThickness * thicknessToTravelFraction, 0.0, 1.0);
            float hitProgress = min(maxProgress, refractionProgress(travelFraction, start.w, end.w));
            hitProgress *= candidateShore * depthFade;
            float miss = (hitProgress - segmentEnd) * screenTravel;
            bool distinctReceiver = abs(depth - centreDepth) >= REFRACTION_DEPTH_EPSILON;
            float behind = (segmentStart - hitProgress) * screenTravel;
            if (miss > 0.0 && miss < farMiss && candidateThickness > 0.0) {
                farTap = tap;
                farMiss = miss;
                farDepth = depth;
                farShore = candidateShore;
            }
            if (behind >= SILHOUETTE_TOLERANCE_PIXELS && depth < occluderDepth
                    && ((candidateThickness > 0.0 && receiverWetPath(tap, extent, depth) > 0.0)
                        || nearProjectedWaterline(tap, extent, depth))) {
                occluderDepth = min(occluderDepth, depth);
            }
            if (behind < SILHOUETTE_TOLERANCE_PIXELS
                    && (miss <= 0.0 || (distinctReceiver && miss < nearestMiss))
                    && receiverWetPath(tap, extent, depth) > 0.0) {
                if (miss > 0.0) {
                    nearestMiss = miss;
                    nearHitShore = candidateShore;
                    nearHitTap = tap;
                } else {
                    // On a depth step the first visible intersection can sit on the
                    // texel boundary. Never extrapolate the hit behind that boundary.
                    vec2 hitUv = centreUv + rawOffset * max(segmentStart, hitProgress);
                    vec2 border = min(hitUv, 1.0 - hitUv) * vec2(extent);
                    float edgeWeight = uRefractionFallbackMode == EDGE_FADE_TINT ? 1.0
                            : smoothstep(0.0, 2.0, min(border.x, border.y));
                    if (edgeWeight <= REFRACTION_COVERAGE_EPSILON) break;
                    // At a discontinuity the receiver was found at tap, not on the
                    // preceding surface. Filtering across their boundary paints a
                    // second strip of the farther surface onto the displaced silhouette.
                    vec4 result = hitProgress < segmentStart && edgeWeight >= 1.0
                            ? refractedTap(tap, extent, candidateShore)
                            : sampleRefractedBackground(mix(centreUv, hitUv, edgeWeight), extent, candidateShore, depth);
                    if (result.a > REFRACTION_COVERAGE_EPSILON) {
                        hitShore = candidateShore;
                        return result;
                    }
                }
            }
        }
        if (segmentEnd >= maxProgress) {
            ivec2 border = min(nearHitTap, extent - 1 - nearHitTap);
            if (nearestMiss < SILHOUETTE_TOLERANCE_PIXELS && min(border.x, border.y) >= 2) {
                hitShore = nearHitShore;
                return refractedTap(nearHitTap, extent, nearHitShore);
            }
            if (farDepth > occluderDepth + REFRACTION_DEPTH_EPSILON) {
                hitShore = farShore;
                return refractedTap(farTap, extent, farShore);
            }
            break;
        }
        if (!strided && nextBoundary.x <= segmentEnd) {
            tap.x += stepDirection.x;
            nextBoundary.x += cellDuration.x;
        }
        if (!strided && nextBoundary.y <= segmentEnd) {
            tap.y += stepDirection.y;
            nextBoundary.y += cellDuration.y;
        }
        segmentStart = segmentEnd;
    }
    return vec4(0.0);
}

#endif

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
#ifdef WATER_MSAA
    ivec2 extent = textureSize(uSceneDepth);
    float deviceDepth = texelFetch(uSceneDepth, pixel, 0).r;
#else
    ivec2 extent = textureSize(uSceneDepth, 0);
    float deviceDepth = texelFetch(uSceneDepth, pixel, 0).r;
#endif
    vec2 ndc = (vec2(pixel) + 0.5) / vec2(extent) * 2.0 - 1.0;

    vec3 waterWorld = vWorldPosition;
    if (uRepairNearPlane == 1) {
        // Two finite clip depths also support orthographic projections. Extend
        // the ray towards the viewer past the raster near plane, not behind it.
        vec4 nearH = uInverseProjection * vec4(ndc, -1.0, 1.0);
        vec4 midH = uInverseProjection * vec4(ndc, 0.0, 1.0);
        if (abs(nearH.w) < 1.0e-6 || abs(midH.w) < 1.0e-6) discard;
        vec3 origin = (uInverseView * vec4(nearH.xyz / nearH.w, 1.0)).xyz;
        vec3 ray = mat3(uInverseView) * normalize(midH.xyz / midH.w - nearH.xyz / nearH.w);
        if (abs(ray.y) < 1.0e-7) discard;
        waterWorld = origin + ray * ((uWaterLevel - origin.y) / ray.y);
        if (!insideWaterFootprint(waterWorld.xz)) discard;
    }
    vec4 waterView = uView * vec4(waterWorld, 1.0);
    float waterDepth = -waterView.z;
    if (!(waterDepth > 0.0) || waterDepth > uFarPlane) discard;

    // Manual occlusion test: the pass has no depth attachment because it must
    // sample the shared scene depth. A surface behind the water plane is treated
    // as deep water; a surface in front of the plane keeps the water hidden.
    float sceneY;
#ifdef WATER_REFRACTION
    vec3 sceneWorld = vec3(0.0);
#endif
    if (deviceDepth >= 1.0) {
        sceneY = uWaterLevel - uFarPlane;
    } else {
        vec4 sceneClip = vec4(ndc, deviceDepth * 2.0 - 1.0, 1.0);
        vec4 sceneViewH = uInverseProjection * sceneClip;
        if (!(abs(sceneViewH.w) > 1.0e-6)) discard;
        vec4 sceneView = sceneViewH / sceneViewH.w;
        float sceneDepth = -sceneView.z;
        float epsilon = max(1.0e-4, waterDepth * 1.0e-4);
        if (sceneDepth + epsilon < waterDepth) discard;
        vec3 position = (uInverseView * vec4(sceneView.xyz, 1.0)).xyz;
        sceneY = position.y;
#ifdef WATER_REFRACTION
        sceneWorld = position;
#endif
    }

    float thickness = uWaterLevel - sceneY;
    if (!(thickness > 0.0)) discard;

    vec3 normal = waveNormal(waterWorld.xz, uTimeSeconds);
    if (uDebugMode == 1) {
        fragColor = vec4(normal * 0.5 + 0.5, 1.0);
        return;
    }

    // Grazing/intersection limit: a view ray almost parallel to the plane has no
    // stable intersection; the pixel is left at the copied background instead of
    // extrapolating an unbounded Fresnel denominator.
    vec3 viewDirection = normalize(waterWorld - uCameraPosition);
    if (abs(viewDirection.y) < GRAZING_EPSILON) discard;

    float nDotV = clamp(dot(normal, -viewDirection), 0.0, 1.0);
    float fresnel = REFLECTION_F0 + (1.0 - REFLECTION_F0) * pow(1.0 - nDotV, 5.0);
    vec3 reflection = uReflectionFallback;
    bool planarSampled = false;
    if (uHasPlanarReflection == 1) {
        vec4 planarClip = uStableViewProjection * vec4(waterWorld, 1.0);
        if (planarClip.w > 0.0) {
            vec2 planarUv = planarClip.xy / planarClip.w * 0.5 + 0.5;
            if (all(greaterThanEqual(planarUv, vec2(0.0)))
                    && all(lessThanEqual(planarUv, vec2(1.0)))) {
                reflection = texture(uPlanarReflection, planarUv).rgb;
                planarSampled = true;
            }
        }
    }
    if (!planarSampled && uHasEnvironment == 1) {
        reflection = rotateEnvironment(reflect(viewDirection, normal));
        reflection = textureLod(uEnvironment, reflection, 0.0).rgb * uEnvironmentIntensity;
    }

    float shore = uShoreFadeDistance <= 0.0 ? 1.0
            : smoothstep(0.0, uShoreFadeDistance, thickness);
    if (!(shore > 0.0)) discard;

#ifdef WATER_REFRACTION
    vec2 centreUv = (vec2(pixel) + 0.5) / vec2(extent);
    vec3 refractionNormal = dot(normal, viewDirection) > 0.0 ? -normal : normal;
    vec3 refracted = refract(viewDirection, refractionNormal, 1.0 / uRefractionIor);
    if (refracted.y < -1.0e-6 && uCameraPosition.y > uWaterLevel) {
        float centreWetPath = thickness / -viewDirection.y;
        float depthFade = smoothstep(0.0, max(uRefractionForegroundBias, 1.0e-4), centreWetPath);
        float hitShore;
        vec4 sampleValue = traceRefractedBackground(waterWorld, refracted, centreUv,
                extent, deviceDepth, thickness, shore, shore * depthFade, hitShore);
        if (sampleValue.a > REFRACTION_COVERAGE_EPSILON) {
            vec3 transmitted = sampleValue.rgb / sampleValue.a;
            fragColor = vec4(mix(transmitted, reflection, fresnel * hitShore), 1.0);
            return;
        }
    }

    // Fade the lighting over the chosen source. In TINT mode neither side of
    // this mix may contain the original, undisplaced object.
    vec3 fallback = uRefractionFallbackMode != 0 ? uRefractionFallbackColor
            : texelFetch(uSceneColor, pixel, 0).rgb;
    float centreDistance = deviceDepth >= 1.0 ? REFRACTION_MAX_PATH_METRES
            : min(length(sceneWorld - waterWorld), REFRACTION_MAX_PATH_METRES);
    vec2 centreInterval = waterInterval(waterWorld, viewDirection, centreDistance);
    float centrePath = centreInterval.y - centreInterval.x;
    vec3 transmitted = mix(uWaterColor, fallback, exp(-uAbsorptionDensity * centrePath));
    fragColor = vec4(mix(fallback, mix(transmitted, reflection, fresnel), shore), 1.0);
#else
    // Preserve the unshifted surface's legacy colour and alpha contract.
    float absorb = 1.0 - exp(-uAbsorptionDensity * thickness);
    vec3 color = mix(uWaterColor, reflection, fresnel);
    float alpha = clamp(shore * mix(absorb, 1.0, fresnel), 0.0, 1.0);
    fragColor = vec4(color, alpha);
#endif
}
