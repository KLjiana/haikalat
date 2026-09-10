#version 460 core

// Demo/legacy-material lit shader migrated to the unified clustered-forward
// light table.  It keeps the simplified Phong look used by the LearnOpenGL
// demo scenes while reading the same buffers as the production PBR shader.

#define POINT_SHADOW_FACE_COUNT 6

struct LightRecord {
    vec4 positionRange;
    vec4 directionOuter;
    vec4 colorIntensity;
    vec4 extra;
    ivec4 metadata;
};

layout(std430, binding = 0) readonly buffer LightTableBlock {
    uvec4 uLightHeader;
    LightRecord uLights[];
};

layout(std430, binding = 2) readonly buffer ClusterHeadersBlock {
    uvec4 uClusterHeaders[];
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
    ivec4 uGridParams;
    vec4 uDepthParams;
    ivec4 uLightCounts;
    vec4 uEdgeExpand;
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
uniform vec3 uCameraPosition;
uniform sampler2D uShadowMap;
uniform int uHasDirectionalShadow;
uniform int uDirectionalShadowFrameLightIndex;
uniform float uShadowBias;
uniform sampler2D uPointShadowMap;
uniform int uHasPointShadow;
uniform sampler2D uSpotShadowMap;
uniform int uHasSpotShadow;

float directionalShadowFactor(vec3 normal, vec3 lightDirection) {
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

float pointShadowFactor(int slot, vec3 lightPosition, vec3 normal, vec3 lightDirection) {
    if (uHasPointShadow == 0 || uPointShadowMeta[slot].y == 0) return 0.0;
    int face = pointShadowFace(vWorldPosition - lightPosition);
    int matrixIndex = slot * POINT_SHADOW_FACE_COUNT + face;
    vec4 clip = uPointSlotMatrices[matrixIndex] * vec4(vWorldPosition, 1.0);
    vec3 projected = clip.xyz / clip.w * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0) return 0.0;
    vec4 rect = uPointFaceRects[matrixIndex];
    vec2 uv = mix(rect.xy, rect.zw, projected.xy);
    vec2 atlasTexel = 1.0 / vec2(textureSize(uPointShadowMap, 0));
    float bias = max(intBitsToFloat(uShadowQualityMeta.z)
            * (1.0 - dot(normal, lightDirection)), intBitsToFloat(uShadowQualityMeta.z) * 0.25);
    float shadow = 0.0;
    for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) {
        shadow += projected.z - bias > texture(uPointShadowMap,
                uv + vec2(x, y) * atlasTexel).r ? 1.0 : 0.0;
    }
    return shadow / 9.0;
}

float spotShadowFactor(int slot, vec3 normal, vec3 lightDirection) {
    if (uHasSpotShadow == 0 || uSpotShadowMeta[slot].y == 0) return 0.0;
    vec4 clip = uSpotSlotMatrices[slot] * vec4(vWorldPosition, 1.0);
    vec3 projected = clip.xyz / clip.w * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0
            || projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0) return 0.0;
    vec4 rect = uSpotTileRects[slot];
    vec2 uv = mix(rect.xy, rect.zw, projected.xy);
    float bias = max(intBitsToFloat(uShadowQualityMeta.w)
            * (1.0 - dot(normal, lightDirection)), intBitsToFloat(uShadowQualityMeta.w) * 0.25);
    vec2 texel = 1.0 / vec2(textureSize(uSpotShadowMap, 0));
    float shadow = 0.0;
    for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) {
        shadow += projected.z - bias
                > texture(uSpotShadowMap, uv + vec2(x, y) * texel).r ? 1.0 : 0.0;
    }
    return shadow / 9.0;
}

vec3 shadeDirectional(int index, vec3 normal, vec3 viewDirection, vec3 baseColor) {
    LightRecord light = uLights[index];
    vec3 lightDirection = normalize(-light.directionOuter.xyz);
    float diffuse = max(dot(normal, lightDirection), 0.0);
    vec3 halfwayDirection = normalize(lightDirection + viewDirection);
    float specular = pow(max(dot(normal, halfwayDirection), 0.0), 32.0) * 0.25;
    float visibility = int(index) == uDirectionalShadowFrameLightIndex
            ? 1.0 - directionalShadowFactor(normal, lightDirection) : 1.0;
    return visibility * (baseColor * diffuse + vec3(specular))
            * light.colorIntensity.rgb * light.colorIntensity.a;
}

vec3 shadeLocal(int index, vec3 normal, vec3 viewDirection, vec3 baseColor) {
    LightRecord light = uLights[index];
    vec3 lightPosition = light.positionRange.xyz;
    vec3 toLight = lightPosition - vWorldPosition;
    float distanceToLight = length(toLight);
    vec3 lightDirection = toLight / max(distanceToLight, 0.0001);
    float attenuation = clamp(1.0 - distanceToLight / light.positionRange.w, 0.0, 1.0);
    attenuation *= attenuation;
    float cone = 1.0;
    if (light.metadata.x == 2) {
        float angle = acos(clamp(dot(-lightDirection,
                normalize(light.directionOuter.xyz)), -1.0, 1.0));
        cone = 1.0 - smoothstep(light.extra.x, light.directionOuter.w, angle);
    }
    int slot = light.metadata.y;
    float visibility = 1.0;
    if (slot >= 0) {
        visibility = 1.0 - (light.metadata.x == 1
                ? pointShadowFactor(slot, lightPosition, normal, lightDirection)
                : spotShadowFactor(slot, normal, lightDirection));
    }
    float diffuse = max(dot(normal, lightDirection), 0.0);
    vec3 halfwayDirection = normalize(lightDirection + viewDirection);
    float specular = pow(max(dot(normal, halfwayDirection), 0.0), 32.0) * 0.25;
    return visibility * attenuation * cone * (baseColor * diffuse + vec3(specular))
            * light.colorIntensity.rgb * light.colorIntensity.a;
}

int resolveCluster() {
    vec4 clip = uStableViewProjection * vec4(vWorldPosition, 1.0);
    if (clip.w <= 0.0) return -1;
    float depth = -(uStableView * vec4(vWorldPosition, 1.0)).z;
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

vec3 shade(vec3 baseColor) {
    vec3 normal = normalize(vNormal);
    vec3 viewDirection = normalize(uCameraPosition - vWorldPosition);
    vec3 result = baseColor * 0.12;
    uint directionalCount = uLightHeader.x;
    uint localCount = uLightHeader.y;
    for (uint i = 0u; i < directionalCount; i++) {
        result += shadeDirectional(int(i), normal, viewDirection, baseColor);
    }
    int cluster = localCount > 0u ? resolveCluster() : -1;
    if (cluster >= 0) {
        uvec4 header = uClusterHeaders[cluster];
        if (header.z != 0u) {
            for (uint i = 0u; i < localCount; i++) {
                result += shadeLocal(int(directionalCount + i), normal, viewDirection, baseColor);
            }
        } else {
            for (uint k = 0u; k < header.y; k++) {
                result += shadeLocal(int(uClusterIndices[header.x + k]), normal,
                        viewDirection, baseColor);
            }
        }
    }
    return result;
}

void main() {
    vec4 base = uUseTexture != 0
            ? texture(uTexture, vTexCoord) * vec4(uTint, 1.0)
            : vec4(vColor, 1.0);
    FragColor = vec4(shade(base.rgb), base.a);
}
