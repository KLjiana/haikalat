#version 460 core

// Temporal upscaling resolve for an independently rendered low-resolution
// colour signal.
//
// One output pixel:
//   1. maps its stable output position into the jittered low-resolution raster,
//      samples the current colour bilinearly and takes the depth of the
//      containing input texel;
//   2. reconstructs the view-space surface with the jittered inverse projection
//      and reprojects it with the previous successful stable view-projection,
//      or uses the frozen velocity/previous-depth pair;
//   3. rejects background, out-of-raster, over-budget, depth/exposure-inconsistent,
//      reactive and luminance-shocked history;
//   4. clamps the surviving history to the current depth-matched input
//      neighbourhood and blends it with the current sample;
//   5. writes the resolved colour and its depth/exposure state.
//
// Bounded cost: at most 4 current bilinear taps, 4 history taps and
// (2 * TUS_MAX_NEIGHBORHOOD_RADIUS + 1)^2 current-neighbourhood taps.  The pass
// never samples its own write target and never reads the framebuffer back.

#define TUS_MAX_NEIGHBORHOOD_RADIUS 3
#define TUS_MIN_DEPTH_TOLERANCE 1.0e-4
#define TUS_DEPTH_REJECTION_THRESHOLD 0.02
#define TUS_EPSILON 1.0e-6

in vec2 vUv;
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outState;

uniform sampler2D uColor;
uniform sampler2D uDepth;
uniform sampler2D uVelocity;
uniform sampler2D uPreviousDepth;
uniform sampler2D uReactive;
uniform sampler2D uUiMask;
uniform sampler2D uHistoryColor;
uniform sampler2D uHistoryState;

uniform vec2 uInputExtent;
uniform vec2 uOutputExtent;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform mat4 uPreviousViewProjection;
uniform mat4 uPreviousInverseProjection;
uniform int uHistoryValid;
uniform int uUseVelocity;
uniform int uHasReactive;
uniform int uHasUiMask;
uniform vec2 uCurrentJitter;
uniform int uNeighborhoodRadius;
uniform float uHistoryBlend;
uniform float uReactiveThreshold;
uniform float uExposureThreshold;
uniform float uExposure;
uniform float uMaxReprojectionPixels;

bool finite2(vec2 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec2(1.0e12)));
}

bool finite3(vec3 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec3(1.0e12)));
}

float luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

bool inRaster(vec2 uv) {
    return all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
}

vec4 currentBilinear(vec2 uv) {
    vec2 texel = uv * uInputExtent - 0.5;
    vec2 base = floor(texel);
    vec2 fraction = texel - base;
    ivec2 origin = ivec2(base);
    ivec2 maximum = ivec2(uInputExtent) - ivec2(1);
    vec4 sum = vec4(0.0);
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            ivec2 pixel = clamp(origin + ivec2(x, y), ivec2(0), maximum);
            float weight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                    * (y == 0 ? 1.0 - fraction.y : fraction.y);
            sum += texelFetch(uColor, pixel, 0) * weight;
        }
    }
    return sum;
}

vec4 viewPositionAt(vec2 rasterUv, float deviceDepth) {
    vec4 clip = uInverseProjection
            * vec4(rasterUv * 2.0 - 1.0, deviceDepth * 2.0 - 1.0, 1.0);
    if (abs(clip.w) <= TUS_EPSILON) return vec4(0.0);
    // xyz is the view-space position; w keeps the projection sign so callers can
    // reject positions behind the eye.
    return vec4(clip.xyz / clip.w, clip.w);
}

float viewDepthAt(vec2 rasterUv, float deviceDepth) {
    vec4 view = viewPositionAt(rasterUv, deviceDepth);
    return view.w > 0.0 ? abs(view.z) : -1.0;
}

float depthToleranceFor(float expectedDepth) {
    return max(TUS_MIN_DEPTH_TOLERANCE,
            TUS_DEPTH_REJECTION_THRESHOLD * max(abs(expectedDepth), 1.0));
}

