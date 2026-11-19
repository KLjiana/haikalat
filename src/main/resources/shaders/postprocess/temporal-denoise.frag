#version 460 core

// Temporal denoise for one real noisy signal (first binding: SSR / S3 #20).
//
// A pixel is reprojected with either the frozen velocity/previous-depth pair or
// the previous successful stable view-projection.  History is accepted only
// when the previous surface depth and octahedral normal still match, the
// reprojection stays within the configured pixel budget and the luminance did
// not jump enough to trigger the reactive reset.  Accepted history is clamped
// to the current depth-matched neighbourhood and its luminance moments, then
// blended into the current sample.  One MRT draw writes:
//   attachment 0: resolved reflection contribution (RGB) plus the resolved
//                 usable hit confidence in alpha;
//   attachment 1: positive view depth, octahedral normal, surface flag;
//   attachment 2: luminance mean and mean-square for the next sigma clip.
//
// Signal contract (S7 / #34): the input is the bounded SSR reflection
// contribution, not scene colour, and its alpha is the usable hit confidence.
// A zero-confidence pixel has an exact zero contribution: it rejects all
// history, writes zero and therefore composites back to the untouched base
// colour.  History taps must themselves be confident, and the confidence is
// carried through the blend so a reflection that is revealed as background or
// becomes non-reflective cannot persist as a ghost.  The signal may be signed
// (a hit darker than the environment fallback is a negative contribution), so
// the clamp arithmetic never floors a value at zero.
//
// Bounded cost: 4 history taps plus at most (2 * TDN_MAX_NEIGHBORHOOD_RADIUS +
// 1)^2 current taps; no unbounded loop, no readback.

#define TDN_MAX_NEIGHBORHOOD_RADIUS 4
#define TDN_MOMENT_SIGMA 2.0
#define TDN_MIN_DEPTH_TOLERANCE 1.0e-4
#define TDN_EPSILON 1.0e-6

in vec2 vUv;
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outState;
layout(location = 2) out vec2 outMoments;

uniform sampler2D uNoisyColor;
uniform sampler2D uDepth;
uniform sampler2D uNormal;
uniform sampler2D uVelocity;
uniform sampler2D uPreviousDepth;
uniform sampler2D uHistoryColor;
uniform sampler2D uHistoryState;
uniform sampler2D uHistoryMoments;

uniform vec2 uExtent;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform mat4 uPreviousViewProjection;
uniform mat4 uPreviousInverseProjection;
uniform int uHistoryValid;
uniform int uUseVelocity;
uniform vec2 uCurrentJitter;
uniform vec2 uPreviousJitter;
uniform int uNeighborhoodRadius;
uniform float uHistoryBlend;
uniform float uDepthRejectionThreshold;
uniform float uNormalRejectionThreshold;
uniform float uReactiveThreshold;
uniform float uMaxReprojectionPixels;

vec3 octDecode(vec2 oct) {
    vec3 n = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx))
                * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

float luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

bool finite2(vec2 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec2(1.0e12)));
}

bool finite3(vec3 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec3(1.0e12)));
}

float viewDepthAt(vec2 uv, float depth) {
    vec4 clip = uInverseProjection
            * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    if (abs(clip.w) <= TDN_EPSILON) return -1.0;
    return abs(clip.z / clip.w);
}

float depthToleranceFor(float expectedDepth) {
    return max(TDN_MIN_DEPTH_TOLERANCE,
            uDepthRejectionThreshold * max(abs(expectedDepth), 1.0));
}

vec3 currentColor(ivec2 pixel) {
    return texelFetch(uNoisyColor, pixel, 0).rgb;
}

// Usable hit confidence from the signal alpha. A missing, NaN or out-of-range
// alpha fails closed to zero (no reflection) so a broken producer can never
// manufacture ghost radiance.
float confidenceOf(float alpha) {
    return alpha >= 0.0 && alpha <= 1.0 ? alpha : 0.0;
}

