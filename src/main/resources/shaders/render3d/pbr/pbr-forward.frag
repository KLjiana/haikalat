#version 460 core

struct DirectionalLight { vec3 direction; vec3 color; float intensity; };
struct PointLight { vec3 position; vec3 color; float intensity; float range; };
struct SpotLight {
    vec3 position; vec3 direction; vec3 color;
    float intensity; float range; float innerCone; float outerCone;
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
uniform float uEnvironmentIntensity;
uniform float uEnvironmentRotation;
uniform float uPrefilterMaxLod;
uniform vec3 uCameraPosition;
uniform int uDirectionalLightCount;
uniform int uPointLightCount;
uniform int uSpotLightCount;
uniform DirectionalLight uDirectionalLights[2];
uniform PointLight uPointLights[8];
uniform SpotLight uSpotLights[4];
uniform int uHasDirectionalShadow;
uniform int uDirectionalShadowLightIndex;
uniform float uShadowBias;
uniform int uDirectionalCascadeCount;
uniform float uDirectionalCascadeSplits[4];
uniform float uDirectionalCascadeBlendRange;
uniform int uHasPointShadow;
uniform int uPointShadowLightIndex;
uniform mat4 uPointShadowMatrices[6];
uniform float uPointShadowBias;
uniform int uHasSpotShadow;
uniform int uSpotShadowLightIndex;
uniform mat4 uSpotShadowMatrix;
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

float sampleDirectionalCascade(int cascade, vec3 n, vec3 l) {
    vec4 lightPosition = uDirectionalCascadeCount > 1
        ? vDirectionalCascadePosition[cascade] : vDirectionalLightPosition;
    vec3 projected = lightPosition.xyz / lightPosition.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || any(lessThanEqual(projected.xy, vec2(0.0)))
            || any(greaterThanEqual(projected.xy, vec2(1.0)))) return 0.0;
    float bias = max(uShadowBias * (1.0 - dot(n, l)), uShadowBias * 0.25);
    int columns = uDirectionalCascadeCount == 2 || uDirectionalCascadeCount > 2 ? 2 : 1;
    int rows = uDirectionalCascadeCount > 2 ? 2 : 1;
    vec2 scale = vec2(1.0 / float(columns), 1.0 / float(rows));
    vec2 offset = vec2(float(cascade % columns), float(cascade / columns)) * scale;
    projected.xy = projected.xy * scale + offset;
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    vec2 tileMinimum = offset + texel * 0.5;
    vec2 tileMaximum = offset + scale - texel * 0.5;
    float shadow = 0.0;
    for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) {
        vec2 sampleUv = clamp(projected.xy + vec2(x, y) * texel,
                tileMinimum, tileMaximum);
        float closest = texture(uShadowMap, sampleUv).r;
        shadow += projected.z - bias > closest ? 1.0 : 0.0;
    }
    return shadow / 9.0;
}

float shadowFactor(vec3 n, vec3 l) {
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
    return mix(current, sampleDirectionalCascade(cascade + 1, n, l), weight);
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

float pointShadowFactor(int lightIndex, vec3 n, vec3 l) {
    if (uHasPointShadow == 0 || lightIndex != uPointShadowLightIndex) return 0.0;
    vec3 fromLight = vWorldPosition - uPointLights[lightIndex].position;
    int face = pointShadowFace(fromLight);
    vec4 clip = uPointShadowMatrices[face] * vec4(vWorldPosition, 1.0);
    vec3 projected = clip.xyz / clip.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || any(lessThanEqual(projected.xy, vec2(0.0)))
            || any(greaterThanEqual(projected.xy, vec2(1.0)))) return 0.0;
    ivec2 tile = ivec2(face % 3, face / 3);
    vec2 grid = vec2(3.0, 2.0);
    vec2 atlasUv = (projected.xy + vec2(tile)) / grid;
    vec2 atlasTexel = 1.0 / vec2(textureSize(uPointShadowMap, 0));
    vec2 tileMinimum = vec2(tile) / grid + atlasTexel * 0.5;
    vec2 tileMaximum = vec2(tile + ivec2(1)) / grid - atlasTexel * 0.5;
    float bias = max(uPointShadowBias * (1.0 - dot(n, l)), uPointShadowBias * 0.25);
    float shadow = 0.0;
    for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) {
        vec2 sampleUv = clamp(atlasUv + vec2(x, y) * atlasTexel,
                tileMinimum, tileMaximum);
        shadow += projected.z - bias > texture(uPointShadowMap, sampleUv).r ? 1.0 : 0.0;
    }
    return shadow / 9.0;
}

float spotShadowFactor(int lightIndex, vec3 n, vec3 l) {
    if (uHasSpotShadow == 0 || lightIndex != uSpotShadowLightIndex) return 0.0;
    vec4 clip = uSpotShadowMatrix * vec4(vWorldPosition, 1.0);
    vec3 projected = clip.xyz / clip.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || any(lessThanEqual(projected.xy, vec2(0.0)))
            || any(greaterThanEqual(projected.xy, vec2(1.0)))) return 0.0;
    float bias = max(uSpotShadowBias * (1.0 - dot(n, l)), uSpotShadowBias * 0.25);
    vec2 texel = 1.0 / vec2(textureSize(uSpotShadowMap, 0));
    float shadow = 0.0;
    for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) {
        shadow += projected.z - bias
                > texture(uSpotShadowMap, projected.xy + vec2(x, y) * texel).r ? 1.0 : 0.0;
    }
    return shadow / 9.0;
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
    vec3 direct = vec3(0.0);
    if (uEnableDirect != 0) {
        for (int i = 0; i < uDirectionalLightCount; ++i) {
            vec3 l = normalize(-uDirectionalLights[i].direction);
            float visibility = i == uDirectionalShadowLightIndex ? 1.0 - shadowFactor(n, l) : 1.0;
            direct += visibility * directBrdf(n, v, l,
                    uDirectionalLights[i].color * uDirectionalLights[i].intensity,
                    baseSample.rgb, metallic, roughness, f0);
        }
        for (int i = 0; i < uPointLightCount; ++i) {
            vec3 delta = uPointLights[i].position - vWorldPosition;
            float distanceToLight = length(delta);
            vec3 l = delta / max(distanceToLight, 1.0e-5);
            float attenuation = rangeInverseSquareAttenuation(
                    distanceToLight, uPointLights[i].range);
            float visibility = 1.0 - pointShadowFactor(i, n, l);
            direct += visibility * directBrdf(n, v, l, uPointLights[i].color
                    * uPointLights[i].intensity * attenuation,
                    baseSample.rgb, metallic, roughness, f0);
        }
        for (int i = 0; i < uSpotLightCount; ++i) {
            vec3 delta = uSpotLights[i].position - vWorldPosition;
            float distanceToLight = length(delta);
            vec3 l = delta / max(distanceToLight, 1.0e-5);
            float attenuation = rangeInverseSquareAttenuation(
                    distanceToLight, uSpotLights[i].range);
            float angle = acos(clamp(dot(-l, normalize(uSpotLights[i].direction)), -1.0, 1.0));
            float cone = 1.0 - smoothstep(uSpotLights[i].innerCone, uSpotLights[i].outerCone, angle);
            float visibility = 1.0 - spotShadowFactor(i, n, l);
            direct += visibility * directBrdf(n, v, l, uSpotLights[i].color
                    * uSpotLights[i].intensity * attenuation * cone,
                    baseSample.rgb, metallic, roughness, f0);
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
