#version 460 core

layout(location = 0) in vec3 vWorldPosition;
layout(location = 0) out vec4 fragColor;

uniform mat4 uInverseProjection;
uniform mat4 uView;
uniform mat4 uInverseView;
uniform vec3 uCameraPosition;
uniform float uFarPlane;

#ifdef WATER_MSAA
uniform sampler2DMS uSceneDepth;
#else
uniform sampler2D uSceneDepth;
#endif

uniform int uHasEnvironment;
uniform samplerCube uEnvironment;
uniform float uEnvironmentIntensity;
uniform float uEnvironmentRotation;
// Optional planar-reflection colour target. When present it replaces the
// environment reflection at the current screen position; off-screen lookups
// fall back to the environment so the surface keeps a stable colour.
uniform int uHasPlanarReflection;
uniform sampler2D uPlanarReflection;
uniform mat4 uViewProjection;

uniform vec3 uWaterColor;
uniform vec3 uReflectionFallback;
uniform float uAbsorptionDensity;
uniform float uShoreFadeDistance;
uniform float uWaterLevel;
uniform float uWaveWavelength;
uniform float uWaveSpeed;
uniform float uWaveAmplitude;
uniform float uWaveDirection;
uniform float uTimeSeconds;
uniform int uDebugMode;

const float TWO_PI = 6.283185307179586;
const float SECONDARY_FREQUENCY_SCALE = 1.7;
const float SECONDARY_WEIGHT = 0.5;
const float SECONDARY_PHASE = 1.3;
const float REFLECTION_F0 = 0.02;
const float GRAZING_EPSILON = 1.0e-3;

vec3 rotateEnvironment(vec3 direction) {
    float c = cos(uEnvironmentRotation);
    float s = sin(uEnvironmentRotation);
    return vec3(c * direction.x - s * direction.z, direction.y,
                s * direction.x + c * direction.z);
}

// CPU counterpart: WaterSurfaceSettings.waveNormal(). Keep the constants and
// the two-train phase formula in sync; both are part of the public contract.
vec3 waveNormal(vec2 position, float timeSeconds) {
    float k1 = TWO_PI / max(uWaveWavelength, 1.0e-4);
    vec2 d1 = vec2(cos(uWaveDirection), sin(uWaveDirection));
    vec2 d2 = vec2(-d1.y, d1.x);
    float k2 = k1 * SECONDARY_FREQUENCY_SCALE;
    float omega1 = k1 * uWaveSpeed;
    float omega2 = k2 * uWaveSpeed;
    float phase1 = k1 * dot(d1, position) - omega1 * timeSeconds;
    float phase2 = k2 * dot(d2, position) - omega2 * timeSeconds + SECONDARY_PHASE;
    vec2 gradient = uWaveAmplitude
            * (k1 * d1 * cos(phase1) + SECONDARY_WEIGHT * k2 * d2 * cos(phase2));
    return normalize(vec3(-gradient.x, 1.0, -gradient.y));
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
#ifdef WATER_MSAA
    ivec2 extent = textureSize(uSceneDepth);
    float deviceDepth = texelFetch(uSceneDepth, pixel, 0).r;
#else
    ivec2 extent = textureSize(uSceneDepth, 0);
    float deviceDepth = texelFetch(uSceneDepth, pixel, 0).r;
#endif
    vec2 ndc = (vec2(pixel) + 0.5) / vec2(extent) * 2.0 - 1.0;

    vec4 waterView = uView * vec4(vWorldPosition, 1.0);
    float waterDepth = -waterView.z;
    if (!(waterDepth > 0.0) || waterDepth > uFarPlane) discard;

    // Manual occlusion test: the pass has no depth attachment because it must
    // sample the shared scene depth. A surface behind the water plane is treated
    // as deep water; a surface in front of the plane keeps the water hidden.
    float sceneY;
    if (deviceDepth >= 1.0) {
        sceneY = uWaterLevel - uFarPlane;
    } else {
        vec4 sceneClip = vec4(ndc, deviceDepth * 2.0 - 1.0, 1.0);
        vec4 sceneViewH = uInverseProjection * sceneClip;
        if (!(abs(sceneViewH.w) > 1.0e-6)) discard;
        vec4 sceneView = sceneViewH / sceneViewH.w;
        float sceneDepth = -sceneView.z;
        float epsilon = max(1.0e-4, waterDepth * 1.0e-4);
        if (sceneDepth + epsilon < waterDepth) discard;
        sceneY = (uInverseView * vec4(sceneView.xyz, 1.0)).y;
    }

    float thickness = uWaterLevel - sceneY;
    if (!(thickness > 0.0)) discard;

    vec3 normal = waveNormal(vWorldPosition.xz, uTimeSeconds);
    if (uDebugMode == 1) {
        fragColor = vec4(normal * 0.5 + 0.5, 1.0);
        return;
    }

    // Grazing/intersection limit: a view ray almost parallel to the plane has no
    // stable intersection; the pixel is left at the copied background instead of
    // extrapolating an unbounded Fresnel denominator.
    vec3 viewDirection = normalize(vWorldPosition - uCameraPosition);
    if (abs(viewDirection.y) < GRAZING_EPSILON) discard;

    float nDotV = clamp(dot(normal, -viewDirection), 0.0, 1.0);
    float fresnel = REFLECTION_F0 + (1.0 - REFLECTION_F0) * pow(1.0 - nDotV, 5.0);
    vec3 reflection = uReflectionFallback;
    bool planarSampled = false;
    if (uHasPlanarReflection == 1) {
        vec4 planarClip = uViewProjection * vec4(vWorldPosition, 1.0);
        if (planarClip.w > 0.0) {
            vec2 planarUv = planarClip.xy / planarClip.w * 0.5 + 0.5;
            if (all(greaterThanEqual(planarUv, vec2(0.0)))
                    && all(lessThanEqual(planarUv, vec2(1.0)))) {
                reflection = texture(uPlanarReflection, planarUv).rgb;
                planarSampled = true;
            }
        }
    }
    if (!planarSampled && uHasEnvironment == 1) {
        reflection = rotateEnvironment(reflect(viewDirection, normal));
        reflection = textureLod(uEnvironment, reflection, 0.0).rgb * uEnvironmentIntensity;
    }

    float absorb = 1.0 - exp(-uAbsorptionDensity * thickness);
    float shore = uShoreFadeDistance <= 0.0 ? 1.0
            : smoothstep(0.0, uShoreFadeDistance, thickness);
    if (!(shore > 0.0)) discard;

    // Alpha blends the shaded water over the copied scene colour. At grazing
    // incidence fresnel approaches one, so the reflection dominates; below the
    // surface the absorption fraction approaches one, so the deep colour wins.
    vec3 color = mix(uWaterColor, reflection, fresnel);
    float alpha = clamp(shore * mix(absorb, 1.0, fresnel), 0.0, 1.0);
    fragColor = vec4(color, alpha);
}
