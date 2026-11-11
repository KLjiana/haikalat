#version 460 core
// HAIKALAT_VOLUME_FOG_CONTRACT

#define POINT_SHADOW_FACE_COUNT 6
#define CONTACT_SHADOW_MAX_STEPS 32
#define CLOUD_SHADOW_OCTAVES 3
#define PCSS_MAX_BLOCKER_TAPS 32
#define PCSS_MAX_FILTER_TAPS 32
#define PCSS_MAX_BLOCKER_RADIUS_TEXELS 16
#define PCSS_MAX_FILTER_RADIUS_TEXELS 16

struct LightRecord {
    vec4 positionRange;    // world xyz, local range
    vec4 directionOuter;   // world direction, spot outer cone
    vec4 colorIntensity;   // linear rgb, intensity
    vec4 extra;            // spot inner cone, view-space position xyz
    ivec4 metadata;        // type (0 directional / 1 point / 2 spot), shadow slot, flags, 0
};

layout(std430, binding = 0) readonly buffer LightTableBlock {
    uvec4 uLightHeader;    // directionalCount, localCount, totalCount, reserved
    LightRecord uLights[];
};

layout(std430, binding = 2) readonly buffer ClusterHeadersBlock {
    uvec4 uClusterHeaders[];   // offset, count, overflowFlag, trueCount
};

layout(std430, binding = 3) readonly buffer ClusterIndicesBlock {
    uint uClusterIndices[];
};

#ifndef HAIKALAT_VOLUME_FOG
layout(std140, binding = 5) uniform ShadowSamplingBlock {
    ivec4 uPointShadowMeta[2];
    mat4 uPointSlotMatrices[2 * POINT_SHADOW_FACE_COUNT];
    vec4 uPointFaceRects[2 * POINT_SHADOW_FACE_COUNT];
    ivec4 uSpotShadowMeta[4];
    mat4 uSpotSlotMatrices[4];
    vec4 uSpotTileRects[4];
    ivec4 uShadowQualityMeta;
};
#endif

layout(std140, binding = 6) uniform ClusterParametersBlock {
    mat4 uStableView;
    mat4 uStableViewProjection;
    mat4 uInverseProjection;
    ivec4 uGridParams;      // nx, ny, nz, inlineCapacity
    vec4 uDepthParams;      // near, far, perspective(1/0), reserved
    ivec4 uLightCounts;     // directionalCount, localCount, reserved, reserved
    vec4 uEdgeExpand;       // ndc expand x, ndc expand y, reserved, reserved
};

in vec2 vTexCoord;
in vec3 vWorldPosition;
in vec3 vNormal;
in vec3 vTangent;
in float vTangentHandedness;
in vec4 vDirectionalLightPosition;
in vec4 vDirectionalCascadePosition[4];
in float vViewDepth;
in vec4 vVertexColor;
layout(location = 0) out vec4 FragColor;

uniform sampler2D uBaseColorMap;
uniform sampler2D uNormalMap;
uniform sampler2D uMetallicRoughnessMap;
uniform sampler2D uOcclusionMap;
uniform sampler2D uEmissiveMap;
#ifndef HAIKALAT_VOLUME_FOG
uniform sampler2D uShadowMap;
uniform sampler2D uPointShadowMap;
uniform sampler2D uSpotShadowMap;
#endif
uniform samplerCube uIrradianceMap;
uniform samplerCube uPrefilteredMap;
uniform sampler2D uBrdfLut;
uniform sampler2D uGtaoMap;

uniform vec4 uBaseColorFactor;
uniform float uMetallicFactor;
uniform float uRoughnessFactor;
uniform float uNormalScale;
uniform float uOcclusionStrength;
uniform vec3 uEmissiveFactor;
uniform int uClusterDebugMode;
uniform float uEnvironmentIntensity;
uniform float uEnvironmentRotation;
uniform float uPrefilterMaxLod;
uniform vec3 uCameraPosition;
uniform int uHasDirectionalShadow;
uniform int uDirectionalShadowFrameLightIndex;
uniform float uShadowBias;
uniform int uPcssEnabled;
uniform float uPcssLightSize;
uniform int uPcssBlockerRadiusTexels;
uniform int uPcssBlockerTaps;
uniform int uPcssFilterRadiusTexels;
uniform int uPcssFilterTaps;
uniform float uPcssDepthBias;
uniform float uDirectionalCascadeTexelWorld[4];
uniform int uContactShadowEnabled;
uniform float uContactShadowDistance;
uniform float uContactShadowThickness;
uniform int uContactShadowSteps;
uniform float uContactShadowStrength;
uniform int uCloudShadowEnabled;
uniform float uCloudShadowCoverage;
uniform float uCloudShadowSoftness;
uniform float uCloudShadowScale;
uniform float uCloudShadowSpeed;
uniform float uCloudShadowStrength;
uniform float uCloudShadowTime;
uniform int uDirectionalCascadeCount;
uniform float uDirectionalCascadeSplits[4];
uniform float uDirectionalCascadeBlendRange;
uniform mat4 uDirectionalLightSpace;
uniform mat4 uDirectionalCascadeMatrices[4];
uniform int uHasPointShadow;
uniform float uPointShadowBias;
uniform int uHasSpotShadow;
uniform float uSpotShadowBias;
uniform int uEnableDirect;
uniform int uEnableDiffuseIbl;
uniform int uEnableSpecularIbl;
uniform int uEnableNormalMap;
uniform int uHasVertexColor;
uniform int uDoubleSided;
uniform float uAlphaCutoff;
uniform int uAlphaMode; // 0 OPAQUE, 1 MASK, 2 BLEND
uniform int uGtaoEnabled;
// Zero is the backwards-compatible default; demo/host materials can opt out.
uniform int uGtaoMaterialOptOut;
// Debug-only material switch used by the GTAO contact acceptance scene.
uniform int uGtaoPreview;

