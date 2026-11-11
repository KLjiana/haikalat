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
uniform sampler2D uClearcoatNormalMap;
// Linear scalar height data for tangent-space parallax occlusion mapping.
uniform sampler2D uHeightMap;
#ifndef HAIKALAT_VOLUME_FOG
uniform sampler2D uShadowMap;
uniform sampler2D uPointShadowMap;
uniform sampler2D uSpotShadowMap;
#endif
uniform samplerCube uIrradianceMap;
uniform samplerCube uPrefilteredMap;
uniform sampler2D uBrdfLut;
uniform sampler2D uGtaoMap;
// Bounded local reflection probes. uReflectionProbeCount is zero by default;
// only then are the probe cubemaps and their per-probe parameters consumed.
// Slot 0 and 1 share the fixed fragment texture-unit contract in PbrMaterials.
uniform int uReflectionProbeCount;
uniform float uReflectionProbeGlobalWeight;
uniform samplerCube uReflectionProbe0;
uniform vec3 uReflectionProbe0Position;
uniform vec3 uReflectionProbe0Extents;
uniform int uReflectionProbe0Shape; // 0 box, 1 sphere
uniform float uReflectionProbe0Weight;
uniform float uReflectionProbe0Intensity;
uniform float uReflectionProbe0Rotation;
uniform float uReflectionProbe0MaxLod;
uniform samplerCube uReflectionProbe1;
uniform vec3 uReflectionProbe1Position;
uniform vec3 uReflectionProbe1Extents;
uniform int uReflectionProbe1Shape;
uniform float uReflectionProbe1Weight;
uniform float uReflectionProbe1Intensity;
uniform float uReflectionProbe1Rotation;
uniform float uReflectionProbe1MaxLod;

uniform vec4 uBaseColorFactor;
uniform float uMetallicFactor;
uniform float uRoughnessFactor;
uniform float uNormalScale;
uniform float uOcclusionStrength;
uniform vec3 uEmissiveFactor;
// Thin-surface controls. Zero keeps the original metallic-roughness output.
uniform float uThicknessFactor;
uniform float uTransmissionFactor;
uniform float uBacklightFactor;
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
// Bounded clearcoat overlay; zero is the backwards-compatible exact no-op.
uniform int uClearcoatEnabled;
uniform float uClearcoatRoughness;
uniform float uClearcoatNormalStrength;
// Bounded anisotropic base-specular input; zero strength is the exact isotropic path.
uniform int uAnisotropicEnabled;
uniform float uAnisotropicStrength;
uniform float uAnisotropicRotation;
// Bounded tangent-space parallax occlusion; zero height scale is the exact original-UV path.
uniform int uParallaxEnabled;
uniform float uParallaxHeightScale;
uniform int uParallaxMinSteps;
uniform int uParallaxMaxSteps;
uniform float uParallaxFadeStart;
uniform float uParallaxFadeEnd;
uniform float uParallaxFadeStartDistance;
uniform float uParallaxMaxDistance;
uniform int uParallaxHeightChannel;
uniform int uParallaxFlipHeight;
// Bounded subsurface scattering; zero is the backwards-compatible exact no-op.
uniform int uSubsurfaceEnabled;
uniform vec3 uSubsurfaceScatterColor;
uniform float uSubsurfaceIntensity;
uniform int uSubsurfaceProfile;          // 0 uniform, 1 skin-like channel penetration
uniform float uSubsurfaceProfileScale;   // mean free path in world units (metres)
uniform int uSubsurfaceThicknessSource;  // 0 constant, 1 occlusion proxy, 2 base-alpha proxy
uniform float uSubsurfaceThickness;      // metres; constant thickness or derived-thickness scale
// Screen-space radius/participation/distance are consumed by the shared
// scene-surface mask producer and SubsurfaceScatteringPass, not by this shader.
uniform int uGtaoEnabled;
// Zero is the backwards-compatible default; demo/host materials can opt out.
uniform int uGtaoMaterialOptOut;
// Debug-only material switch used by the GTAO contact acceptance scene.
uniform int uGtaoPreview;

const float PI = 3.14159265358979323846;
// Clearcoat interface reflectance at normal incidence (glTF clearcoat value).
const float CLEARCOAT_F0 = 0.04;
// Same safe GGX floor as the base layer.
const float CLEARCOAT_MIN_ROUGHNESS = 0.045;
// Per-axis GGX alpha floor: the square of the base 0.045 roughness floor.
const float ANISOTROPIC_MIN_ALPHA = 0.002025;
// Compile-time cap of the parallax march; the uniform step count is clamped to it.
const int PARALLAX_MAX_STEPS = 128;
// Longest tangent-space lateral slope the march may traverse (bounded UV travel).
const float PARALLAX_MAX_SLOPE = 8.0;
// Smallest tangent-space view depth that still participates in the march.
const float PARALLAX_MIN_VIEW_Z = 1.0e-4;
// Subsurface-scattering bounds; PbrMaterials/SubsurfaceScatteringSettings validate
// the authored values against the same numbers before they reach the GPU.
const float SUBSURFACE_MIN_PROFILE_SCALE = 0.001;
const float SUBSURFACE_MAX_PROFILE_SCALE = 10.0;
const float SUBSURFACE_MAX_THICKNESS = 2.0;
const float SUBSURFACE_MAX_INTENSITY = 4.0;
// Red survives the longest in the skin profile; green and blue are absorbed
// faster in the same thickness. Wax uses the uniform (1, 1, 1) profile.
const vec3 SUBSURFACE_SKIN_PENETRATION = vec3(1.0, 0.55, 0.30);
// Thickness is resolved once per fragment before the light loops and is read
// by the per-light transmission term. It is a shader-invocation global so the
// direct-light helper signatures (and their existing tests) stay unchanged.
float gSubsurfaceThickness;

int shadowKernelRadius() {
    return clamp(uShadowQualityMeta.x, 0, 2);
}

float shadowNormalBias() {
    return intBitsToFloat(uShadowQualityMeta.y);
}

float shadowDepthBias(float baseBias, vec3 n, vec3 l) {
    return max(baseBias * (1.0 - dot(n, l)), baseBias * 0.25);
}

