#version 460 core

in vec2 vUv;
out vec4 FragColor;

uniform sampler2D uScene;
uniform sampler2D uDepth;
uniform mat4 uInverseViewProjection;
uniform vec3 uCameraPosition;
uniform vec3 uFogColor;
uniform float uDistanceDensity;
uniform float uHeightDensity;
uniform float uHeightFalloff;
uniform float uBaseHeight;
uniform float uMaximumOpacity;

vec3 reconstructWorldPosition(float depth) {
    vec4 clip = vec4(vUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = uInverseViewProjection * clip;
    float safeW = abs(world.w) < 1.0e-7
            ? (world.w < 0.0 ? -1.0e-7 : 1.0e-7) : world.w;
    return world.xyz / safeW;
}

void main() {
    vec3 sceneColor = texture(uScene, vUv).rgb;
    float depth = texture(uDepth, vUv).r;
    vec3 worldPosition = reconstructWorldPosition(depth);
    vec3 ray = worldPosition - uCameraPosition;
    float distanceToCamera = length(ray);
    float heightDelta = worldPosition.y - uCameraPosition.y;
    float falloffDelta = uHeightFalloff * heightDelta;
    float cameraDensity = exp(clamp(
            -uHeightFalloff * (uCameraPosition.y - uBaseHeight), -80.0, 80.0));
    float averageHeightDensity = abs(falloffDelta) < 1.0e-5
            ? cameraDensity
            : cameraDensity * (1.0 - exp(-falloffDelta)) / falloffDelta;
    float opticalDepth = uDistanceDensity * distanceToCamera
            + uHeightDensity * distanceToCamera * averageHeightDensity;
    float fog = min(uMaximumOpacity, 1.0 - exp(-max(opticalDepth, 0.0)));
    FragColor = vec4(mix(sceneColor, uFogColor, fog), 1.0);
}