const float PI = 3.14159265358979323846;

int shadowKernelRadius() {
    return clamp(uShadowQualityMeta.x, 0, 2);
}

float shadowNormalBias() {
    return intBitsToFloat(uShadowQualityMeta.y);
}

float shadowDepthBias(float baseBias, vec3 n, vec3 l) {
    return max(baseBias * (1.0 - dot(n, l)), baseBias * 0.25);
}

vec3 rotateEnvironment(vec3 direction) {
    float c = cos(uEnvironmentRotation);
    float s = sin(uEnvironmentRotation);
    return vec3(c * direction.x - s * direction.z, direction.y,
                s * direction.x + c * direction.z);
}

float distributionGgx(vec3 n, vec3 h, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float nDotH = max(dot(n, h), 0.0);
    float denominator = nDotH * nDotH * (a2 - 1.0) + 1.0;
    return a2 / max(PI * denominator * denominator, 1.0e-6);
}

float geometrySchlickGgx(float nDotV, float roughness) {
    float r = roughness + 1.0;
    float k = r * r * 0.125;
    return nDotV / max(nDotV * (1.0 - k) + k, 1.0e-6);
}

float geometrySmith(vec3 n, vec3 v, vec3 l, float roughness) {
    return geometrySchlickGgx(max(dot(n, v), 0.0), roughness)
         * geometrySchlickGgx(max(dot(n, l), 0.0), roughness);
}