void main() {
    ivec2 extent = ivec2(uExtent);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), extent - ivec2(1));
    vec2 uv = (vec2(pixel) + 0.5) / uExtent;
    vec4 noisy = texelFetch(uNoisyColor, pixel, 0);
    vec3 current = noisy.rgb;
    float currentConfidence = confidenceOf(noisy.a);
    if (!finite3(current)) {
        outColor = vec4(0.0);
        outState = vec4(-1.0, 0.0, 0.0, 0.0);
        outMoments = vec2(0.0);
        return;
    }
    float deviceDepth = texelFetch(uDepth, pixel, 0).r;

    // Background has no surface correspondence.  Refuse history instead of
    // blending unrelated radiance into the sky/background.  The SSR signal
    // contract already writes an exact zero contribution here.
    if (deviceDepth >= 0.999999) {
        float backgroundLum = luminance(current);
        outColor = vec4(current, currentConfidence);
        outState = vec4(-1.0, 0.0, 0.0, 0.0);
        outMoments = vec2(backgroundLum, backgroundLum * backgroundLum);
        return;
    }

    float currentDepth = viewDepthAt(uv, deviceDepth);
    if (!(currentDepth > 0.0) || !(currentDepth < 1.0e12)) {
        float fallbackLum = luminance(current);
        outColor = vec4(current, currentConfidence);
        outState = vec4(-1.0, 0.0, 0.0, 0.0);
        outMoments = vec2(fallbackLum, fallbackLum * fallbackLum);
        return;
    }

    vec2 currentOct = texelFetch(uNormal, pixel, 0).rg;
    vec3 currentNormal = octDecode(currentOct);

    bool acceptedHistory = false;
    vec3 resolvedHistory = current;
    float resolvedConfidence = currentConfidence;
    float historyMean = 0.0;
    float historyM2 = 0.0;

    // A non-reflective pixel has no contribution to denoise. It must not accept
    // stale history: that is exactly the revealed-reflection ghost the signal
    // contract forbids. The composite then writes the untouched base colour.
    if (currentConfidence <= 0.0) {
        outColor = vec4(0.0);
        outState = vec4(currentDepth, currentOct.x, currentOct.y, 1.0);
        outMoments = vec2(0.0);
        return;
    }

    if (uHistoryValid != 0) {
        vec2 previousUv = uv;
        float expectedPreviousDepth = -1.0;
        bool reprojected = false;

        if (uUseVelocity != 0) {
            vec2 velocity = texelFetch(uVelocity, pixel, 0).rg;
            if (finite2(velocity)) {
                // Current raster is jittered; velocity is a stable-raster
                // displacement, so remove the current jitter and re-apply the
                // previous frame's jitter to land on its history texel.
                previousUv = uv + velocity - uCurrentJitter + uPreviousJitter;
                expectedPreviousDepth = texelFetch(uPreviousDepth, pixel, 0).r;
                reprojected = expectedPreviousDepth > 0.0;
            }
        } else {
            vec4 currentClip = uInverseProjection
                    * vec4(uv * 2.0 - 1.0, deviceDepth * 2.0 - 1.0, 1.0);
            if (finite3(currentClip.xyz) && abs(currentClip.w) > TDN_EPSILON) {
                vec3 viewPosition = currentClip.xyz / currentClip.w;
                vec3 worldPosition = (uInverseView * vec4(viewPosition, 1.0)).xyz;
                vec4 previousClip = uPreviousViewProjection * vec4(worldPosition, 1.0);
                if (finite3(previousClip.xyz) && previousClip.w > TDN_EPSILON) {
                    // previousViewProjection is stable; the history slot was
                    // rasterised with the previous jitter.
                    previousUv = previousClip.xy / previousClip.w * 0.5 + 0.5
                            + uPreviousJitter;
                    vec4 previousView = uPreviousInverseProjection * previousClip;
                    if (finite3(previousView.xyz) && abs(previousView.w) > TDN_EPSILON) {
                        expectedPreviousDepth = abs(previousView.z / previousView.w);
                        reprojected = expectedPreviousDepth > 0.0;
                    }
                }
            }
        }

        if (reprojected && finite2(previousUv)
                && all(greaterThanEqual(previousUv, vec2(0.0)))
                && all(lessThanEqual(previousUv, vec2(1.0)))
                && length((previousUv - uv) * uExtent) <= uMaxReprojectionPixels) {
            float tolerance = depthToleranceFor(expectedPreviousDepth);
            vec2 base = previousUv * uExtent - 0.5;
            vec2 baseFloor = floor(base);
            ivec2 origin = ivec2(baseFloor);
            vec2 fraction = base - baseFloor;

            vec3 historyAccum = vec3(0.0);
            float historyWeight = 0.0;
            float historyConfidenceAccum = 0.0;
            float meanAccum = 0.0;
            float m2Accum = 0.0;
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    ivec2 samplePixel = origin + ivec2(x, y);
                    if (any(lessThan(samplePixel, ivec2(0)))
                            || any(greaterThanEqual(samplePixel, extent))) {
                        continue;
                    }
                    vec4 state = texelFetch(uHistoryState, samplePixel, 0);
                    if (state.a < 0.5) continue;
                    // A history texel that had no reflection confidence is not
                    // a valid sample: accepting it would blend a zero into a
                    // real reflection (or the reverse) at a confidence edge.
                    vec4 historySample = texelFetch(uHistoryColor, samplePixel, 0);
                    if (!(confidenceOf(historySample.a) > 0.0)) continue;
                    if (!(state.r > 0.0)
                            || abs(state.r - expectedPreviousDepth) > tolerance) {
                        continue;
                    }
                    vec3 historyNormal = octDecode(state.gb);
                    if (!finite3(historyNormal)
                            || dot(historyNormal, currentNormal)
                            < uNormalRejectionThreshold) {
                        continue;
                    }
                    vec2 moments = texelFetch(uHistoryMoments, samplePixel, 0).rg;
                    float tapWeight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                            * (y == 0 ? 1.0 - fraction.y : fraction.y);
                    historyAccum += historySample.rgb * tapWeight;
                    historyConfidenceAccum += confidenceOf(historySample.a) * tapWeight;
                    meanAccum += moments.x * tapWeight;
                    m2Accum += moments.y * tapWeight;
                    historyWeight += tapWeight;
                }
            }

            if (historyWeight > TDN_EPSILON) {
                vec3 historyRgb = historyAccum / historyWeight;
                float historyConfidence = historyConfidenceAccum / historyWeight;
                float historyMeanValue = meanAccum / historyWeight;
                float historyM2Value = m2Accum / historyWeight;
                float historyVariance = max(
                        historyM2Value - historyMeanValue * historyMeanValue, 0.0);
                float historySigma = sqrt(historyVariance) * TDN_MOMENT_SIGMA;
                float rawHistoryLum = luminance(historyRgb);
                float currentLum = luminance(current);
                float shockScale = max(max(abs(currentLum), abs(rawHistoryLum)),
                        TDN_EPSILON);
                bool shocked = abs(currentLum - rawHistoryLum) / shockScale
                        > uReactiveThreshold;

                vec3 low = current;
                vec3 high = current;
                float sum = currentLum;
                float sumSquares = currentLum * currentLum;
                float count = 1.0;
                int radius = clamp(uNeighborhoodRadius, 1,
                        TDN_MAX_NEIGHBORHOOD_RADIUS);
                float neighbourhoodTolerance = max(
                        depthToleranceFor(currentDepth) * 2.0,
                        TDN_MIN_DEPTH_TOLERANCE);
                for (int y = -TDN_MAX_NEIGHBORHOOD_RADIUS;
                        y <= TDN_MAX_NEIGHBORHOOD_RADIUS; y++) {
                    if (abs(y) > radius) continue;
                    for (int x = -TDN_MAX_NEIGHBORHOOD_RADIUS;
                            x <= TDN_MAX_NEIGHBORHOOD_RADIUS; x++) {
                        if (abs(x) > radius) continue;
                        if (x == 0 && y == 0) continue;
                        ivec2 neighbourPixel = pixel + ivec2(x, y);
                        if (any(lessThan(neighbourPixel, ivec2(0)))
                                || any(greaterThanEqual(neighbourPixel, extent))) {
                            continue;
                        }
                        float neighbourDeviceDepth = texelFetch(uDepth,
                                neighbourPixel, 0).r;
                        if (neighbourDeviceDepth >= 0.999999) continue;
                        vec2 neighbourUv = (vec2(neighbourPixel) + 0.5) / uExtent;
                        float neighbourDepth = viewDepthAt(neighbourUv,
                                neighbourDeviceDepth);
                        if (!(neighbourDepth > 0.0)
                                || abs(neighbourDepth - currentDepth)
                                > neighbourhoodTolerance) {
                            continue;
                        }
                        vec3 neighbour = currentColor(neighbourPixel);
                        if (!finite3(neighbour)) continue;
                        // Unreflective neighbours have an exact zero
                        // contribution; they must not drag a confident
                        // reflection's clamp window toward black.
                        if (!(confidenceOf(texelFetch(uNoisyColor,
                                neighbourPixel, 0).a) > 0.0)) {
                            continue;
                        }
                        float neighbourLum = luminance(neighbour);
                        low = min(low, neighbour);
                        high = max(high, neighbour);
                        sum += neighbourLum;
                        sumSquares += neighbourLum * neighbourLum;
                        count += 1.0;
                    }
                }

                float mean = sum / count;
                float variance = max(sumSquares / count - mean * mean, 0.0);
                // The history moments are the temporal variance estimate.  They
                // widen the clamp for a spatially flat current neighbourhood and
                // set the luminance sigma floor; without this use the moments
                // attachment would be dead data.
                float currentSigma = sqrt(variance) * TDN_MOMENT_SIGMA;
                float sigma = max(currentSigma, historySigma);
                if (all(lessThan(abs(high - low), vec3(TDN_EPSILON)))) {
                    // Signed signal: never floor the widened window at zero,
                    // because a reflection darker than the environment is a
                    // legitimate negative contribution.
                    low = low - vec3(historySigma);
                    high = high + vec3(historySigma);
                }
                vec3 clamped = clamp(historyRgb, low, high);
                float clampedLum = luminance(clamped);
                if (abs(clampedLum) > TDN_EPSILON) {
                    float targetLum = clamp(clampedLum,
                            mean - sigma, mean + sigma);
                    clamped *= targetLum / clampedLum;
                }
                clamped = clamp(clamped, low, high);

                if (!shocked) {
                    float currentWeight = clamp(1.0 - uHistoryBlend, 0.0, 1.0);
                    resolvedHistory = mix(clamped, current, currentWeight);
                    // Carry the confidence through the same blend so a fading
                    // reflection reports how much signal it still represents.
                    resolvedConfidence = mix(historyConfidence, currentConfidence,
                            currentWeight);
                    acceptedHistory = true;
                    historyMean = historyMeanValue;
                    historyM2 = historyM2Value;
                }
            }
        }
    }

    float resolvedLum = luminance(resolvedHistory);
    float currentLum = luminance(current);
    float outMean;
    float outM2;
    if (acceptedHistory) {
        float currentWeight = clamp(1.0 - uHistoryBlend, 0.0, 1.0);
        outMean = mix(historyMean, resolvedLum, currentWeight);
        outM2 = mix(historyM2, resolvedLum * resolvedLum, currentWeight);
    } else {
        outMean = currentLum;
        outM2 = currentLum * currentLum;
    }

    outColor = vec4(resolvedHistory, clamp(resolvedConfidence, 0.0, 1.0));
    outState = vec4(currentDepth, currentOct.x, currentOct.y, 1.0);
    outMoments = vec2(outMean, outM2);
}
