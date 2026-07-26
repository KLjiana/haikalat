#version 330 core

struct DirectionalLight {
    vec3 direction;
    vec3 color;
    float intensity;
};

struct PointLight {
    vec3 position;
    vec3 color;
    float intensity;
    float range;
};

struct SpotLight {
    vec3 position;
    vec3 direction;
    vec3 color;
    float intensity;
    float range;
    float innerCone;
    float outerCone;
};

in vec3 vColor;
in vec2 vTexCoord;
in vec3 vWorldPosition;
in vec3 vNormal;
in vec4 vDirectionalLightPosition;
out vec4 FragColor;

uniform int uUseTexture;
uniform sampler2D uTexture;
uniform vec3 uTint;
uniform int uDirectionalLightCount;
uniform int uPointLightCount;
uniform int uSpotLightCount;
uniform DirectionalLight uDirectionalLights[2];
uniform PointLight uPointLights[8];
uniform SpotLight uSpotLights[4];
uniform vec3 uCameraPosition;
uniform sampler2D uShadowMap;
uniform int uHasDirectionalShadow;
uniform int uDirectionalShadowLightIndex;
uniform float uShadowBias;
uniform sampler2D uPointShadowMap;
uniform int uHasPointShadow;
uniform int uPointShadowLightIndex;
uniform mat4 uPointShadowMatrices[6];
uniform float uPointShadowBias;
uniform sampler2D uSpotShadowMap;
uniform int uHasSpotShadow;
uniform int uSpotShadowLightIndex;
uniform mat4 uSpotShadowMatrix;
uniform float uSpotShadowBias;

float shadowFactor(vec3 normal, vec3 lightDirection) {
    if (uHasDirectionalShadow == 0) return 0.0;
    vec3 projected = vDirectionalLightPosition.xyz / vDirectionalLightPosition.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0) return 0.0;

    float bias = max(uShadowBias * (1.0 - dot(normal, lightDirection)), uShadowBias * 0.25);
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    float shadow = 0.0;
    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            float closest = texture(uShadowMap, projected.xy + vec2(x, y) * texel).r;
            shadow += projected.z - bias > closest ? 1.0 : 0.0;
        }
    }
    return shadow / 9.0;
}

int pointShadowFace(vec3 direction) {
    vec3 a = abs(direction);
    if (a.x >= a.y && a.x >= a.z) return direction.x >= 0.0 ? 0 : 1;
    if (a.y >= a.z) return direction.y >= 0.0 ? 2 : 3;
    return direction.z >= 0.0 ? 4 : 5;
}

float pointShadowFactor(int lightIndex, vec3 normal, vec3 lightDirection) {
    if (uHasPointShadow == 0 || lightIndex != uPointShadowLightIndex) return 0.0;
    int face = pointShadowFace(vWorldPosition - uPointLights[lightIndex].position);
    vec4 clip = uPointShadowMatrices[face] * vec4(vWorldPosition, 1.0);
    vec3 projected = clip.xyz / clip.w * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0) return 0.0;
    ivec2 tile = ivec2(face % 3, face / 3);
    vec2 grid = vec2(3.0, 2.0);
    vec2 uv = (projected.xy + vec2(tile)) / grid;
    vec2 atlasTexel = 1.0 / vec2(textureSize(uPointShadowMap, 0));
    vec2 tileMin = vec2(tile) / grid + atlasTexel * 0.5;
    vec2 tileMax = vec2(tile + ivec2(1)) / grid - atlasTexel * 0.5;
    float bias = max(uPointShadowBias * (1.0 - dot(normal, lightDirection)),
            uPointShadowBias * 0.25);
    float shadow = 0.0;
    for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) {
        vec2 sampleUv = clamp(uv + vec2(x, y) * atlasTexel, tileMin, tileMax);
        shadow += projected.z - bias > texture(uPointShadowMap, sampleUv).r ? 1.0 : 0.0;
    }
    return shadow / 9.0;
}