vec3 fresnelSchlick(float cosTheta, vec3 f0) {
    return f0 + (1.0 - f0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 fresnelSchlickRoughness(float cosTheta, vec3 f0, float roughness) {
    return f0 + (max(vec3(1.0 - roughness), f0) - f0)
            * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec2 receiverPlaneGradient(vec3 projected) {
    vec3 dx = dFdx(projected), dy = dFdy(projected);
    float determinant = dx.x * dy.y - dx.y * dy.x;
    if (abs(determinant) < 1e-10) return vec2(0.0);
    return vec2(dx.z * dy.y - dy.z * dx.y, dy.z * dx.x - dx.z * dy.x) / determinant;
}

int directionalCascadeIndex() {
    int count = max(uDirectionalCascadeCount, 1);
    for (int index = 0; index < count; index++) {
        if (vViewDepth <= uDirectionalCascadeSplits[index]) return index;
    }
    return count - 1;
}

mat4 directionalCascadeMatrix(int cascade) {
    return uDirectionalCascadeCount > 1
            ? uDirectionalCascadeMatrices[cascade] : uDirectionalLightSpace;
}

void directionalCascadeTile(int cascade, float tapRadiusTexels,
                            out vec2 scale, out vec2 offset,
                            out vec2 tileMinimum, out vec2 tileMaximum) {
    int columns = uDirectionalCascadeCount == 2 || uDirectionalCascadeCount > 2 ? 2 : 1;
    int rows = uDirectionalCascadeCount > 2 ? 2 : 1;
    scale = vec2(1.0 / float(columns), 1.0 / float(rows));
    offset = vec2(float(cascade % columns), float(cascade / columns)) * scale;
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    vec2 guard = texel * (tapRadiusTexels + 0.5);
    tileMinimum = offset + guard;
    tileMaximum = offset + scale - guard;
}

float sampleDirectionalCascade(int cascade, vec3 n, vec3 l) {
    vec3 samplePosition = vWorldPosition + n * shadowNormalBias();
    vec4 lightPosition = directionalCascadeMatrix(cascade) * vec4(samplePosition, 1.0);
    vec3 projected = lightPosition.xyz / lightPosition.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0) return 0.0;
    vec2 scale;
    vec2 offset;
    vec2 tileMinimum;
    vec2 tileMaximum;
    int radius = shadowKernelRadius();
    directionalCascadeTile(cascade, float(radius), scale, offset, tileMinimum, tileMaximum);
    projected.xy = projected.xy * scale + offset;
    if (any(lessThanEqual(projected.xy, tileMinimum))
            || any(greaterThanEqual(projected.xy, tileMaximum))) return 0.0;
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    vec2 gradient = receiverPlaneGradient(projected);
    float bias = shadowDepthBias(uShadowBias, n, l);
    bias += min(dot(abs(gradient), texel) * 0.5, 0.01);
    float shadow = 0.0;
    for (int x = -2; x <= 2; ++x) for (int y = -2; y <= 2; ++y) {
        if (abs(x) > radius || abs(y) > radius) continue;
        vec2 sampleUv = clamp(projected.xy + vec2(x, y) * texel,
                tileMinimum, tileMaximum);
        float closest = texture(uShadowMap, sampleUv).r;
        shadow += projected.z + dot(gradient, sampleUv - projected.xy) - bias > closest ? 1.0 : 0.0;
    }
    float width = float(radius * 2 + 1);
    return shadow / (width * width);
}

/**
 * Deterministic golden-angle disk tap shared by the PCSS blocker search and the
 * variable-radius filter.  The count and radius are bounded by PcssSettings and
 * the compile-time PCSS_MAX_* limits, so the loop cost is fixed per pixel.
 */
vec2 pcssDiskOffset(int index, int count, float radiusTexels) {
    float ratio = (float(index) + 0.5) / float(count);
    float radius = sqrt(ratio) * radiusTexels;
    float angle = float(index) * 2.39996323;
    return vec2(cos(angle), sin(angle)) * radius;
}

/**
 * PCSS penumbra radius in shadow texels.
 *
 * <p>Directional cascades use an orthographic projection, so the normalized
 * window depth is linear in the distance from the light plane:
 * {@code world = near + depth * (far - near)}.  The cascade matrix's m22/m32
 * recover both constants; {@code uDirectionalCascadeTexelWorld} is the same
 * world-texel size the shadow planner uses for culling.  The result is clamped
 * by the caller to the configured filter radius.</p>
 */
float pcssPenumbraTexels(int cascade, float receiverDepth, float blockerDepth) {
    mat4 lightSpace = directionalCascadeMatrix(cascade);
    float depthRange = -2.0 / lightSpace[2][2];
    float nearDistance = (lightSpace[3][2] + 1.0) / lightSpace[2][2];
    float texelWorld = uDirectionalCascadeTexelWorld[cascade];
    if (!(depthRange > 0.0) || !(texelWorld > 0.0)) return 0.0;
    float receiverWorld = nearDistance + receiverDepth * depthRange;
    float blockerWorld = nearDistance + blockerDepth * depthRange;
    float occluderDistance = max(blockerWorld, 1.0e-4);
    float penumbraWorld = max(receiverWorld - blockerWorld, 0.0)
            * uPcssLightSize / occluderDistance;
    return penumbraWorld / max(texelWorld, 1.0e-6);
}

/**
 * Bounded PCSS replacement for one directional cascade.
 *
 * <p>Depths are normalized light-space window depths in {@code [0, 1]}, exactly
 * like the existing hard/PCF comparison; {@code uPcssDepthBias} shares those
 * units and is added to the existing slope-corrected {@code uShadowBias}.
 * {@code uPcssLightSize} is a world-space diameter.  Every blocker and filter
 * tap is clamped to the selected cascade's guarded tile, so no sample can reach
 * a neighbouring atlas tile.  Returns 0 (fully lit) when there is no blocker or
 * when the receiver is outside the cascade tile.</p>
 */
float sampleDirectionalCascadePcss(int cascade, vec3 n, vec3 l) {
    vec3 samplePosition = vWorldPosition + n * shadowNormalBias();
    vec4 lightPosition = directionalCascadeMatrix(cascade) * vec4(samplePosition, 1.0);
    vec3 projected = lightPosition.xyz / lightPosition.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0) return 0.0;
    int blockerRadius = clamp(uPcssBlockerRadiusTexels, 1, PCSS_MAX_BLOCKER_RADIUS_TEXELS);
    int filterRadius = clamp(uPcssFilterRadiusTexels, 1, PCSS_MAX_FILTER_RADIUS_TEXELS);
    float searchRadius = float(max(blockerRadius, filterRadius));
    vec2 scale;
    vec2 offset;
    vec2 tileMinimum;
    vec2 tileMaximum;
    directionalCascadeTile(cascade, searchRadius, scale, offset, tileMinimum, tileMaximum);
    projected.xy = projected.xy * scale + offset;
    if (any(lessThanEqual(projected.xy, tileMinimum))
            || any(greaterThanEqual(projected.xy, tileMaximum))) return 0.0;
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    vec2 gradient = receiverPlaneGradient(projected);
    float bias = shadowDepthBias(uShadowBias, n, l)
            + min(dot(abs(gradient), texel) * 0.5, 0.01) + uPcssDepthBias;
    int blockerTaps = clamp(uPcssBlockerTaps, 1, PCSS_MAX_BLOCKER_TAPS);
    float blockerSum = 0.0;
    int blockerCount = 0;
    for (int tap = 0; tap < PCSS_MAX_BLOCKER_TAPS; ++tap) {
        if (tap >= blockerTaps) break;
        vec2 sampleUv = clamp(projected.xy
                + pcssDiskOffset(tap, blockerTaps, float(blockerRadius)) * texel,
                tileMinimum, tileMaximum);
        float receiverDepth = projected.z + dot(gradient, sampleUv - projected.xy);
        float stored = texture(uShadowMap, sampleUv).r;
        if (stored < receiverDepth - bias) {
            blockerSum += stored;
            blockerCount++;
        }
    }
    if (blockerCount == 0) return 0.0;
    float blockerDepth = blockerSum / float(blockerCount);
    float radius = min(pcssPenumbraTexels(cascade, projected.z, blockerDepth),
            float(filterRadius));
    int filterTaps = clamp(uPcssFilterTaps, 1, PCSS_MAX_FILTER_TAPS);
    float shadow = 0.0;
    for (int tap = 0; tap < PCSS_MAX_FILTER_TAPS; ++tap) {
        if (tap >= filterTaps) break;
        vec2 sampleUv = clamp(projected.xy + pcssDiskOffset(tap, filterTaps, radius) * texel,
                tileMinimum, tileMaximum);
        float receiverDepth = projected.z + dot(gradient, sampleUv - projected.xy);
        float stored = texture(uShadowMap, sampleUv).r;
        shadow += stored < receiverDepth - bias ? 1.0 : 0.0;
    }
    return shadow / float(filterTaps);
}

/**
 * Selects the existing hard/PCF path when PCSS is disabled, so the disabled
 * default is an exact no-op and keeps its original sample sequence.
 */
float sampleDirectionalShadow(int cascade, vec3 n, vec3 l) {
    if (uPcssEnabled == 0) return sampleDirectionalCascade(cascade, n, l);
    return sampleDirectionalCascadePcss(cascade, n, l);
}

float directionalShadowFactor(vec3 n, vec3 l) {
    if (uHasDirectionalShadow == 0) return 0.0;
    int count = max(uDirectionalCascadeCount, 1);
    int cascade = directionalCascadeIndex();
    float current = sampleDirectionalShadow(cascade, n, l);
    if (cascade >= count - 1 || uDirectionalCascadeBlendRange <= 0.0) return current;
    float nearSplit = cascade == 0 ? 0.0 : uDirectionalCascadeSplits[cascade - 1];
    float blendWidth = max((uDirectionalCascadeSplits[cascade] - nearSplit)
            * uDirectionalCascadeBlendRange, 1.0e-4);
    float blendStart = uDirectionalCascadeSplits[cascade] - blendWidth;
    float weight = smoothstep(blendStart, uDirectionalCascadeSplits[cascade], vViewDepth);
    if (weight <= 1.0e-4) return current;
    float next = sampleDirectionalShadow(cascade + 1, n, l);
    return weight >= 0.9999 ? next : mix(current, next, weight);
}

/**
 * Refines the selected cascade with a bounded short-range march toward the
 * light.  The receiver must be inside the cascade tile and the camera's stable
 * view-projection; edge/off-screen receivers fall back to the cascaded result.
 * The caller scales the result by the cascade's unshadowed visibility so a
 * surface already darkened by the cascade is never darkened twice.
 */
float directionalContactShadow(vec3 n, vec3 l) {
    if (uContactShadowEnabled == 0 || uHasDirectionalShadow == 0
            || uContactShadowStrength <= 0.0 || dot(n, l) <= 0.0) return 0.0;
    int cascade = directionalCascadeIndex();
    mat4 lightSpace = directionalCascadeMatrix(cascade);
    vec3 normalOffset = n * shadowNormalBias();
    vec4 receiverClip = lightSpace * vec4(vWorldPosition + normalOffset, 1.0);
    if (receiverClip.w <= 0.0) return 0.0;
    vec3 receiver = receiverClip.xyz / receiverClip.w * 0.5 + 0.5;
    if (receiver.z <= 0.0 || receiver.z >= 1.0) return 0.0;
    vec2 scale;
    vec2 offset;
    vec2 tileMinimum;
    vec2 tileMaximum;
    directionalCascadeTile(cascade, float(shadowKernelRadius()), scale, offset,
            tileMinimum, tileMaximum);
    vec2 receiverUv = receiver.xy * scale + offset;
    if (any(lessThanEqual(receiverUv, tileMinimum))
            || any(greaterThanEqual(receiverUv, tileMaximum))) return 0.0;

    vec4 screenClip = uStableViewProjection * vec4(vWorldPosition, 1.0);
    if (screenClip.w <= 0.0) return 0.0;
    vec2 screenUv = screenClip.xy / screenClip.w * 0.5 + 0.5;
    float edge = min(min(screenUv.x, 1.0 - screenUv.x),
                     min(screenUv.y, 1.0 - screenUv.y));
    float edgeFade = smoothstep(0.0, 0.05, edge);
    if (edgeFade <= 0.0) return 0.0;

    int steps = clamp(uContactShadowSteps, 1, CONTACT_SHADOW_MAX_STEPS);
    float distance = max(uContactShadowDistance, 0.0);
    float depthPerWorld = abs(lightSpace[2][2]) * 0.5;
    if (!(depthPerWorld > 0.0)) return 0.0;
    float thickness = clamp(uContactShadowThickness, 0.0, distance);
    float startOffset = max(thickness * 0.1, 0.001);
    float bias = depthPerWorld * startOffset * 0.5 + 1.0e-5;
    float thicknessDepth = max(thickness * depthPerWorld, 2.0 * bias);
    vec3 origin = vWorldPosition + normalOffset + l * startOffset;
    float occlusion = 0.0;
    for (int step = 1; step <= CONTACT_SHADOW_MAX_STEPS; step++) {
        if (step > steps) break;
        float along = float(step) / float(steps);
        vec4 sampleClip = lightSpace * vec4(origin + l * (distance * along), 1.0);
        if (sampleClip.w <= 0.0) continue;
        vec3 projected = sampleClip.xyz / sampleClip.w * 0.5 + 0.5;
        if (projected.z <= 0.0 || projected.z >= 1.0) continue;
        vec2 sampleUv = projected.xy * scale + offset;
        if (any(lessThanEqual(sampleUv, tileMinimum))
                || any(greaterThanEqual(sampleUv, tileMaximum))) continue;
        float separation = projected.z - texture(uShadowMap, sampleUv).r;
        if (separation > bias && separation <= bias + thicknessDepth) {
            occlusion = 1.0 - 0.65 * along;
            break;
        }
    }
    return occlusion * edgeFade * clamp(uContactShadowStrength, 0.0, 1.0);
}

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

/**
 * World-space cloud visibility for the selected directional light.
 *
 * <p>The receiver is projected along the light direction onto the plane through
 * the world origin perpendicular to that direction; the returned coordinates
 * depend on vWorldPosition only, never on the camera or screen position.  A
 * fixed world-space wind offset scrolls the procedural layer by time.  The
 * early gate keeps the disabled/zero-strength path an exact no-op.</p>
 */
float cloudShadowVisibility(vec3 n, vec3 l) {
    if (uCloudShadowEnabled == 0 || uCloudShadowStrength <= 0.0 || dot(n, l) <= 0.0) {
        return 1.0;
    }
    vec3 reference = abs(l.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(reference, l));
    vec3 bitangent = cross(l, tangent);
    vec2 projected = vec2(dot(vWorldPosition, tangent), dot(vWorldPosition, bitangent));
    projected += vec2(0.8, 0.6) * (uCloudShadowTime * max(uCloudShadowSpeed, 0.0));
    float scale = max(uCloudShadowScale, 1.0e-4);
    float noise = cloudShadowDensity(projected * scale);
    float coverage = clamp(uCloudShadowCoverage, 0.0, 1.0);
    float threshold = mix(0.78, 0.22, coverage);
    float edge = mix(0.004, 0.28, clamp(uCloudShadowSoftness, 0.0, 1.0));
    float shadow = smoothstep(threshold - edge, threshold + edge, noise);
    return 1.0 - clamp(uCloudShadowStrength, 0.0, 1.0) * shadow;
}

int pointShadowFace(vec3 direction) {
    vec3 absoluteDirection = abs(direction);
    if (absoluteDirection.x >= absoluteDirection.y
            && absoluteDirection.x >= absoluteDirection.z) {
        return direction.x >= 0.0 ? 0 : 1;
    }
    if (absoluteDirection.y >= absoluteDirection.z) {
        return direction.y >= 0.0 ? 2 : 3;
    }
    return direction.z >= 0.0 ? 4 : 5;
}

float pointShadowFactor(int slot, vec3 lightPosition, vec3 n, vec3 l) {
    if (uHasPointShadow == 0 || uPointShadowMeta[slot].y == 0) return 0.0;
    vec3 fromLight = vWorldPosition - lightPosition;
    int face = pointShadowFace(fromLight);
    vec3 samplePosition = vWorldPosition + n * shadowNormalBias();
    int matrixIndex = slot * POINT_SHADOW_FACE_COUNT + face;
    vec4 clip = uPointSlotMatrices[matrixIndex] * vec4(samplePosition, 1.0);
    vec3 projected = clip.xyz / clip.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || any(lessThanEqual(projected.xy, vec2(0.0)))
            || any(greaterThanEqual(projected.xy, vec2(1.0)))) return 0.0;
    vec4 rect = uPointFaceRects[matrixIndex];
    vec2 atlasUv = mix(rect.xy, rect.zw, projected.xy);
    vec2 atlasTexel = 1.0 / vec2(textureSize(uPointShadowMap, 0));
    float bias = shadowDepthBias(intBitsToFloat(uShadowQualityMeta.z), n, l);
    int radius = shadowKernelRadius();
    vec2 guard = atlasTexel * (float(radius) + 0.5);
    vec2 tileMinimum = rect.xy + guard;
    vec2 tileMaximum = rect.zw - guard;
    float shadow = 0.0;
    for (int x = -2; x <= 2; ++x) for (int y = -2; y <= 2; ++y) {
        if (abs(x) > radius || abs(y) > radius) continue;
        vec2 sampleUv = clamp(atlasUv + vec2(x, y) * atlasTexel,
                tileMinimum, tileMaximum);
        shadow += projected.z - bias > texture(uPointShadowMap, sampleUv).r ? 1.0 : 0.0;
    }
    float width = float(radius * 2 + 1);
    return shadow / (width * width);
}

