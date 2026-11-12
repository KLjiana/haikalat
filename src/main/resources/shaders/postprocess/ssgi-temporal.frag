#version 460 core

// Optional GI-specific temporal accumulation for screen-space indirect diffuse
// (S5 #39).  The spatial pass writes the current added bounce into a private
// staging target; this pass reprojects the previous accumulated GI through the
// shared single-sample velocity, rejects disoccluded samples per pixel, clamps
// the surviving history to the depth-matched current 3x3 GI neighbourhood and
// blends it with the current sample.
//
// Rejection contract (all thresholds come from SsgiSettings):
//   - the validity channel must be >= 0.5 (camera cut, first frame, invalid
//     deformation and missing previous surface all write zero);
//   - velocity must be finite and the reprojection must stay inside the raster
//     and within temporalVelocityRejection output pixels;
//   - the expected previous view depth comes from the current pixel's
//     previous-surface-depth channel;
//   - every history tap must be flagged valid, must match that expected depth
//     within SsgiSettings.depthTolerance and must have a normal whose dot with
//     the current normal is at least temporalNormalRejection.
//
// One MRT draw writes:
//   attachment 0: the accumulated added bounce (next frame's history);
//   attachment 1: positive view depth, octahedral normal and a validity flag;
//   attachment 2: base + accumulated bounce (the colour-chain output).
//
// Bounded cost: one direct colour/spatial/depth/normal/velocity/validity lookup
// per pixel, at most 4 history taps (colour + state), and one 3x3 current-GI
// neighbourhood. No loop grows with the history length and no pixel is read
// back.

#define SSGI_TEMPORAL_EPSILON 1.0e-6
#define SSGI_TEMPORAL_MAX_VELOCITY 4.0
#define SSGI_TEMPORAL_NEIGHBOURHOOD 1

in vec2 vUv;
layout(location = 0) out vec4 outHistoryColor;
layout(location = 1) out vec4 outState;
layout(location = 2) out vec4 outComposite;

uniform sampler2D uSpatialGi;
uniform sampler2D uColor;
uniform sampler2D uDepth;
uniform sampler2D uNormal;
uniform sampler2D uVelocity;
uniform sampler2D uPreviousDepth;
uniform sampler2D uValidity;
uniform sampler2D uHistoryColor;
uniform sampler2D uHistoryState;

uniform vec2 uViewportSize;
uniform mat4 uInverseProjection;
uniform int uHistoryValid;
uniform float uHistoryWeight;
uniform float uDepthRejection;
uniform float uNormalRejection;
uniform float uVelocityRejection;

vec3 octDecode(vec2 oct) {
    vec3 n = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx))
                * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

bool finite2(vec2 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec2(1.0e12)));
}

bool finite3(vec3 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec3(1.0e12)));
}

float viewDepthAt(vec2 uv, float deviceDepth) {
    vec4 clip = uInverseProjection
            * vec4(uv * 2.0 - 1.0, deviceDepth * 2.0 - 1.0, 1.0);
    if (abs(clip.w) <= SSGI_TEMPORAL_EPSILON) return -1.0;
    return -clip.z / clip.w;
}

float depthToleranceFor(float expectedDepth) {
    return max(1.0e-4, uDepthRejection * max(abs(expectedDepth), 1.0));
}

vec3 currentGi(ivec2 pixel) {
    return max(texelFetch(uSpatialGi, pixel, 0).rgb, vec3(0.0));
}

