#version 460 core

// Stochastic transparency temporal resolve (S4 #37).
//
// One full-screen MRT draw turns the per-frame stochastic estimate into a
// reproducible accumulated estimate:
//
//   uFrameSummation (RGBA32F)  rgb = sum of accepted sample colours,
//                              a = accepted sample count
//   uSceneDepth (borrowed)     single-sample stable-raster scene depth
//   uHistoryColor (RGBA16F)    rgb = premultiplied accumulated colour,
//                              a = accumulated coverage
//   uHistoryState (RGBA32F)    r = coverage mean, g = coverage second moment,
//                              b = positive view depth, a = valid flag
//
// Reprojection uses the previous successful stable view-projection stored by
// the pass.  History is rejected when the reprojection leaves the raster, when
// it exceeds the configured screen-space budget, when the scene depth window
// does not match, or when the coverage shock exceeds the reactive window.
// Accepted history is clamped to the depth-matched current neighbourhood
// (coverage AABB plus 2-sigma moments, per-channel colour AABB) and blended with
// the current frame.  The shared scene depth is sampled read-only; this pass
// writes only its own resolved attachments.  Every loop is compile-time
// bounded and there is no readback.

#define ST_MAX_NEIGHBORHOOD_RADIUS 4
#define ST_MAX_COLOR 6.5504e4
#define ST_EPSILON 1.0e-6
#define ST_SIGMA_FACTOR 2.0
#define ST_MAX_HISTORY_WEIGHT 0.985

in vec2 vUv;

layout(location = 0) out vec4 oResolvedColor;
layout(location = 1) out vec4 oState;

uniform sampler2D uFrameSummation;
uniform sampler2D uSceneDepth;
uniform sampler2D uHistoryColor;
uniform sampler2D uHistoryState;

uniform vec2 uExtent;
uniform int uSamplesPerFrame;
uniform int uHistoryValid;
uniform float uHistoryWeight;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform mat4 uPreviousViewProjection;
uniform int uNeighborhoodRadius;
uniform float uReactiveThreshold;
uniform float uDepthRejectionThreshold;
uniform float uVelocityRejectionPixels;

bool stFinite2(vec2 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec2(1.0e12)));
}

bool stFinite3(vec3 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec3(1.0e12)));
}

bool stFinite4(vec4 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec4(1.0e12)));
}

float stDepthTolerance(float depth) {
    return max(1.0e-4, uDepthRejectionThreshold * max(abs(depth), 1.0));
}

// Normalised current-frame estimate: premultiplied colour in rgb, coverage in a.
vec4 stCurrentEstimate(ivec2 pixel) {
    vec4 sum = texelFetch(uFrameSummation, pixel, 0);
    float samples = float(max(uSamplesPerFrame, 1));
    if (!stFinite4(sum)) {
        return vec4(0.0);
    }
    float coverage = clamp(sum.a / samples, 0.0, 1.0);
    vec3 premultiplied = clamp(sum.rgb / samples, vec3(0.0), vec3(ST_MAX_COLOR));
    return vec4(premultiplied, coverage);
}

// Positive view-space depth of a stable-raster device depth; background (or a
// non-finite value) is reconstructed at the far plane so rotation still
// reprojects while translation contributes only a small, bounded shift.
float stViewDepth(vec2 uv, float deviceDepth) {
    float rasterDepth = (isnan(deviceDepth) || deviceDepth >= 0.999999)
            ? 1.0 : clamp(deviceDepth, 0.0, 1.0);
    vec4 depthClip = uInverseProjection * vec4(uv * 2.0 - 1.0, rasterDepth * 2.0 - 1.0, 1.0);
    if (!stFinite4(depthClip) || abs(depthClip.w) <= ST_EPSILON) {
        return -1.0;
    }
    return abs(depthClip.z / depthClip.w);
}