float spotShadowFactor(int slot, vec3 n, vec3 l) {
    if (uHasSpotShadow == 0 || uSpotShadowMeta[slot].y == 0) return 0.0;
    vec3 samplePosition = vWorldPosition + n * shadowNormalBias();
    vec4 clip = uSpotSlotMatrices[slot] * vec4(samplePosition, 1.0);
    vec3 projected = clip.xyz / clip.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || any(lessThanEqual(projected.xy, vec2(0.0)))
            || any(greaterThanEqual(projected.xy, vec2(1.0)))) return 0.0;
    float bias = shadowDepthBias(intBitsToFloat(uShadowQualityMeta.w), n, l);
    vec2 texel = 1.0 / vec2(textureSize(uSpotShadowMap, 0));
    vec4 rect = uSpotTileRects[slot];
    vec2 atlasUv = mix(rect.xy, rect.zw, projected.xy);
    int radius = shadowKernelRadius();
    vec2 guard = texel * (float(radius) + 0.5);
    vec2 tileMinimum = rect.xy + guard;
    vec2 tileMaximum = rect.zw - guard;
    float shadow = 0.0;
    for (int x = -2; x <= 2; ++x) for (int y = -2; y <= 2; ++y) {
        if (abs(x) > radius || abs(y) > radius) continue;
        shadow += projected.z - bias
                > texture(uSpotShadowMap, clamp(atlasUv + vec2(x, y) * texel,
                    tileMinimum, tileMaximum)).r ? 1.0 : 0.0;
    }
    float width = float(radius * 2 + 1);
    return shadow / (width * width);
}