void main() {
    ivec2 extent = ivec2(uViewportSize);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), extent - ivec2(1));
    vec2 uv = (vec2(pixel) + 0.5) / uViewportSize;
    vec3 base = texelFetch(uColor, pixel, 0).rgb;
    vec3 spatial = currentGi(pixel);
    float deviceDepth = texelFetch(uDepth, pixel, 0).r;

    // Background has no GI receiver and no surface correspondence.
    if (deviceDepth >= 0.999999) {
        outHistoryColor = vec4(0.0);
        outState = vec4(-1.0, 0.0, 0.0, 0.0);
        outComposite = vec4(base, 1.0);
        return;
    }

    float currentDepth = viewDepthAt(uv, deviceDepth);
    vec2 currentOct = texelFetch(uNormal, pixel, 0).rg;
    if (!(currentDepth > 0.0) || !(currentDepth < 1.0e12)) {
        outHistoryColor = vec4(0.0);
        outState = vec4(-1.0, 0.0, 0.0, 0.0);
        outComposite = vec4(base, 1.0);
        return;
    }
    vec3 currentNormal = octDecode(currentOct);

    bool acceptedHistory = false;
    vec3 resolvedHistory = spatial;
    float validity = texelFetch(uValidity, pixel, 0).r;

    if (uHistoryValid != 0 && uHistoryWeight > 0.0 && validity >= 0.5) {
        vec2 velocity = texelFetch(uVelocity, pixel, 0).rg;
        float expectedPreviousDepth = texelFetch(uPreviousDepth, pixel, 0).r;
        bool velocityFinite = finite2(velocity)
                && all(lessThan(abs(velocity), vec2(SSGI_TEMPORAL_MAX_VELOCITY)));
        if (velocityFinite && expectedPreviousDepth > 0.0
                && expectedPreviousDepth < 1.0e12) {
            vec2 previousUv = uv + velocity;
            bool insideRaster = all(greaterThanEqual(previousUv, vec2(0.0)))
                    && all(lessThanEqual(previousUv, vec2(1.0)));
            float reprojectionPixels = length((previousUv - uv) * uViewportSize);
            if (insideRaster && reprojectionPixels <= uVelocityRejection) {
                float tolerance = depthToleranceFor(expectedPreviousDepth);
                vec2 basePixel = previousUv * uViewportSize - 0.5;
                vec2 baseFloor = floor(basePixel);
                ivec2 origin = ivec2(baseFloor);
                vec2 fraction = basePixel - baseFloor;

                vec3 historyAccum = vec3(0.0);
                float historyWeightSum = 0.0;
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 2; x++) {
                        ivec2 samplePixel = origin + ivec2(x, y);
                        if (any(lessThan(samplePixel, ivec2(0)))
                                || any(greaterThanEqual(samplePixel, extent))) {
                            continue;
                        }
                        vec4 state = texelFetch(uHistoryState, samplePixel, 0);
                        if (state.a < 0.5) continue;
                        if (!(state.r > 0.0)
                                || abs(state.r - expectedPreviousDepth) > tolerance) {
                            continue;
                        }
                        vec3 historyNormal = octDecode(state.gb);
                        if (!finite3(historyNormal)
                                || dot(historyNormal, currentNormal) < uNormalRejection) {
                            continue;
                        }
                        float tapWeight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                                * (y == 0 ? 1.0 - fraction.y : fraction.y);
                        historyAccum += texelFetch(uHistoryColor, samplePixel, 0).rgb
                                * tapWeight;
                        historyWeightSum += tapWeight;
                    }
                }

                if (historyWeightSum > SSGI_TEMPORAL_EPSILON) {
                    resolvedHistory = historyAccum / historyWeightSum;
                    acceptedHistory = true;
                }
            }
        }
    }

    // Clamp accepted history to the depth-matched current 3x3 GI neighbourhood.
    // A uniform light/emissive step updates every neighbour in the same frame,
    // so stale bright history is clamped to the new range instead of ghosting.
    if (acceptedHistory) {
        vec3 low = spatial;
        vec3 high = spatial;
        bool hasNeighbour = false;
        float neighbourhoodTolerance = max(depthToleranceFor(currentDepth) * 2.0, 1.0e-4);
        for (int y = -SSGI_TEMPORAL_NEIGHBOURHOOD;
                y <= SSGI_TEMPORAL_NEIGHBOURHOOD; y++) {
            for (int x = -SSGI_TEMPORAL_NEIGHBOURHOOD;
                    x <= SSGI_TEMPORAL_NEIGHBOURHOOD; x++) {
                if (x == 0 && y == 0) continue;
                ivec2 neighbourPixel = pixel + ivec2(x, y);
                if (any(lessThan(neighbourPixel, ivec2(0)))
                        || any(greaterThanEqual(neighbourPixel, extent))) {
                    continue;
                }
                float neighbourDeviceDepth = texelFetch(uDepth, neighbourPixel, 0).r;
                if (neighbourDeviceDepth >= 0.999999) continue;
                vec2 neighbourUv = (vec2(neighbourPixel) + 0.5) / uViewportSize;
                float neighbourDepth = viewDepthAt(neighbourUv, neighbourDeviceDepth);
                if (!(neighbourDepth > 0.0)
                        || abs(neighbourDepth - currentDepth) > neighbourhoodTolerance) {
                    continue;
                }
                vec3 neighbourGi = currentGi(neighbourPixel);
                low = min(low, neighbourGi);
                high = max(high, neighbourGi);
                hasNeighbour = true;
            }
        }
        // An empty depth-matched neighbourhood means the receiver is a thin
        // silhouette (for example the receiver hole in the acceptance fixture);
        // there is no current GI range to clamp to, so the history survives.
        if (hasNeighbour) {
            resolvedHistory = clamp(resolvedHistory, low, high);
        }
    }

    float currentWeight = clamp(1.0 - uHistoryWeight, 0.0, 1.0);
    vec3 added = acceptedHistory
            ? mix(resolvedHistory, spatial, currentWeight) : spatial;
    vec3 result = base + max(added, vec3(0.0));
    if (!finite3(result)) {
        result = base;
    }
    if (!finite3(added)) {
        added = vec3(0.0);
    }
    // Attachment 0 is the accumulated added bounce (the next frame's history);
    // attachment 2 is the composite the colour chain consumes. Keeping them
    // separate prevents the base colour from feeding back into the GI history.
    outHistoryColor = vec4(max(added, vec3(0.0)), 1.0);
    outState = vec4(currentDepth, currentOct.x, currentOct.y, 1.0);
    outComposite = vec4(result, 1.0);
}
