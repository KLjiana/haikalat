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

in vec2 vTexCoord;
in vec3 vWorldPosition;
in vec3 vNormal;
in vec4 vDirectionalLightPosition;
out vec4 FragColor;

uniform sampler2D uTexture;
uniform vec3 uTint;
uniform int uDirectionalLightCount;
uniform int uPointLightCount;
uniform DirectionalLight uDirectionalLights[2];
uniform PointLight uPointLights[8];
uniform vec3 uCameraPosition;
uniform sampler2D uShadowMap;
uniform int uHasDirectionalShadow;
uniform float uShadowBias;

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

vec3 shade(vec3 baseColor) {
    vec3 normal = normalize(vNormal);
    vec3 viewDirection = normalize(uCameraPosition - vWorldPosition);
    vec3 result = baseColor * 0.12;

    for (int i = 0; i < uDirectionalLightCount; ++i) {
        vec3 lightDirection = normalize(-uDirectionalLights[i].direction);
        float diffuse = max(dot(normal, lightDirection), 0.0);
        vec3 halfwayDirection = normalize(lightDirection + viewDirection);
        float specular = pow(max(dot(normal, halfwayDirection), 0.0), 32.0) * 0.25;
        float visibility = i == 0 ? 1.0 - shadowFactor(normal, lightDirection) : 1.0;
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
        result += attenuation * (baseColor * diffuse + vec3(specular))
                * uPointLights[i].color * uPointLights[i].intensity;
    }
    return result;
}

void main() {
    vec4 texel = texture(uTexture, vTexCoord) * vec4(uTint, 1.0);
    FragColor = vec4(shade(texel.rgb), texel.a);
}