void main() {
    ivec2 extent = ivec2(uExtent);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), extent - ivec2(1));
    vec2 uv = (vec2(pixel) + 0.5) / uExtent;

    vec4 current = stCurrentEstimate(pixel);
    float currentCoverage = current.a;
    vec3 currentAverage = currentCoverage > ST_EPSILON
            ? current.rgb / currentCoverage : vec3(0.0);

    float sceneDepth = texelFetch(uSceneDepth, pixel, 0).r;
    float currentViewDepth = -1.0;
    vec2 previousUv = uv;
    bool reprojected = false;

    float rasterDepth = (isnan(sceneDepth) || sceneDepth >= 0.999999)
            ? 1.0 : clamp(sceneDepth, 0.0, 1.0);
    vec4 currentClip = uInverseProjection * vec4(uv * 2.0 - 1.0, rasterDepth * 2.0 - 1.0, 1.0);
    if (stFinite4(currentClip) && abs(currentClip.w) > ST_EPSILON) {
        vec3 viewPosition = currentClip.xyz / currentClip.w;
        currentViewDepth = abs(viewPosition.z);
        vec4 world = uInverseView * vec4(viewPosition, 1.0);
        if (stFinite4(world) && abs(world.w) > ST_EPSILON) {
            vec3 worldPosition = world.xyz / world.w;
            vec4 previousClip = uPreviousViewProjection * vec4(worldPosition, 1.0);
            if (stFinite4(previousClip) && previousClip.w > ST_EPSILON) {
                vec2 candidate = previousClip.xy / previousClip.w * 0.5 + 0.5;
                if (stFinite2(candidate)) {
                    previousUv = candidate;
                    reprojected = true;
                }
            }
        }
    }
    bool depthKnown = currentViewDepth > 0.0 && currentViewDepth < 1.0e12;

    bool historyAccepted = uHistoryValid != 0 && reprojected && depthKnown
            && all(greaterThanEqual(previousUv, vec2(0.0)))
            && all(lessThanEqual(previousUv, vec2(1.0)))
            && length((previousUv - uv) * uExtent) <= uVelocityRejectionPixels;

    vec4 historyColour = vec4(0.0);
    vec4 historyState = vec4(0.0);
    float historyWeightSum = 0.0;
    if (historyAccepted) {
        vec2 base = previousUv * uExtent - 0.5;
        vec2 baseFloor = floor(base);
        vec2 fraction = base - baseFloor;
        ivec2 origin = ivec2(baseFloor);
        float tolerance = stDepthTolerance(currentViewDepth);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                ivec2 tapPixel = origin + ivec2(x, y);
                if (any(lessThan(tapPixel, ivec2(0)))
                        || any(greaterThanEqual(tapPixel, extent))) {
                    continue;
                }
                vec4 state = texelFetch(uHistoryState, tapPixel, 0);
                if (!(state.a >= 0.5) || !stFinite4(state)) {
                    continue;
                }
                if (!(state.b > 0.0)
                        || abs(state.b - currentViewDepth) > tolerance) {
                    continue;
                }
                float tapWeight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                        * (y == 0 ? 1.0 - fraction.y : fraction.y);
                if (tapWeight <= 0.0) {
                    continue;
                }
                vec4 colour = texelFetch(uHistoryColor, tapPixel, 0);
                if (!stFinite4(colour)) {
                    continue;
                }
                historyColour += colour * tapWeight;
                historyState += state * tapWeight;
                historyWeightSum += tapWeight;
            }
        }
    }
    if (historyWeightSum > ST_EPSILON) {
        historyColour /= historyWeightSum;
        historyState /= historyWeightSum;
    } else {
        historyAccepted = false;
    }
    float historyCoverage = historyColour.a;

    // The accepted history must stay inside the current depth-matched
    // distribution: coverage AABB plus moments and a per-channel colour AABB
    // over neighbours that carry current coverage.  The centre colour seeds the
    // bounds when the centre itself is covered, so a one-pixel particle cannot
    // inherit an unclamped history colour.
    vec3 low = currentAverage;
    vec3 high = currentAverage;
    bool haveColourBounds = currentCoverage > ST_EPSILON;
    float minimumCoverage = currentCoverage;
    float maximumCoverage = currentCoverage;
    float coverageSum = currentCoverage;
    float coverageSquares = currentCoverage * currentCoverage;
    float neighbourCount = 1.0;
    int radius = clamp(uNeighborhoodRadius, 1, ST_MAX_NEIGHBORHOOD_RADIUS);
    float neighbourDepthWindow = max(stDepthTolerance(currentViewDepth) * 2.0, 1.0e-4);
    for (int y = -ST_MAX_NEIGHBORHOOD_RADIUS; y <= ST_MAX_NEIGHBORHOOD_RADIUS; y++) {
        if (abs(y) > radius) {
            continue;
        }
        for (int x = -ST_MAX_NEIGHBORHOOD_RADIUS; x <= ST_MAX_NEIGHBORHOOD_RADIUS; x++) {
            if (abs(x) > radius || (x == 0 && y == 0)) {
                continue;
            }
            ivec2 neighbourPixel = pixel + ivec2(x, y);
            if (any(lessThan(neighbourPixel, ivec2(0)))
                    || any(greaterThanEqual(neighbourPixel, extent))) {
                continue;
            }
            float neighbourDeviceDepth = texelFetch(uSceneDepth, neighbourPixel, 0).r;
            vec2 neighbourUv = (vec2(neighbourPixel) + 0.5) / uExtent;
            float neighbourViewDepth = stViewDepth(neighbourUv, neighbourDeviceDepth);
            if (!(neighbourViewDepth > 0.0)
                    || abs(neighbourViewDepth - currentViewDepth) > neighbourDepthWindow) {
                continue;
            }
            vec4 neighbour = stCurrentEstimate(neighbourPixel);
            minimumCoverage = min(minimumCoverage, neighbour.a);
            maximumCoverage = max(maximumCoverage, neighbour.a);
            coverageSum += neighbour.a;
            coverageSquares += neighbour.a * neighbour.a;
            neighbourCount += 1.0;
            if (neighbour.a > ST_EPSILON) {
                vec3 neighbourAverage = neighbour.rgb / neighbour.a;
                if (!haveColourBounds) {
                    low = neighbourAverage;
                    high = neighbourAverage;
                    haveColourBounds = true;
                } else {
                    low = min(low, neighbourAverage);
                    high = max(high, neighbourAverage);
                }
            }
        }
    }
    float coverageMean = coverageSum / neighbourCount;
    float coverageVariance = max(coverageSquares / neighbourCount
            - coverageMean * coverageMean, 0.0);
    float coverageSigma = sqrt(coverageVariance) * ST_SIGMA_FACTOR;

    // Reactive rejection: a deep per-pixel coverage shock is treated as a new
    // signal.  A colour-only change is handled by the current-neighbourhood
    // colour clamp, so this window is deliberately on coverage: a colour window
    // on stochastic mixtures would false-trigger on the per-frame sampling
    // noise and prevent convergence.
    bool reactive = historyAccepted && historyCoverage > ST_EPSILON
            && abs(currentCoverage - historyCoverage) > uReactiveThreshold;

    float weight = (historyAccepted && !reactive && historyCoverage > ST_EPSILON)
            ? clamp(uHistoryWeight, 0.0, ST_MAX_HISTORY_WEIGHT)
            : 0.0;

    vec3 outPremultiplied = current.rgb;
    float outCoverage = currentCoverage;
    float outSquaredCoverage = currentCoverage * currentCoverage;
    if (weight > 0.0) {
        float clampedCoverage = clamp(historyCoverage, minimumCoverage, maximumCoverage);
        clampedCoverage = clamp(clampedCoverage,
                max(coverageMean - coverageSigma, minimumCoverage),
                min(coverageMean + coverageSigma, maximumCoverage));
        vec3 clampedAverage = historyColour.rgb / historyCoverage;
        if (haveColourBounds) {
            clampedAverage = clamp(clampedAverage, low, high);
        }
        vec3 clampedPremultiplied = clampedCoverage * clampedAverage;
        if (!stFinite3(clampedPremultiplied)) {
            clampedPremultiplied = current.rgb;
            clampedCoverage = currentCoverage;
        }
        outCoverage = mix(currentCoverage, clampedCoverage, weight);
        outPremultiplied = mix(current.rgb, clampedPremultiplied, weight);
        outSquaredCoverage = mix(currentCoverage * currentCoverage,
                max(historyState.g, 0.0), weight);
    }

    outCoverage = clamp(outCoverage, 0.0, 1.0);
    outPremultiplied = clamp(outPremultiplied, vec3(0.0), vec3(ST_MAX_COLOR));
    if (isnan(outCoverage) || isinf(outCoverage)
            || any(isnan(outPremultiplied)) || any(isinf(outPremultiplied))) {
        outCoverage = 0.0;
        outPremultiplied = vec3(0.0);
        outSquaredCoverage = 0.0;
    }
    oResolvedColor = vec4(outPremultiplied, outCoverage);
    oState = vec4(outCoverage, outSquaredCoverage,
            depthKnown ? currentViewDepth : -1.0,
            depthKnown ? 1.0 : 0.0);
}