vec3 directBrdf(vec3 n, vec3 v, vec3 l, vec3 radiance,
                vec3 baseColor, float metallic, float roughness, vec3 f0) {
    float nDotL = max(dot(n, l), 0.0);
    if (nDotL <= 0.0) return vec3(0.0);
    vec3 h = normalize(v + l);
    vec3 f = fresnelSchlick(max(dot(h, v), 0.0), f0);
    float d = distributionGgx(n, h, roughness);
    float g = geometrySmith(n, v, l, roughness);
    vec3 specular = d * g * f / max(4.0 * max(dot(n, v), 0.0) * nDotL, 1.0e-5);
    vec3 kd = (1.0 - f) * (1.0 - metallic);
    return (kd * baseColor / PI + specular) * radiance * nDotL;
}

float rangeInverseSquareAttenuation(float distanceToLight, float range) {
    float rangeWindow = clamp(1.0 - distanceToLight / max(range, 1.0e-4), 0.0, 1.0);
    return (rangeWindow * rangeWindow) / max(distanceToLight * distanceToLight, 1.0e-4);
}

vec3 directionalRadiance(int index, vec3 n, vec3 v, vec3 baseColor,
                         float metallic, float roughness, vec3 f0) {
    LightRecord light = uLights[index];
    vec3 l = normalize(-light.directionOuter.xyz);
    float visibility = 1.0;
    if (index == uDirectionalShadowFrameLightIndex) {
        visibility = 1.0 - directionalShadowFactor(n, l);
        if (visibility > 0.0) {
            float contact = directionalContactShadow(n, l) * visibility;
            visibility = max(visibility - contact, 0.0);
        }
        // Only the selected directional light consumes the cloud mask.  The
        // factor is exact 1.0 when disabled or at zero strength, so emissive,
        // IBL and other lights are untouched.
        visibility *= cloudShadowVisibility(n, l);
    }
    return visibility * directBrdf(n, v, l, light.colorIntensity.rgb * light.colorIntensity.a,
            baseColor, metallic, roughness, f0);
}