float spotShadowFactor(int lightIndex, vec3 normal, vec3 lightDirection) {
    if (uHasSpotShadow == 0 || lightIndex != uSpotShadowLightIndex) return 0.0;
    vec4 clip = uSpotShadowMatrix * vec4(vWorldPosition, 1.0);
    vec3 projected = clip.xyz / clip.w * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0) return 0.0;
    float bias = max(uSpotShadowBias * (1.0 - dot(normal, lightDirection)),
            uSpotShadowBias * 0.25);
    vec2 texel = 1.0 / vec2(textureSize(uSpotShadowMap, 0));
    float shadow = 0.0;
    for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) {
        shadow += projected.z - bias
                > texture(uSpotShadowMap, projected.xy + vec2(x, y) * texel).r ? 1.0 : 0.0;
    }
    return shadow / 9.0;
}

vec3 shade(vec3 baseColor) {
    vec3 normal = normalize(vNormal);
    vec3 viewDirection = normalize(uCameraPosition - vWorldPosition);
    vec3 result = baseColor * 0.12;

    for (int i = 0; i < uDirectionalLightCount; ++i) {
        vec3 lightDirection = normalize(-uDirectionalLights[i].direction);
        float diffuse = max(dot(normal, lightDirection), 0.0);
        vec3 halfwayDirection = normalize(lightDirection + viewDirection);
        float specular = pow(max(dot(normal, halfwayDirection), 0.0), 32.0) * 0.25;
        float visibility = i == uDirectionalShadowLightIndex
                ? 1.0 - shadowFactor(normal, lightDirection) : 1.0;
        result += visibility * (baseColor * diffuse + vec3(specular))
                * uDirectionalLights[i].color * uDirectionalLights[i].intensity;
    }

    for (int i = 0; i < uPointLightCount; ++i) {
        vec3 toLight = uPointLights[i].position - vWorldPosition;
        float distanceToLight = length(toLight);
        vec3 lightDirection = toLight / max(distanceToLight, 0.0001);
        float attenuation = clamp(1.0 - distanceToLight / uPointLights[i].range, 0.0, 1.0);
        attenuation *= attenuation;
        float diffuse = max(dot(normal, lightDirection), 0.0);
        vec3 halfwayDirection = normalize(lightDirection + viewDirection);
        float specular = pow(max(dot(normal, halfwayDirection), 0.0), 32.0) * 0.25;
        float visibility = 1.0 - pointShadowFactor(i, normal, lightDirection);
        result += visibility * attenuation * (baseColor * diffuse + vec3(specular))
                * uPointLights[i].color * uPointLights[i].intensity;
    }

    for (int i = 0; i < uSpotLightCount; ++i) {
        vec3 toLight = uSpotLights[i].position - vWorldPosition;
        float distanceToLight = length(toLight);
        vec3 lightDirection = toLight / max(distanceToLight, 0.0001);
        float attenuation = clamp(1.0 - distanceToLight / uSpotLights[i].range, 0.0, 1.0);
        attenuation *= attenuation;
        float angle = acos(clamp(dot(-lightDirection, normalize(uSpotLights[i].direction)), -1.0, 1.0));
        float cone = 1.0 - smoothstep(uSpotLights[i].innerCone, uSpotLights[i].outerCone, angle);
        float diffuse = max(dot(normal, lightDirection), 0.0);
        vec3 halfwayDirection = normalize(lightDirection + viewDirection);
        float specular = pow(max(dot(normal, halfwayDirection), 0.0), 32.0) * 0.25;
        float visibility = 1.0 - spotShadowFactor(i, normal, lightDirection);
        result += visibility * attenuation * cone * (baseColor * diffuse + vec3(specular))
                * uSpotLights[i].color * uSpotLights[i].intensity;
    }
    return result;
}

void main() {
    vec4 base = uUseTexture != 0
            ? texture(uTexture, vTexCoord) * vec4(uTint, 1.0)
            : vec4(vColor, 1.0);
    FragColor = vec4(shade(base.rgb), base.a);
}
