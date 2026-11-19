#version 460 core
// Full-resolution reconstruction of the low-resolution cloud signal.
//
// One output pixel:
//   1. resolves the low-resolution cloud (premultiplied scatter + transmittance)
//      with a depth-aware 2x2 bilinear filter: taps whose full-resolution
//      surface depth differs from the output pixel's depth are rejected, so a
//      silhouette does not smear clouds across a depth discontinuity;
//   2. reconstructs a reprojection anchor (the opaque surface, or the cloud
//      layer entry point for sky pixels) and maps it through the previous
//      successful stable view-projection;
//   3. rejects the history when the global flag says the camera was cut or the
//      exposure changed, when the previous pixel is invalid or outside the
//      raster, or when the expected previous anchor depth disagrees with the
//      stored depth (disocclusion);
//   4. clamps the surviving history to the 3x3 low-resolution scatter /
//      transmittance range and blends it with the spatial result.
// Cost is bounded by 4 cloud taps, 4 depth fetches, 9 clamp taps, 1 history
// fetch and 4 bilinear history taps. Nothing is read back.
layout (location = 0) out vec4 outCloud;
layout (location = 1) out vec4 outState;

uniform sampler2D uCloud;
uniform sampler2D uSceneDepth;
uniform sampler2D uHistoryCloud;
uniform sampler2D uHistoryState;
uniform mat4 uView;
uniform mat4 uInverseView;
uniform mat4 uInverseProjection;
uniform mat4 uPreviousView;
uniform mat4 uPreviousViewProjection;
uniform vec2 uFullExtent;
uniform vec2 uLowExtent;
uniform float uLayerBase;
uniform float uLayerTop;
uniform float uDepthToleranceMetres;
uniform float uHistoryBlend;
uniform float uExposure;
uniform int uHistoryValid;

#define CLOUD_EPSILON 1.0e-6

bool cloudSurfaceViewPosition(ivec2 pixel, out vec3 viewPosition) {
    float rawDepth = texelFetch(uSceneDepth, pixel, 0).r;
    if (!(rawDepth < 1.0)) {
        return false;
    }
    vec2 ndc = 2.0 * (vec2(pixel) + 0.5) / uFullExtent - 1.0;
    vec4 view = uInverseProjection * vec4(ndc, 2.0 * rawDepth - 1.0, 1.0);
    viewPosition = view.xyz / view.w;
    return true;
}

// World-space anchor for a sky pixel: the near-plane intersection with the
// cloud slab. A ray that misses the slab has no cloud and no history anchor.
bool cloudSkyAnchor(ivec2 pixel, out vec3 worldPosition) {
    vec2 ndc = 2.0 * (vec2(pixel) + 0.5) / uFullExtent - 1.0;
    vec4 nearPosition = uInverseProjection * vec4(ndc, -1.0, 1.0);
    nearPosition.xyz /= nearPosition.w;
    vec4 farPosition = uInverseProjection * vec4(ndc, 1.0, 1.0);
    farPosition.xyz /= farPosition.w;
    vec3 viewDirection = normalize(farPosition.xyz - nearPosition.xyz);
    vec3 origin = (uInverseView * vec4(nearPosition.xyz, 1.0)).xyz;
    vec3 direction = normalize((uInverseView * vec4(viewDirection, 0.0)).xyz);
    if (abs(direction.y) < 1.0e-6) {
        if (origin.y < uLayerBase || origin.y > uLayerTop) {
            return false;
        }
        worldPosition = origin;
        return true;
    }
    float first = (uLayerBase - origin.y) / direction.y;
    float second = (uLayerTop - origin.y) / direction.y;
    float entry = max(min(first, second), 0.0);
    if (!(max(first, second) > entry)) {
        return false;
    }
    worldPosition = origin + direction * entry;
    return true;
}