vec3 localRadiance(int index, vec3 n, vec3 v, vec3 baseColor,
                   float metallic, float roughness, vec3 f0) {
    LightRecord light = uLights[index];
    vec3 lightPosition = light.positionRange.xyz;
    vec3 delta = lightPosition - vWorldPosition;
    float distanceToLight = length(delta);
    vec3 l = delta / max(distanceToLight, 1.0e-5);
    float attenuation = rangeInverseSquareAttenuation(distanceToLight,
            light.positionRange.w);
    float cone = 1.0;
    int type = light.metadata.x;
    if (type == 2) {
        float angle = acos(clamp(dot(-l, normalize(light.directionOuter.xyz)), -1.0, 1.0));
        cone = 1.0 - smoothstep(light.extra.x, light.directionOuter.w, angle);
    }
    int slot = light.metadata.y;
    float visibility = 1.0;
    if (slot >= 0) {
        visibility = 1.0 - (type == 1
                ? pointShadowFactor(slot, lightPosition, n, l)
                : spotShadowFactor(slot, n, l));
    }
    return visibility * directBrdf(n, v, l,
            light.colorIntensity.rgb * light.colorIntensity.a * attenuation * cone,
            baseColor, metallic, roughness, f0);
}

int resolveCluster() {
    vec4 clip = uStableViewProjection * vec4(vWorldPosition, 1.0);
    if (clip.w <= 0.0) return -1;
    float viewZ = (uStableView * vec4(vWorldPosition, 1.0)).z;
    float depth = -viewZ;
    if (depth <= 0.0) return -1;
    int z;
    if (uDepthParams.z > 0.5) {
        z = int(floor(log(depth / uDepthParams.x) * float(uGridParams.z)
                / log(uDepthParams.y / uDepthParams.x)));
    } else {
        z = int(floor((depth - uDepthParams.x) / (uDepthParams.y - uDepthParams.x)
                * float(uGridParams.z)));
    }
    z = clamp(z, 0, uGridParams.z - 1);
    vec2 uv = clamp(clip.xy / clip.w * 0.5 + 0.5, 0.0, 1.0);
    int tileX = clamp(int(floor(uv.x * float(uGridParams.x))), 0, uGridParams.x - 1);
    int tileY = clamp(int(floor(uv.y * float(uGridParams.y))), 0, uGridParams.y - 1);
    return tileX + uGridParams.x * (tileY + uGridParams.y * z);
}

