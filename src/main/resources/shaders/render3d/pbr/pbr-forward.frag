#version 460 core

#define POINT_SHADOW_FACE_COUNT 6

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

layout(std140, binding = 5) uniform ShadowSamplingBlock {
    ivec4 uPointShadowMeta[2];
    mat4 uPointSlotMatrices[2 * POINT_SHADOW_FACE_COUNT];
    vec4 uPointFaceRects[2 * POINT_SHADOW_FACE_COUNT];
    ivec4 uSpotShadowMeta[4];
    mat4 uSpotSlotMatrices[4];
    vec4 uSpotTileRects[4];
    ivec4 uShadowQualityMeta;
};

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
uniform sampler2D uShadowMap;
uniform sampler2D uPointShadowMap;
uniform sampler2D uSpotShadowMap;
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

float sampleDirectionalCascade(int cascade, vec3 n, vec3 l) {
    vec3 samplePosition = vWorldPosition + n * shadowNormalBias();
    vec4 lightPosition = uDirectionalCascadeCount > 1
        ? uDirectionalCascadeMatrices[cascade] * vec4(samplePosition, 1.0)
        : uDirectionalLightSpace * vec4(samplePosition, 1.0);
    vec3 projected = lightPosition.xyz / lightPosition.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || any(lessThanEqual(projected.xy, vec2(0.0)))
            || any(greaterThanEqual(projected.xy, vec2(1.0)))) return 0.0;
    float bias = shadowDepthBias(uShadowBias, n, l);
    int columns = uDirectionalCascadeCount == 2 || uDirectionalCascadeCount > 2 ? 2 : 1;
    int rows = uDirectionalCascadeCount > 2 ? 2 : 1;
    vec2 scale = vec2(1.0 / float(columns), 1.0 / float(rows));
    vec2 offset = vec2(float(cascade % columns), float(cascade / columns)) * scale;
    projected.xy = projected.xy * scale + offset;
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    int radius = shadowKernelRadius();
    vec2 guard = texel * (float(radius) + 0.5);
    vec2 tileMinimum = offset + guard;
    vec2 tileMaximum = offset + scale - guard;
    vec2 gradient = receiverPlaneGradient(projected);
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

float directionalShadowFactor(vec3 n, vec3 l) {
    if (uHasDirectionalShadow == 0) return 0.0;
    int count = max(uDirectionalCascadeCount, 1);
    int cascade = count - 1;
    for (int index = 0; index < count; index++) {
        if (vViewDepth <= uDirectionalCascadeSplits[index]) { cascade = index; break; }
    }
    float current = sampleDirectionalCascade(cascade, n, l);
    if (cascade >= count - 1 || uDirectionalCascadeBlendRange <= 0.0) return current;
    float nearSplit = cascade == 0 ? 0.0 : uDirectionalCascadeSplits[cascade - 1];
    float blendWidth = max((uDirectionalCascadeSplits[cascade] - nearSplit)
            * uDirectionalCascadeBlendRange, 1.0e-4);
    float blendStart = uDirectionalCascadeSplits[cascade] - blendWidth;
    float weight = smoothstep(blendStart, uDirectionalCascadeSplits[cascade], vViewDepth);
    if (weight <= 1.0e-4) return current;
    float next = sampleDirectionalCascade(cascade + 1, n, l);
    return weight >= 0.9999 ? next : mix(current, next, weight);
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
    float visibility = index == uDirectionalShadowFrameLightIndex
            ? 1.0 - directionalShadowFactor(n, l) : 1.0;
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
}