bool exposureAccepted(float previousExposure) {
    if (!(previousExposure > 0.0) || !(uExposure > 0.0)) return false;
    float relative = abs(uExposure - previousExposure)
            / max(max(uExposure, previousExposure), TUS_EPSILON);
    return relative <= uExposureThreshold;
}

void main() {
    ivec2 outputExtent = ivec2(uOutputExtent);
    ivec2 outputPixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), outputExtent - ivec2(1));
    vec2 outputUv = (vec2(outputPixel) + 0.5) / uOutputExtent;

    // The output history raster is the stable display raster.  The current
    // low-resolution signal may have been rasterised with a sub-pixel jitter, so
    // the colour sample is shifted by it and the jittered inverse projection
    // recovers the stable surface at this output pixel.  The stable position is
    // the output UV itself: the low-res texel centre is only used to fetch the
    // depth/velocity/mask channels, never as the reprojection origin.
    vec2 sampleUv = outputUv + uCurrentJitter;
    ivec2 inputExtent = ivec2(uInputExtent);
    ivec2 inputPixel = clamp(ivec2(floor(sampleUv * uInputExtent)),
            ivec2(0), inputExtent - ivec2(1));

    vec4 current = currentBilinear(sampleUv);
    float deviceDepth = texelFetch(uDepth, inputPixel, 0).r;
    if (deviceDepth >= 0.999999) {
        outColor = current;
        outState = vec4(-1.0, uExposure, 0.0, 0.0);
        return;
    }

    float currentDepth = viewDepthAt(sampleUv, deviceDepth);
    if (!(currentDepth > 0.0) || !(currentDepth < 1.0e12)) {
        outColor = current;
        outState = vec4(-1.0, uExposure, 0.0, 0.0);
        return;
    }

    bool hasReactive = uHasReactive != 0;
    bool hasUiMask = uHasUiMask != 0;
    bool reactive = hasReactive && texelFetch(uReactive, inputPixel, 0).r > 0.5;
    bool ui = hasUiMask && texelFetch(uUiMask, inputPixel, 0).r > 0.5;

    // Current depth-matched input neighbourhood used as the spatial clamp.
    vec3 low = current.rgb;
    vec3 high = current.rgb;
    int radius = clamp(uNeighborhoodRadius, 1, TUS_MAX_NEIGHBORHOOD_RADIUS);
    float neighbourhoodTolerance = max(depthToleranceFor(currentDepth) * 2.0,
            TUS_MIN_DEPTH_TOLERANCE);
    for (int y = -TUS_MAX_NEIGHBORHOOD_RADIUS;
            y <= TUS_MAX_NEIGHBORHOOD_RADIUS; y++) {
        if (abs(y) > radius) continue;
        for (int x = -TUS_MAX_NEIGHBORHOOD_RADIUS;
                x <= TUS_MAX_NEIGHBORHOOD_RADIUS; x++) {
            if (abs(x) > radius) continue;
            if (x == 0 && y == 0) continue;
            ivec2 neighbourPixel = inputPixel + ivec2(x, y);
            if (any(lessThan(neighbourPixel, ivec2(0)))
                    || any(greaterThanEqual(neighbourPixel, inputExtent))) {
                continue;
            }
            float neighbourDeviceDepth = texelFetch(uDepth, neighbourPixel, 0).r;
            if (neighbourDeviceDepth >= 0.999999) continue;
            vec2 neighbourUv = (vec2(neighbourPixel) + 0.5) / uInputExtent;
            float neighbourDepth = viewDepthAt(neighbourUv, neighbourDeviceDepth);
            if (!(neighbourDepth > 0.0)
                    || abs(neighbourDepth - currentDepth) > neighbourhoodTolerance) {
                continue;
            }
            vec3 neighbour = texelFetch(uColor, neighbourPixel, 0).rgb;
            if (!finite3(neighbour)) continue;
            low = min(low, neighbour);
            high = max(high, neighbour);
        }
    }

    vec3 resolved = current.rgb;
    if (uHistoryValid != 0 && !ui && !reactive) {
        vec2 previousUv = vec2(0.0);
        float expectedPreviousDepth = -1.0;
        float displacementPixels = 1.0e12;
        bool reprojected = false;

        if (uUseVelocity != 0) {
            vec2 velocity = texelFetch(uVelocity, inputPixel, 0).rg;
            if (finite2(velocity)) {
                // Velocity is a stable-raster UV displacement; the history
                // raster is stable, so the previous stable position is the
                // output position plus that displacement.
                previousUv = outputUv + velocity;
                ivec2 previousPixel = clamp(
                        ivec2(floor(previousUv * uInputExtent)),
                        ivec2(0), inputExtent - ivec2(1));
                expectedPreviousDepth = texelFetch(uPreviousDepth, previousPixel, 0).r;
                displacementPixels = length(velocity * uOutputExtent);
                reprojected = expectedPreviousDepth > 0.0;
            }
        } else {
            vec4 viewPosition = viewPositionAt(sampleUv, deviceDepth);
            if (viewPosition.w > 0.0 && finite3(viewPosition.xyz)) {
                vec3 worldPosition = (uInverseView * vec4(viewPosition.xyz, 1.0)).xyz;
                vec4 previousClip = uPreviousViewProjection * vec4(worldPosition, 1.0);
                if (finite3(previousClip.xyz) && previousClip.w > TUS_EPSILON) {
                    vec2 previousStableUv = previousClip.xy / previousClip.w * 0.5 + 0.5;
                    vec4 previousView = uPreviousInverseProjection * previousClip;
                    if (finite3(previousView.xyz) && abs(previousView.w) > TUS_EPSILON) {
                        previousUv = previousStableUv;
                        expectedPreviousDepth = abs(previousView.z / previousView.w);
                        displacementPixels = length(
                                (previousStableUv - outputUv) * uOutputExtent);
                        reprojected = expectedPreviousDepth > 0.0;
                    }
                }
            }
        }

        if (reprojected && finite2(previousUv) && inRaster(previousUv)
                && displacementPixels <= uMaxReprojectionPixels) {
            float tolerance = depthToleranceFor(expectedPreviousDepth);
            vec2 originBase = previousUv * uOutputExtent - 0.5;
            vec2 originFloor = floor(originBase);
            ivec2 origin = ivec2(originFloor);
            vec2 fraction = originBase - originFloor;

            vec3 historyAccum = vec3(0.0);
            float historyWeight = 0.0;
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    ivec2 samplePixel = origin + ivec2(x, y);
                    if (any(lessThan(samplePixel, ivec2(0)))
                            || any(greaterThanEqual(samplePixel, outputExtent))) {
                        continue;
                    }
                    vec4 state = texelFetch(uHistoryState, samplePixel, 0);
                    if (state.a < 0.5) continue;
                    if (!(state.r > 0.0)
                            || abs(state.r - expectedPreviousDepth) > tolerance) {
                        continue;
                    }
                    if (!exposureAccepted(state.g)) continue;
                    float tapWeight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                            * (y == 0 ? 1.0 - fraction.y : fraction.y);
                    historyAccum += texelFetch(uHistoryColor, samplePixel, 0).rgb
                            * tapWeight;
                    historyWeight += tapWeight;
                }
            }

            if (historyWeight > TUS_EPSILON) {
                vec3 historyRgb = historyAccum / historyWeight;
                float currentLum = luminance(current.rgb);
                float historyLum = luminance(historyRgb);
                float shockScale = max(max(abs(currentLum), abs(historyLum)),
                        TUS_EPSILON);
                bool shocked = abs(currentLum - historyLum) / shockScale
                        > uReactiveThreshold;
                if (!shocked) {
                    vec3 clamped = clamp(historyRgb, low, high);
                    float currentWeight = clamp(1.0 - uHistoryBlend, 0.0, 1.0);
                    resolved = mix(clamped, current.rgb, currentWeight);
                }
            }
        }
    }

    outColor = vec4(resolved, current.a);
    outState = vec4(currentDepth, uExposure, 0.0, 1.0);
}