vec3 clusterDebugColor(int cluster) {
    if (cluster < 0) return vec3(0.08, 0.08, 0.10);
    uvec4 header = uClusterHeaders[cluster];
    if (uClusterDebugMode == 1) {
        uint hashed = uint(cluster) * 2654435761u;
        return vec3(float(hashed & 255u), float((hashed >> 8) & 255u),
                float((hashed >> 16) & 255u)) / 255.0;
    }
    if (uClusterDebugMode == 2) {
        int z = cluster / (uGridParams.x * uGridParams.y);
        float t = float(z) / max(float(uGridParams.z - 1), 1.0);
        return vec3(t, 1.0 - t, 0.15);
    }
    if (uClusterDebugMode == 3) {
        float t = float(header.y) / max(float(uGridParams.w), 1.0);
        return vec3(t, 1.0 - t, 0.0);
    }
    if (uClusterDebugMode == 4) {
        return header.z != 0u ? vec3(1.0, 0.05, 0.05) : vec3(0.05, 0.6, 0.1);
    }
    if (uClusterDebugMode == 5) {
        for (uint k = 0u; k < header.y; k++) {
            LightRecord light = uLights[uClusterIndices[header.x + k]];
            if (light.metadata.y >= 0) {
                float t = float(light.metadata.y) / 4.0;
                return vec3(1.0 - t, 0.2, t);
            }
        }
        return vec3(0.25, 0.25, 0.28);
    }
    return vec3(1.0);
}