vec3 rotateEnvironmentBy(vec3 direction, float rotation) {
    float c = cos(rotation);
    float s = sin(rotation);
    return vec3(c * direction.x - s * direction.z, direction.y,
                s * direction.x + c * direction.z);
}

vec3 rotateEnvironment(vec3 direction) {
    return rotateEnvironmentBy(direction, uEnvironmentRotation);
}

/**
 * Local-corrected cubemap direction for one static reflection probe.
 *
 * <p>The probe cubemap was prefiltered around its own capture position, so an
 * uncorrected lookup at an arbitrary world point shows the environment at
 * infinity. This helper intersects the reflected ray with the probe's box (or
 * sphere) influence volume and returns the direction from the probe centre to
 * that hit. The result is still only a bounded local approximation: the
 * influence volume is not a captured depth hull, it has no occlusion, and a
 * ray that misses the volume falls back to the unmodified reflection.</p>
 */
vec3 reflectionProbeSampleDirection(vec3 worldPosition, vec3 reflection,
                                    vec3 probePosition, vec3 probeExtents, int shape) {
    if (shape == 1) {
        vec3 offset = worldPosition - probePosition;
        float radius = max(probeExtents.x, 1.0e-5);
        float b = dot(offset, reflection);
        float c = dot(offset, offset) - radius * radius;
        float discriminant = b * b - c;
        if (discriminant >= 0.0) {
            float root = sqrt(discriminant);
            float nearDistance = -b - root;
            float farDistance = -b + root;
            float distance = nearDistance > 1.0e-5 ? nearDistance : farDistance;
            if (distance > 1.0e-5) {
                return normalize(worldPosition + reflection * distance - probePosition);
            }
        }
        return reflection;
    }
    vec3 minimum = probePosition - probeExtents;
    vec3 maximum = probePosition + probeExtents;
    vec3 safeDirection = vec3(
            abs(reflection.x) < 1.0e-6 ? (reflection.x < 0.0 ? -1.0e-6 : 1.0e-6) : reflection.x,
            abs(reflection.y) < 1.0e-6 ? (reflection.y < 0.0 ? -1.0e-6 : 1.0e-6) : reflection.y,
            abs(reflection.z) < 1.0e-6 ? (reflection.z < 0.0 ? -1.0e-6 : 1.0e-6) : reflection.z);
    vec3 inverse = 1.0 / safeDirection;
    vec3 first = (minimum - worldPosition) * inverse;
    vec3 second = (maximum - worldPosition) * inverse;
    vec3 nearVector = min(first, second);
    vec3 farVector = max(first, second);
    float nearDistance = max(max(nearVector.x, nearVector.y), nearVector.z);
    float farDistance = min(min(farVector.x, farVector.y), farVector.z);
    if (farDistance >= 0.0 && nearDistance <= farDistance) {
        float distance = nearDistance > 1.0e-5 ? nearDistance : farDistance;
        if (distance > 1.0e-5) {
            return normalize(worldPosition + reflection * distance - probePosition);
        }
    }
    return reflection;
}

/**
 * One probe's prefiltered specular radiance. The probe's own rotation is
 * applied after local correction, and the prefiltered mip is
 * {@code roughness * maximumLod}: PbrEnvironment stores roughness
 * {@code mip / (mipLevels - 1)}, exactly like the global environment.
 */