// Background/surface mismatch is a rejection; two backgrounds are compatible.
float cloudDepthSimilarity(float centerDepth, float tapDepth) {
    if (centerDepth < 0.0 && tapDepth < 0.0) {
        return 1.0;
    }
    if (centerDepth < 0.0 || tapDepth < 0.0) {
        return 0.0;
    }
    return exp(-abs(centerDepth - tapDepth) / uDepthToleranceMetres);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 maxFullPixel = ivec2(uFullExtent) - 1;
    ivec2 maxLowPixel = ivec2(uLowExtent) - 1;
    vec2 uv = (vec2(pixel) + 0.5) / uFullExtent;

    vec3 surfaceView;
    bool hasSurface = cloudSurfaceViewPosition(pixel, surfaceView);
    float centerDepth = hasSurface ? -surfaceView.z : -1.0;

    vec3 anchorWorld;
    bool anchorDefined;
    if (hasSurface) {
        anchorWorld = (uInverseView * vec4(surfaceView, 1.0)).xyz;
        anchorDefined = true;
    } else {
        anchorDefined = cloudSkyAnchor(pixel, anchorWorld);
    }
    if (!anchorDefined) {
        outCloud = vec4(0.0, 0.0, 0.0, 1.0);
        outState = vec4(-1.0, uExposure, 0.0, 0.0);
        return;
    }
    float anchorDepth = -(uView * vec4(anchorWorld, 1.0)).z;

    // Depth-aware spatial reconstruction of the low-resolution signal.
    vec2 lowCoordinate = uv * uLowExtent - 0.5;
    vec2 base = floor(lowCoordinate);
    vec2 fraction = lowCoordinate - base;
    vec4 spatial = vec4(0.0);
    float weightSum = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            float bilinear = (x == 0 ? 1.0 - fraction.x : fraction.x)
                    * (y == 0 ? 1.0 - fraction.y : fraction.y);
            if (!(bilinear > 0.0)) {
                continue;
            }
            ivec2 texel = clamp(ivec2(base) + ivec2(x, y), ivec2(0), maxLowPixel);
            ivec2 tapPixel = clamp(ivec2((vec2(texel) + 0.5) / uLowExtent * uFullExtent),
                    ivec2(0), maxFullPixel);
            vec3 tapView;
            float tapDepth = cloudSurfaceViewPosition(tapPixel, tapView) ? -tapView.z : -1.0;
            float weight = bilinear * cloudDepthSimilarity(centerDepth, tapDepth);
            if (!(weight > 0.0)) {
                continue;
            }
            spatial += texelFetch(uCloud, texel, 0) * weight;
            weightSum += weight;
        }
    }
    if (weightSum > 1.0e-8) {
        spatial /= weightSum;
    } else {
        ivec2 nearest = clamp(ivec2(floor(lowCoordinate + 0.5)), ivec2(0), maxLowPixel);
        spatial = texelFetch(uCloud, nearest, 0);
    }

    vec4 resolved = spatial;
    float appliedHistoryWeight = 0.0;
    if (uHistoryValid != 0) {
        vec4 clip = uPreviousViewProjection * vec4(anchorWorld, 1.0);
        if (clip.w > CLOUD_EPSILON) {
            vec2 previousUv = clip.xy / clip.w * 0.5 + 0.5;
            if (all(greaterThanEqual(previousUv, vec2(0.0)))
                    && all(lessThanEqual(previousUv, vec2(1.0)))) {
                ivec2 historyPixel = clamp(ivec2(previousUv * uFullExtent),
                        ivec2(0), maxFullPixel);
                vec4 state = texelFetch(uHistoryState, historyPixel, 0);
                float expectedDepth = -(uPreviousView * vec4(anchorWorld, 1.0)).z;
                float tolerance = max(1.0e-4, 0.02 * max(expectedDepth, 1.0));
                if (state.a >= 0.5 && abs(state.r - expectedDepth) <= tolerance) {
                    ivec2 center = clamp(ivec2(floor(lowCoordinate + 0.5)),
                            ivec2(0), maxLowPixel);
                    vec4 minimumCloud = vec4(1.0e30);
                    vec4 maximumCloud = vec4(-1.0e30);
                    for (int y = -1; y <= 1; y++) {
                        for (int x = -1; x <= 1; x++) {
                            vec4 value = texelFetch(uCloud,
                                    clamp(center + ivec2(x, y), ivec2(0), maxLowPixel), 0);
                            minimumCloud = min(minimumCloud, value);
                            maximumCloud = max(maximumCloud, value);
                        }
                    }
                    vec4 history = clamp(texture(uHistoryCloud, previousUv),
                            minimumCloud, maximumCloud);
                    float historyWeight = clamp(uHistoryBlend, 0.0, 1.0);
                    resolved = mix(spatial, history, historyWeight);
                    appliedHistoryWeight = historyWeight;
                }
            }
        }
    }
    outCloud = vec4(max(resolved.rgb, vec3(0.0)), clamp(resolved.a, 0.0, 1.0));
    outState = vec4(anchorDepth, uExposure, appliedHistoryWeight, 1.0);
}
