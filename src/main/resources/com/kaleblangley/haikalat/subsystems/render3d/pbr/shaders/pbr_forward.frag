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
layout(location = 0) out vec4 FragColor;

uniform sampler2D uBaseColorMap;
uniform sampler2D uNormalMap;
uniform sampler2D uMetallicRoughnessMap;
uniform sampler2D uOcclusionMap;
uniform sampler2D uEmissiveMap;
uniform sampler2D uShadowMap;
uniform samplerCube uIrradianceMap;
uniform samplerCube uPrefilteredMap;
uniform sampler2D uBrdfLut;

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
uniform int uEnableDirect;
uniform int uEnableDiffuseIbl;
uniform int uEnableSpecularIbl;
uniform int uEnableNormalMap;

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

float shadowFactor(vec3 n, vec3 l) {
    if (uHasDirectionalShadow == 0) return 0.0;
    vec3 projected = vDirectionalLightPosition.xyz / vDirectionalLightPosition.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || any(lessThanEqual(projected.xy, vec2(0.0)))
            || any(greaterThanEqual(projected.xy, vec2(1.0)))) return 0.0;
    float bias = max(uShadowBias * (1.0 - dot(n, l)), uShadowBias * 0.25);
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    float shadow = 0.0;
    for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) {
        float closest = texture(uShadowMap, projected.xy + vec2(x, y) * texel).r;
        shadow += projected.z - bias > closest ? 1.0 : 0.0;
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

void main() {
    vec4 baseSample = texture(uBaseColorMap, vTexCoord) * uBaseColorFactor;
    vec2 mr = texture(uMetallicRoughnessMap, vTexCoord).gb;
    float roughness = clamp(mr.x * uRoughnessFactor, 0.045, 1.0);
    float metallic = clamp(mr.y * uMetallicFactor, 0.0, 1.0);
    vec3 n = normalize(vNormal);
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
            float attenuation = clamp(1.0 - distanceToLight / uPointLights[i].range, 0.0, 1.0);
            attenuation *= attenuation;
            direct += directBrdf(n, v, l, uPointLights[i].color
                    * uPointLights[i].intensity * attenuation,
                    baseSample.rgb, metallic, roughness, f0);
        }
        for (int i = 0; i < uSpotLightCount; ++i) {
            vec3 delta = uSpotLights[i].position - vWorldPosition;
            float distanceToLight = length(delta);
            vec3 l = delta / max(distanceToLight, 1.0e-5);
            float attenuation = clamp(1.0 - distanceToLight / uSpotLights[i].range, 0.0, 1.0);
            attenuation *= attenuation;
            float angle = acos(clamp(dot(-l, normalize(uSpotLights[i].direction)), -1.0, 1.0));
            float cone = 1.0 - smoothstep(uSpotLights[i].innerCone, uSpotLights[i].outerCone, angle);
            direct += directBrdf(n, v, l, uSpotLights[i].color
                    * uSpotLights[i].intensity * attenuation * cone,
                    baseSample.rgb, metallic, roughness, f0);
        }
    }

    float nDotV = max(dot(n, v), 0.0);
    vec3 f = fresnelSchlickRoughness(nDotV, f0, roughness);
    vec3 kd = (1.0 - f) * (1.0 - metallic);
    vec3 rotatedN = rotateEnvironment(n);
    vec3 diffuseIbl = texture(uIrradianceMap, rotatedN).rgb * baseSample.rgb;
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
    indirect *= ao * uEnvironmentIntensity;
    vec3 emissive = texture(uEmissiveMap, vTexCoord).rgb * uEmissiveFactor;
    FragColor = vec4(max(direct + indirect + emissive, vec3(0.0)), baseSample.a);
}