void main() {
    vec4 vertexColor = uHasVertexColor != 0 ? vVertexColor : vec4(1.0);
    vec4 baseSample = texture(uBaseColorMap, vTexCoord) * uBaseColorFactor * vertexColor;
    if (uAlphaMode == 1 && baseSample.a < uAlphaCutoff) discard;
    vec2 mr = texture(uMetallicRoughnessMap, vTexCoord).gb;
    float roughness = clamp(mr.x * uRoughnessFactor, 0.045, 1.0);
    float metallic = clamp(mr.y * uMetallicFactor, 0.0, 1.0);
    vec3 n = normalize(vNormal);
    if (uDoubleSided != 0 && !gl_FrontFacing) n = -n;
    if (uEnableNormalMap != 0) {
        vec3 sampled = texture(uNormalMap, vTexCoord).xyz * 2.0 - 1.0;
        sampled.xy *= uNormalScale;
        vec3 t = normalize(vTangent);
        vec3 b = normalize(cross(n, t)) * vTangentHandedness;
        n = normalize(mat3(t, b, n) * sampled);
    }
    vec3 v = normalize(uCameraPosition - vWorldPosition);
    vec3 f0 = mix(vec3(0.04), baseSample.rgb, metallic);
    if (uClusterDebugMode != 0) {
        uint localLightCount = uLightHeader.y;
        int debugCluster = localLightCount > 0u ? resolveCluster() : -1;
        FragColor = vec4(clusterDebugColor(debugCluster), 1.0);
        return;
    }
    vec3 direct = vec3(0.0);
    if (uEnableDirect != 0) {
        uint directionalCount = uLightHeader.x;
        uint localCount = uLightHeader.y;
        for (uint i = 0u; i < directionalCount; i++) {
            direct += directionalRadiance(int(i), n, v, baseSample.rgb,
                    metallic, roughness, f0);
        }
        int cluster = localCount > 0u ? resolveCluster() : -1;
        if (cluster >= 0) {
            uvec4 header = uClusterHeaders[cluster];
            if (header.z != 0u) {
                // Overflow: scan the complete local range with the same
                // evaluator so no light is lost, however expensive.
                for (uint i = 0u; i < localCount; i++) {
                    direct += localRadiance(int(directionalCount + i), n, v, baseSample.rgb,
                            metallic, roughness, f0);
                }
            } else {
                uint count = header.y;
                for (uint k = 0u; k < count; k++) {
                    direct += localRadiance(int(uClusterIndices[header.x + k]), n, v,
                            baseSample.rgb, metallic, roughness, f0);
                }
            }
        }
    }

    float nDotV = max(dot(n, v), 0.0);
    vec3 f = fresnelSchlickRoughness(nDotV, f0, roughness);
    vec3 kd = (1.0 - f) * (1.0 - metallic);
    vec3 rotatedN = rotateEnvironment(n);
    // irradiance.comp stores PI * average(cosine-weighted radiance), so the
    // Lambert BRDF must divide by PI exactly once here.
    vec3 diffuseIbl = texture(uIrradianceMap, rotatedN).rgb * baseSample.rgb / PI;
    vec3 reflection = rotateEnvironment(reflect(-v, n));
    vec3 prefiltered = textureLod(uPrefilteredMap, reflection,
            roughness * uPrefilterMaxLod).rgb;
    vec2 brdf = texture(uBrdfLut, vec2(nDotV, roughness)).rg;
    vec3 specularIbl = prefiltered * (f * brdf.x + brdf.y);
    float aoSample = texture(uOcclusionMap, vTexCoord).r;
    float ao = mix(1.0, aoSample, uOcclusionStrength);
    vec3 indirect = vec3(0.0);
    if (uEnableDiffuseIbl != 0) indirect += kd * diffuseIbl;
    if (uEnableSpecularIbl != 0) indirect += specularIbl;
    float gtao = 1.0;
    if (uGtaoEnabled != 0 && uGtaoMaterialOptOut == 0) {
        gtao = texture(uGtaoMap, gl_FragCoord.xy / vec2(textureSize(uGtaoMap, 0))).r;
    }
    if (uGtaoPreview != 0) {
        FragColor = vec4(vec3(gtao), 1.0);
        return;
    }
    indirect *= ao * gtao * uEnvironmentIntensity;
    vec3 emissive = texture(uEmissiveMap, vTexCoord).rgb * uEmissiveFactor;
    FragColor = vec4(max(direct + indirect + emissive, vec3(0.0)),
            uAlphaMode == 2 ? baseSample.a : 1.0);
#ifdef HAIKALAT_VOLUME_FOG
    FragColor = volumeFogSurface(FragColor, vWorldPosition, uVolumeAdditive != 0);
#endif
}