vec3 reflectionProbeRadiance(samplerCube probe, vec3 worldPosition, vec3 reflection,
                             vec3 probePosition, vec3 probeExtents, int shape,
                             float rotation, float maximumLod, float intensity,
                             float roughness) {
    vec3 direction = reflectionProbeSampleDirection(worldPosition, reflection,
            probePosition, probeExtents, shape);
    direction = rotateEnvironmentBy(direction, rotation);
    float maxLod = max(maximumLod, 0.0);
    float lod = clamp(roughness * maxLod, 0.0, maxLod);
    return textureLod(probe, direction, lod).rgb * max(intensity, 0.0);
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

/**
 * Deterministic tangent axis used when the mesh has no usable TANGENT.
 *
 * <p>Same construction as the CPU tangent generator's degenerate fallback:
 * pick a reference axis that cannot be parallel to the normal, then take its
 * cross product with the normal. Comparisons are false for NaN, so a tangent
 * attribute that normalized to NaN (or a tangent parallel to the normal) is
 * routed here through the caller's {@code !(dot > epsilon)} checks.</p>
 */
vec3 fallbackAnisotropicTangent(vec3 n) {
    vec3 reference = abs(n.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 tangent = cross(reference, n);
    float lengthSquared = dot(tangent, tangent);
    return lengthSquared > 1.0e-12 ? tangent * inversesqrt(lengthSquared) : vec3(1.0, 0.0, 0.0);
}

/** Handedness-corrected unit bitangent; zero when the product is unusable. */
vec3 tangentFrameBitangent(vec3 n, vec3 t, float handedness) {
    vec3 bitangent = cross(n, t) * (handedness < 0.0 ? -1.0 : 1.0);
    float lengthSquared = dot(bitangent, bitangent);
    return lengthSquared > 1.0e-8 ? bitangent * inversesqrt(lengthSquared) : vec3(0.0);
}

/**
 * Bounded anisotropic tangent frame for the shaded normal.
 *
 * <p>The vertex tangent is re-projected onto the shaded normal (so normal-map
 * perturbation does not invalidate the frame), and the bitangent is rebuilt
 * with the handedness sign emitted by {@code pbr-forward.vert}. That sign
 * already includes the mirrored-UV/negative-model-determinant term, so a
 * negative determinant flips the bitangent instead of silently producing a
 * left-handed frame. A missing, zero, non-finite or normal-parallel tangent
 * falls back to {@link #fallbackAnisotropicTangent}; the fallback is still a
 * valid anisotropic frame, never an unbounded normalization. The rotation
 * happens inside the tangent plane, with zero aligning the stretch with the
 * mesh tangent. Returns false only when even the fallback cannot build a
 * finite orthonormal frame.</p>
 */
bool anisotropicTangentFrame(vec3 n, out vec3 tangent, out vec3 bitangent) {
    tangent = vec3(0.0);
    bitangent = vec3(0.0);
    if (!(dot(n, n) > 1.0e-12)) return false;
    vec3 candidate = vTangent;
    if (!(dot(candidate, candidate) > 1.0e-8)) {
        candidate = fallbackAnisotropicTangent(n);
    }
    vec3 projected = candidate - n * dot(n, candidate);
    if (!(dot(projected, projected) > 1.0e-8)) {
        projected = fallbackAnisotropicTangent(n);
    }
    if (!(dot(projected, projected) > 1.0e-8)) return false;
    tangent = normalize(projected);
    bitangent = tangentFrameBitangent(n, tangent, vTangentHandedness);
    if (!(dot(bitangent, bitangent) > 1.0e-8)) {
        tangent = fallbackAnisotropicTangent(n);
        bitangent = tangentFrameBitangent(n, tangent, 1.0);
    }
    if (!(dot(bitangent, bitangent) > 1.0e-8)) return false;
    float c = cos(uAnisotropicRotation);
    float s = sin(uAnisotropicRotation);
    vec3 rotatedTangent = tangent * c + bitangent * s;
    vec3 rotatedBitangent = bitangent * c - tangent * s;
    float tangentLengthSquared = dot(rotatedTangent, rotatedTangent);
    float bitangentLengthSquared = dot(rotatedBitangent, rotatedBitangent);
    if (!(tangentLengthSquared > 1.0e-8) || !(bitangentLengthSquared > 1.0e-8)) return false;
    tangent = rotatedTangent * inversesqrt(tangentLengthSquared);
    bitangent = rotatedBitangent * inversesqrt(bitangentLengthSquared);
    return true;
}

/**
 * Burley anisotropic GGX distribution.
 *
 * <p>{@code at}/{@code ab} are the tangential GGX alphas along the anisotropy
 * direction and perpendicular to it. The half-vector is evaluated in the
 * (tangent, bitangent, normal) frame; the result reduces to the isotropic GGX
 * distribution when both axes are equal. A non-finite/degenerate scaled
 * half-vector returns zero instead of propagating NaN.</p>
 */
float distributionGgxAnisotropic(vec3 n, vec3 h, vec3 t, vec3 b, float at, float ab) {
    float nDotH = max(dot(n, h), 0.0);
    float tDotH = dot(t, h);
    float bDotH = dot(b, h);
    float a2 = at * ab;
    vec3 scaled = vec3(ab * tDotH, at * bDotH, a2 * nDotH);
    float lengthSquared = dot(scaled, scaled);
    if (!(lengthSquared > 1.0e-20) || !(a2 > 0.0)) return 0.0;
    float weight = a2 / lengthSquared;
    return a2 * weight * weight / PI;
}

/**
 * Burley anisotropic Smith visibility {@code V = G / (4 (n.v) (n.l))}.
 *
 * <p>Direct specular is {@code D * V * F}, matching the isotropic path's
 * split of GGX and Smith. The visibility is finite for every finite frame;
 * the caller's positive {@code n.l} gate keeps the grazing denominator out of
 * the final radiance.</p>
 */
float visibilitySmithAnisotropic(vec3 n, vec3 v, vec3 l, vec3 t, vec3 b,
                                 float at, float ab) {
    float nDotV = max(dot(n, v), 0.0);
    float nDotL = max(dot(n, l), 0.0);
    vec3 scaledView = vec3(at * dot(t, v), ab * dot(b, v), nDotV);
    vec3 scaledLight = vec3(at * dot(t, l), ab * dot(b, l), nDotL);
    float lambdaV = nDotL * length(scaledView);
    float lambdaL = nDotV * length(scaledLight);
    return 0.5 / max(lambdaV + lambdaL, 1.0e-5);
}

/**
 * Base-layer direct BRDF with the Burley anisotropic distribution and Smith
 * visibility. Only the base specular lobe is anisotropic: the diffuse term,
 * Schlick Fresnel and the optional clearcoat split stay isotropic. Strength
 * zero never reaches this function; the caller keeps the original
 * {@code directBrdf} path so the isotropic output is unchanged.</p>
 */
vec3 directBrdfAnisotropic(vec3 n, vec3 v, vec3 l, vec3 radiance,
                           vec3 baseColor, float metallic, float roughness, vec3 f0,
                           vec3 tangent, vec3 bitangent, float strength) {
    float nDotL = max(dot(n, l), 0.0);
    if (nDotL <= 0.0) return vec3(0.0);
    vec3 h = normalize(v + l);
    vec3 f = fresnelSchlick(max(dot(h, v), 0.0), f0);
    float alpha = max(roughness * roughness, ANISOTROPIC_MIN_ALPHA);
    float at = max(alpha * (1.0 + strength), ANISOTROPIC_MIN_ALPHA);
    float ab = max(alpha * (1.0 - strength), ANISOTROPIC_MIN_ALPHA);
    float d = distributionGgxAnisotropic(n, h, tangent, bitangent, at, ab);
    float visibility = visibilitySmithAnisotropic(n, v, l, tangent, bitangent, at, ab);
    vec3 specular = d * visibility * f;
    vec3 kd = (1.0 - f) * (1.0 - metallic);
    return (kd * baseColor / PI + specular) * radiance * nDotL;
}

/**
 * Bounded anisotropic IBL lookup direction.
 *
 * <p>The isotropic reflection vector is stretched inside the tangent plane
 * along the anisotropy direction and squeezed across it, then renormalized.
 * The main shader also widens the prefiltered-environment mip with the same
 * strength. This is a deliberate bounded energy approximation: it reuses the
 * existing prefiltered cubemap and isotropic BRDF LUT instead of a true
 * anisotropic prefilter, so it must not be read as an exact anisotropic IBL
 * solution.</p>
 */
vec3 anisotropicReflectionDirection(vec3 n, vec3 v, vec3 tangent, vec3 bitangent,
                                    float strength) {
    vec3 reflection = reflect(-v, n);
    vec3 local = vec3(dot(reflection, tangent), dot(reflection, bitangent),
                      dot(reflection, n));
    local.x *= 1.0 + 0.5 * strength;
    local.y *= 1.0 - 0.35 * strength;
    vec3 warped = tangent * local.x + bitangent * local.y + n * local.z;
    float lengthSquared = dot(warped, warped);
    return lengthSquared > 1.0e-8 ? warped * inversesqrt(lengthSquared) : reflection;
}

/**
 * Tangent-space clearcoat normal.
 *
 * <p>The optional clearcoat normal map uses the base normal map's convention:
 * sampled XYZ decodes from [0,1] to [-1,1], the XY slope is scaled by
 * uClearcoatNormalStrength and the TBN frame comes from pbr-forward.vert
 * (tangent, handedness-corrected bitangent, geometric normal). The coat is
 * oriented from the geometric normal, not the base normal map, so the thin
 * layer keeps its own smooth orientation. Strength zero reduces to the
 * geometric normal exactly. The optional coat normal map keeps sampling
 * {@code vTexCoord}: the coat is an independent overlay and is not
 * parallax-offset by the base-layer height field.</p>
 */
vec3 clearcoatNormal(vec3 geometricNormal) {
    vec3 sampled = texture(uClearcoatNormalMap, vTexCoord).xyz * 2.0 - 1.0;
    sampled.xy *= uClearcoatNormalStrength;
    vec3 t = normalize(vTangent);
    vec3 b = normalize(cross(geometricNormal, t)) * vTangentHandedness;
    return normalize(mat3(t, b, geometricNormal) * sampled);
}

/** Coat interface Fresnel; the complementary share is transmitted to the base layer. */
float clearcoatFresnel(float cosTheta) {
    return CLEARCOAT_F0
            + (1.0 - CLEARCOAT_F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

/** Roughness-aware coat Fresnel for the IBL split; shares the base Schlick form. */
float clearcoatFresnelRoughness(float cosTheta, float roughness) {
    return CLEARCOAT_F0
            + (max(1.0 - roughness, CLEARCOAT_F0) - CLEARCOAT_F0)
            * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

/**
 * Clearcoat specular lobe for one light, already multiplied by the coat N.L.
 * Reuses the bounded GGX/Smith evaluator with the independent coat roughness
 * and the fixed CLEARCOAT_F0 interface reflectance.
 */
vec3 clearcoatDirectBrdf(vec3 cn, vec3 v, vec3 l, float roughness) {
    float nDotL = max(dot(cn, l), 0.0);
    if (nDotL <= 0.0) return vec3(0.0);
    vec3 h = normalize(v + l);
    float f = clearcoatFresnel(max(dot(h, v), 0.0));
    float d = distributionGgx(cn, h, roughness);
    float g = geometrySmith(cn, v, l, roughness);
    return vec3(d * g * f / max(4.0 * max(dot(cn, v), 0.0) * nDotL, 1.0e-5) * nDotL);
}

/**
 * Bounded smoothstep fade shared by the angular and distance parallax windows.
 *
 * <p>Returns one at or below {@code start}, zero at or above {@code end} and the
 * smooth cubic ramp between. The caller validates that the inputs are finite
 * and ordered; the span floor only keeps a degenerate window from dividing by
 * zero.</p>
 */
float parallaxFade(float value, float start, float end) {
    float span = max(end - start, 1.0e-4);
    float ramp = clamp((value - start) / span, 0.0, 1.0);
    return 1.0 - ramp * ramp * (3.0 - 2.0 * ramp);
}

/**
 * Linear height for one UV, from the selected channel of {@code uHeightMap}.
 *
 * <p>The role is linear (non-colour) data and the shader never gamma-decodes
 * it. The channel selector is clamped to the four GLSL channels and the result
 * is bounded to {@code [0, 1]} so an inverted or malformed texel cannot make
 * the march unbounded. {@code uParallaxFlipHeight} returns {@code 1 - h} for
 * height maps whose convention is inverted relative to this shader.</p>
 */
float parallaxHeight(vec2 uv) {
    vec4 texel = texture(uHeightMap, uv);
    int channel = clamp(uParallaxHeightChannel, 0, 3);
    float height = channel == 0 ? texel.r
            : channel == 1 ? texel.g
            : channel == 2 ? texel.b : texel.a;
    return clamp(uParallaxFlipHeight != 0 ? 1.0 - height : height, 0.0, 1.0);
}

/**
 * Bounded tangent-space parallax occlusion UV.
 *
 * <p>The interpolated view direction is expressed in the mesh tangent frame
 * and marched through the height field from the surface layer toward the
 * camera. Each layer samples {@link parallaxHeight} and the first layer below
 * the traced height is refined with one linear interpolation between the two
 * bracketing samples, which recovers a continuous intersection instead of a
 * quantised layer index. The returned UV is used only by the following
 * material texture lookups: fragment depth, the shadow-map depth written by
 * the shadow caster, velocity and the geometric silhouette are untouched, so
 * this is an approximation of relief, not geometric displacement. There is no
 * self-shadowing and no silhouette expansion.</p>
 *
 * <p>The offset is scaled by the product of the angular and distance fades, so
 * both windows degrade continuously to the exact original UV. A tangent-space
 * slope cap ({@link #PARALLAX_MAX_SLOPE}), a view-depth floor, a compile-time
 * loop cap and the clamped uniform step count keep every coordinate and loop
 * bounded. Height lookups and the final material lookups use their samplers'
 * wrap modes, so out-of-range UVs wrap or clamp instead of producing NaN. A
 * non-finite or inconsistent uniform (possible only through a raw material
 * override) fails closed to {@code vTexCoord}.</p>
 */
vec2 parallaxOcclusionUv(vec3 geometricNormal, vec3 viewDirection) {
    float scale = uParallaxHeightScale;
    if (!(scale > 0.0) || !(scale <= 1.0)) return vTexCoord;
    if (!(uParallaxFadeStart >= 0.0) || !(uParallaxFadeEnd > uParallaxFadeStart)
            || !(uParallaxFadeEnd <= 90.0)) return vTexCoord;
    if (!(uParallaxFadeStartDistance >= 0.0)
            || !(uParallaxMaxDistance > uParallaxFadeStartDistance)) return vTexCoord;
    float nDotV = dot(geometricNormal, viewDirection);
    if (!(nDotV > 0.0)) return vTexCoord;
    float viewAngle = degrees(acos(clamp(nDotV, 0.0, 1.0)));
    float angleWeight = parallaxFade(viewAngle, uParallaxFadeStart, uParallaxFadeEnd);
    float viewDistance = length(uCameraPosition - vWorldPosition);
    float distanceWeight = parallaxFade(viewDistance, uParallaxFadeStartDistance,
            uParallaxMaxDistance);
    float weight = clamp(angleWeight * distanceWeight, 0.0, 1.0);
    if (!(weight > 0.0)) return vTexCoord;
    // Rebuild the tangent frame from the interpolated geometric inputs. A
    // missing, zero or normal-parallel tangent falls back to the deterministic
    // normal-derived axis; the frame is never unbounded-normalized.
    vec3 tangent = vTangent;
    if (!(dot(tangent, tangent) > 1.0e-8)) tangent = fallbackAnisotropicTangent(geometricNormal);
    vec3 projected = tangent - geometricNormal * dot(geometricNormal, tangent);
    if (!(dot(projected, projected) > 1.0e-8)) {
        projected = fallbackAnisotropicTangent(geometricNormal);
    }
    vec3 axisT = normalize(projected);
    vec3 axisB = tangentFrameBitangent(geometricNormal, axisT, vTangentHandedness);
    if (!(dot(axisB, axisB) > 1.0e-8)) {
        axisT = fallbackAnisotropicTangent(geometricNormal);
        axisB = tangentFrameBitangent(geometricNormal, axisT, 1.0);
    }
    vec3 viewT = vec3(dot(viewDirection, axisT), dot(viewDirection, axisB), nDotV);
    if (!(viewT.z > PARALLAX_MIN_VIEW_Z)) return vTexCoord;
    int minimumSteps = clamp(uParallaxMinSteps, 1, PARALLAX_MAX_STEPS);
    int maximumSteps = clamp(uParallaxMaxSteps, minimumSteps, PARALLAX_MAX_STEPS);
    float layers = mix(float(maximumSteps), float(minimumSteps), clamp(viewT.z, 0.0, 1.0));
    int layerCount = clamp(int(ceil(layers)), minimumSteps, maximumSteps);
    vec2 slope = viewT.xy / max(viewT.z, PARALLAX_MIN_VIEW_Z);
    float slopeLength = length(slope);
    if (slopeLength > PARALLAX_MAX_SLOPE) slope *= PARALLAX_MAX_SLOPE / slopeLength;
    vec2 stepUv = slope * scale * weight / float(layerCount);
    float layerDepth = 1.0 / float(layerCount);
    vec2 currentUv = vTexCoord;
    float currentDepth = 0.0;
    float currentHeight = parallaxHeight(currentUv);
    vec2 previousUv = currentUv;
    float previousDepth = 0.0;
    float previousHeight = currentHeight;
    int steps = 0;
    for (int i = 0; i < PARALLAX_MAX_STEPS; ++i) {
        if (i >= layerCount || !(currentDepth < currentHeight)) break;
        previousUv = currentUv;
        previousDepth = currentDepth;
        previousHeight = currentHeight;
        currentUv -= stepUv;
        currentDepth += layerDepth;
        currentHeight = parallaxHeight(currentUv);
        steps = i + 1;
    }
    if (steps == 0 || !(previousDepth < previousHeight)
            || !(currentHeight < currentDepth)) return currentUv;
    float previousError = previousDepth - previousHeight;
    float currentError = currentDepth - currentHeight;
    float denominator = previousError - currentError;
    float blend = denominator < -1.0e-6
            ? clamp(previousError / denominator, 0.0, 1.0) : 0.0;
    return mix(previousUv, currentUv, blend);
}

vec2 receiverPlaneGradient(vec3 projected) {
    vec3 dx = dFdx(projected), dy = dFdy(projected);
    float determinant = dx.x * dy.y - dx.y * dy.x;
    if (abs(determinant) < 1e-10) return vec2(0.0);
    return vec2(dx.z * dy.y - dy.z * dx.y, dy.z * dx.x - dx.z * dy.x) / determinant;
}

/**
 * Per-channel penetration of the selected analytic profile. UNIFORM (wax)
 * returns one for every channel; SKIN returns the fixed red-dominant ratios.
 */
vec3 subsurfacePenetration() {
    return uSubsurfaceProfile == 1 ? SUBSURFACE_SKIN_PENETRATION : vec3(1.0);
}

// Comparison-based sanitizers: a NaN/Inf raw uniform override must fail closed
// to the inert value instead of contaminating the direct radiance.
float subsurfaceSafeThickness() {
    return uSubsurfaceThickness >= 0.0 && uSubsurfaceThickness <= SUBSURFACE_MAX_THICKNESS
            ? uSubsurfaceThickness : 0.0;
}

float subsurfaceSafeProfileScale() {
    return uSubsurfaceProfileScale >= SUBSURFACE_MIN_PROFILE_SCALE
            && uSubsurfaceProfileScale <= SUBSURFACE_MAX_PROFILE_SCALE
            ? uSubsurfaceProfileScale : SUBSURFACE_MIN_PROFILE_SCALE;
}

float subsurfaceSafeIntensity() {
    return uSubsurfaceIntensity >= 0.0 && uSubsurfaceIntensity <= SUBSURFACE_MAX_INTENSITY
            ? uSubsurfaceIntensity : 0.0;
}

vec3 subsurfaceSafeTint() {
    vec3 tint = uSubsurfaceScatterColor;
    bool valid = tint.r >= 0.0 && tint.r <= 1.0
            && tint.g >= 0.0 && tint.g <= 1.0
            && tint.b >= 0.0 && tint.b <= 1.0;
    return valid ? tint : vec3(0.0);
}

/**
 * Per-pixel thickness in metres. CONSTANT uses the authored value directly;
 * OCCLUSION treats the occlusion map as a thickness proxy and BASE_ALPHA uses
 * the base-colour alpha. The returned value is bounded to
 * {@link #SUBSURFACE_MAX_THICKNESS}, so no texture or raw override can make the
 * attenuation unbounded.
 */
float subsurfaceThickness(vec4 baseSample, vec2 materialUv) {
    float thickness = subsurfaceSafeThickness();
    if (uSubsurfaceThicknessSource == 1) {
        float occlusion = clamp(texture(uOcclusionMap, materialUv).r, 0.0, 1.0);
        thickness = clamp((1.0 - occlusion) * thickness, 0.0, SUBSURFACE_MAX_THICKNESS);
    } else if (uSubsurfaceThicknessSource == 2) {
        float alpha = clamp(baseSample.a, 0.0, 1.0);
        thickness = clamp((1.0 - alpha) * thickness, 0.0, SUBSURFACE_MAX_THICKNESS);
    }
    return thickness;
}

/** Exponential per-channel attenuation for the resolved thickness, in metres. */
vec3 subsurfaceAttenuation(float thickness) {
    float scale = subsurfaceSafeProfileScale();
    vec3 penetration = max(subsurfacePenetration(), vec3(0.001));
    return exp(-thickness / max(scale * penetration,
            vec3(SUBSURFACE_MIN_PROFILE_SCALE)));
}

/**
 * Bounded back-light transmission for one light, in the same linear radiance
 * space as the direct BRDF. It depends only on the back-facing cosine, the
 * resolved thickness and the analytic profile; it is added next to the
 * unchanged diffuse/specular terms, so a front highlight is never scaled by
 * the scatter weight and a back-lit thin surface receives a bounded glow.
 */
vec3 subsurfaceTransmission(vec3 n, vec3 l, vec3 radiance) {
    float backFacing = clamp(dot(-n, l), 0.0, 1.0);
    if (!(backFacing > 0.0)) return vec3(0.0);
    float intensity = subsurfaceSafeIntensity();
    if (!(intensity > 0.0)) return vec3(0.0);
    return subsurfaceSafeTint() * intensity * radiance
            * backFacing * subsurfaceAttenuation(gSubsurfaceThickness);
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
 * <p>Directional cascades use an orthographic projection, so normalized
 * light-space window depth is affine in the distance from the light plane.
 * Treating it as proportional to that distance makes the range cancel in the
 * PCSS ratio: {@code penumbra = (receiver - blocker) * lightSize / blocker}.
 * The ortho near offset (0.01 for cascades, the configured legacy near plane)
 * is neglected; it is below 0.1% of typical blocker depths and the resulting
 * radius is clamped by the caller.  {@code uDirectionalCascadeTexelWorld} is
 * the same world-texel size the shadow planner uses for culling.</p>
 */
float pcssPenumbraTexels(int cascade, float receiverDepth, float blockerDepth) {
    float texelWorld = uDirectionalCascadeTexelWorld[cascade];
    if (!(texelWorld > 0.0)) return 0.0;
    float blockerDistance = max(blockerDepth, 1.0e-4);
    float separation = max(receiverDepth - blockerDepth, 0.0);
    float penumbraWorld = separation * uPcssLightSize / blockerDistance;
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

// Artistic thin-surface backlighting. The current double-sided-corrected
// normal decides whether this face is backlit; the forward lobe is tightened
// and attenuated by normalized thickness. This is deliberately not an
// energy-conserving transmission model and only runs when both controls are on.
vec3 thinSurfaceTransmission(vec3 n, vec3 v, vec3 l, vec3 radiance, vec3 baseColor) {
    if (uTransmissionFactor <= 0.0 || uBacklightFactor <= 0.0) return vec3(0.0);
    float back = clamp(dot(-n, l), 0.0, 1.0);
    if (back <= 0.0) return vec3(0.0);
    float forward = clamp(dot(v, -l), 0.0, 1.0);
    float thickness = clamp(uThicknessFactor, 0.0, 1.0);
    float lobe = pow(forward, mix(1.0, 8.0, thickness));
    float absorption = exp(-2.0 * thickness);
    return baseColor * radiance
            * (uTransmissionFactor * uBacklightFactor * back * lobe * absorption);
}

float rangeInverseSquareAttenuation(float distanceToLight, float range) {
    float rangeWindow = clamp(1.0 - distanceToLight / max(range, 1.0e-4), 0.0, 1.0);
    return (rangeWindow * rangeWindow) / max(distanceToLight * distanceToLight, 1.0e-4);
}

vec3 directionalRadiance(int index, vec3 n, vec3 v, vec3 baseColor,
                         float metallic, float roughness, vec3 f0,
                         vec3 clearcoatNormalValue, float clearcoatRoughness,
                         vec3 anisotropicTangent, vec3 anisotropicBitangent,
                         float anisotropicStrength) {
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
    vec3 radiance = light.colorIntensity.rgb * light.colorIntensity.a;
    vec3 base = anisotropicStrength > 0.0
            ? directBrdfAnisotropic(n, v, l, radiance, baseColor, metallic, roughness, f0,
                    anisotropicTangent, anisotropicBitangent, anisotropicStrength)
            : directBrdf(n, v, l, radiance, baseColor, metallic, roughness, f0);
    if (uClearcoatEnabled != 0) {
        vec3 h = normalize(v + l);
        float transmitted = 1.0 - clearcoatFresnel(max(dot(h, v), 0.0));
        base = base * transmitted
                + clearcoatDirectBrdf(clearcoatNormalValue, v, l, clearcoatRoughness)
                        * radiance;
    }
    if (uSubsurfaceEnabled != 0) {
        // Separate back-light term: the existing diffuse and specular output
        // above is untouched, including a front-facing specular highlight.
        base += subsurfaceTransmission(n, l, radiance);
    }
    // Thin-surface backlight transmission is an additive artistic term next to
    // the unchanged reflection lobe. Zero controls return exactly zero.
    base += thinSurfaceTransmission(n, v, l, radiance, baseColor);
    return visibility * base;
}

vec3 localRadiance(int index, vec3 n, vec3 v, vec3 baseColor,
                   float metallic, float roughness, vec3 f0,
                   vec3 clearcoatNormalValue, float clearcoatRoughness,
                   vec3 anisotropicTangent, vec3 anisotropicBitangent,
                   float anisotropicStrength) {
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
    vec3 radiance = light.colorIntensity.rgb * light.colorIntensity.a * attenuation * cone;
    vec3 radiance = light.colorIntensity.rgb * light.colorIntensity.a * attenuation * cone;
    vec3 base = anisotropicStrength > 0.0
            ? directBrdfAnisotropic(n, v, l, radiance, baseColor, metallic, roughness, f0,
                    anisotropicTangent, anisotropicBitangent, anisotropicStrength)
            : directBrdf(n, v, l, radiance, baseColor, metallic, roughness, f0);
    if (uClearcoatEnabled != 0) {
        vec3 h = normalize(v + l);
        float transmitted = 1.0 - clearcoatFresnel(max(dot(h, v), 0.0));
        base = base * transmitted
                + clearcoatDirectBrdf(clearcoatNormalValue, v, l, clearcoatRoughness)
                        * radiance;
    }
    if (uSubsurfaceEnabled != 0) {
        // Separate back-light term: the existing diffuse and specular output
        // above is untouched, including a front-facing specular highlight.
        base += subsurfaceTransmission(n, l, radiance);
    }
    base += thinSurfaceTransmission(n, v, l, radiance, baseColor);
    return visibility * base;
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
    vec3 geometricNormal = normalize(vNormal);
    if (uDoubleSided != 0 && !gl_FrontFacing) geometricNormal = -geometricNormal;
    vec3 v = normalize(uCameraPosition - vWorldPosition);
    // Parallax occlusion only offsets the following material lookups. It never
    // writes depth, shadow, velocity or the silhouette, and disabled/zero scale
    // keeps this variable equal to vTexCoord exactly.
    vec2 materialUv = vTexCoord;
    if (uParallaxEnabled != 0 && uParallaxHeightScale > 0.0) {
        materialUv = parallaxOcclusionUv(geometricNormal, v);
    }
    vec4 vertexColor = uHasVertexColor != 0 ? vVertexColor : vec4(1.0);
    vec4 baseSample = texture(uBaseColorMap, materialUv) * uBaseColorFactor * vertexColor;
    if (uAlphaMode == 1 && baseSample.a < uAlphaCutoff) discard;
    vec2 mr = texture(uMetallicRoughnessMap, materialUv).gb;
    float roughness = clamp(mr.x * uRoughnessFactor, 0.045, 1.0);
    float metallic = clamp(mr.y * uMetallicFactor, 0.0, 1.0);
    vec3 n = geometricNormal;
    if (uEnableNormalMap != 0) {
        vec3 sampled = texture(uNormalMap, materialUv).xyz * 2.0 - 1.0;
        sampled.xy *= uNormalScale;
        vec3 t = normalize(vTangent);
        vec3 b = normalize(cross(n, t)) * vTangentHandedness;
        n = normalize(mat3(t, b, n) * sampled);
    }
    vec3 f0 = mix(vec3(0.04), baseSample.rgb, metallic);
    // Lazy clearcoat setup: disabled keeps the original single-layer values.
    vec3 clearcoatNormalValue = n;
    float clearcoatRoughness = 0.0;
    if (uClearcoatEnabled != 0) {
        clearcoatNormalValue = clearcoatNormal(geometricNormal);
        clearcoatRoughness = clamp(uClearcoatRoughness, CLEARCOAT_MIN_ROUGHNESS, 1.0);
    }
    if (uClusterDebugMode != 0) {
        uint localLightCount = uLightHeader.y;
        int debugCluster = localLightCount > 0u ? resolveCluster() : -1;
        FragColor = vec4(clusterDebugColor(debugCluster), 1.0);
        return;
    }
    // Lazy anisotropic setup: disabled or zero strength keeps the original
    // isotropic BRDF and the original environment sample sequence.
    float anisotropicStrength = 0.0;
    vec3 anisotropicTangent = vec3(0.0);
    vec3 anisotropicBitangent = vec3(0.0);
    if (uAnisotropicEnabled != 0 && uAnisotropicStrength > 0.0) {
        anisotropicStrength = clamp(uAnisotropicStrength, 0.0, 1.0);
        // Reject a non-finite rotation explicitly. Some drivers do not preserve
        // NaN through cos/sin followed only by comparison-based length checks,
        // which can leave a NaN tangent in the BRDF and clamp the whole pixel
        // to zero instead of restoring the isotropic path. Never evaluate the
        // trig/frame math for a non-finite rotation; the frame's own length
        // checks remain as the second line of defense.
        if (isnan(uAnisotropicRotation) || isinf(uAnisotropicRotation)) {
            anisotropicStrength = 0.0;
        } else if (!anisotropicTangentFrame(n, anisotropicTangent, anisotropicBitangent)) {
            // Also clear the outs: a compiler may speculatively evaluate both
            // arms of the later ternary, and arithmetic on NaN tangent inputs
            // could otherwise contaminate the selected isotropic result.
            anisotropicStrength = 0.0;
            anisotropicTangent = vec3(0.0);
            anisotropicBitangent = vec3(0.0);
        }
    }
    // Resolve the bounded thickness once for the two light evaluators. The
    // disabled path leaves it zero and never samples the occlusion map or the
    // base alpha for this term.
    gSubsurfaceThickness = 0.0;
    if (uSubsurfaceEnabled != 0) {
        gSubsurfaceThickness = subsurfaceThickness(baseSample, materialUv);
    }
    vec3 direct = vec3(0.0);
    if (uEnableDirect != 0) {
        uint directionalCount = uLightHeader.x;
        uint localCount = uLightHeader.y;
        for (uint i = 0u; i < directionalCount; i++) {
            direct += directionalRadiance(int(i), n, v, baseSample.rgb,
                    metallic, roughness, f0, clearcoatNormalValue, clearcoatRoughness,
                    anisotropicTangent, anisotropicBitangent, anisotropicStrength);
        }
        int cluster = localCount > 0u ? resolveCluster() : -1;
        if (cluster >= 0) {
            uvec4 header = uClusterHeaders[cluster];
            if (header.z != 0u) {
                // Overflow: scan the complete local range with the same
                // evaluator so no light is lost, however expensive.
                for (uint i = 0u; i < localCount; i++) {
                    direct += localRadiance(int(directionalCount + i), n, v, baseSample.rgb,
                            metallic, roughness, f0, clearcoatNormalValue, clearcoatRoughness,
                            anisotropicTangent, anisotropicBitangent, anisotropicStrength);
                }
            } else {
                uint count = header.y;
                for (uint k = 0u; k < count; k++) {
                    direct += localRadiance(int(uClusterIndices[header.x + k]), n, v,
                            baseSample.rgb, metallic, roughness, f0,
                            clearcoatNormalValue, clearcoatRoughness,
                            anisotropicTangent, anisotropicBitangent, anisotropicStrength);
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
    // Keep the world-space reflection separate from the globally rotated
    // lookup direction. Local reflection probes are positioned in world space:
    // their box/sphere intersection must use the unrotated (but optionally
    // anisotropic-warped) reflection. Only the global prefiltered environment
    // consumes uEnvironmentRotation.
    vec3 worldReflection = reflect(-v, n);
    vec3 reflection = rotateEnvironment(worldReflection);
    float prefilterRoughness = roughness;
    if (anisotropicStrength > 0.0) {
        // Bounded approximation, not an exact anisotropic IBL: the existing
        // prefiltered cubemap is sampled through a tangent-plane-stretched
        // direction at a slightly widened mip. The isotropic BRDF LUT and
        // Fresnel term are reused, so energy is only approximately preserved.
        worldReflection = anisotropicReflectionDirection(
                n, v, anisotropicTangent, anisotropicBitangent, anisotropicStrength);
        reflection = rotateEnvironment(worldReflection);
        prefilterRoughness = clamp(roughness * (1.0 + 0.5 * anisotropicStrength),
                0.0, 1.0);
    }
    vec3 prefiltered = textureLod(uPrefilteredMap, reflection,
            prefilterRoughness * uPrefilterMaxLod).rgb;
    if (uReflectionProbeCount > 0) {
        // Specular-only local probes: the CPU selector already produced the
        // normalised per-slot weights and the residual global weight. The
        // count-zero path above is the exact original global sample.
        vec3 probeRadiance = vec3(0.0);
        float probeWeight = 0.0;
        float weight0 = clamp(uReflectionProbe0Weight, 0.0, 1.0);
        if (weight0 > 0.0) {
            probeRadiance += weight0 * reflectionProbeRadiance(
                    uReflectionProbe0, vWorldPosition, worldReflection,
                    uReflectionProbe0Position, uReflectionProbe0Extents,
                    uReflectionProbe0Shape, uReflectionProbe0Rotation,
                    uReflectionProbe0MaxLod, uReflectionProbe0Intensity,
                    prefilterRoughness);
            probeWeight += weight0;
        }
        if (uReflectionProbeCount > 1) {
            float weight1 = clamp(uReflectionProbe1Weight, 0.0, 1.0);
            if (weight1 > 0.0) {
                probeRadiance += weight1 * reflectionProbeRadiance(
                        uReflectionProbe1, vWorldPosition, worldReflection,
                        uReflectionProbe1Position, uReflectionProbe1Extents,
                        uReflectionProbe1Shape, uReflectionProbe1Rotation,
                        uReflectionProbe1MaxLod, uReflectionProbe1Intensity,
                        prefilterRoughness);
                probeWeight += weight1;
            }
        }
        float globalWeight = clamp(uReflectionProbeGlobalWeight, 0.0, 1.0);
        float weightSum = probeWeight + globalWeight;
        if (weightSum > 0.0) {
            prefiltered = (prefiltered * globalWeight + probeRadiance) / weightSum;
        }
    }
    vec2 brdf = texture(uBrdfLut, vec2(nDotV, roughness)).rg;
    vec3 specularIbl = prefiltered * (f * brdf.x + brdf.y);
    float aoSample = texture(uOcclusionMap, materialUv).r;
    float ao = mix(1.0, aoSample, uOcclusionStrength);
    vec3 indirect = vec3(0.0);
    if (uEnableDiffuseIbl != 0) indirect += kd * diffuseIbl;
    if (uEnableSpecularIbl != 0) indirect += specularIbl;
    if (uClearcoatEnabled != 0) {
        // Same convex interface split as the direct path: the base indirect
        // term keeps (1 - F_coat) and the coat adds its own prefiltered
        // specular evaluated at the independent coat roughness.  The coat
        // reflection is rotated by the existing environment rotation but is
        // not tinted by the base metallic/roughness terms.
        float cnDotV = max(dot(clearcoatNormalValue, v), 0.0);
        float clearcoatF = clearcoatFresnelRoughness(cnDotV, clearcoatRoughness);
        indirect *= 1.0 - clearcoatF;
        if (uEnableSpecularIbl != 0) {
            vec3 coatReflection = rotateEnvironment(reflect(-v, clearcoatNormalValue));
            vec3 coatPrefiltered = textureLod(uPrefilteredMap, coatReflection,
                    clearcoatRoughness * uPrefilterMaxLod).rgb;
            vec2 coatBrdf = texture(uBrdfLut, vec2(cnDotV, clearcoatRoughness)).rg;
            indirect += coatPrefiltered * (clearcoatF * coatBrdf.x + coatBrdf.y);
        }
    }
    float gtao = 1.0;
    if (uGtaoEnabled != 0 && uGtaoMaterialOptOut == 0) {
        gtao = texture(uGtaoMap, gl_FragCoord.xy / vec2(textureSize(uGtaoMap, 0))).r;
    }
    if (uGtaoPreview != 0) {
        FragColor = vec4(vec3(gtao), 1.0);
        return;
    }
    indirect *= ao * gtao * uEnvironmentIntensity;
    vec3 emissive = texture(uEmissiveMap, materialUv).rgb * uEmissiveFactor;
    FragColor = vec4(max(direct + indirect + emissive, vec3(0.0)),
            uAlphaMode == 2 ? baseSample.a : 1.0);
#ifdef HAIKALAT_VOLUME_FOG
    FragColor = volumeFogSurface(FragColor, vWorldPosition, uVolumeAdditive != 0);
#endif
}
