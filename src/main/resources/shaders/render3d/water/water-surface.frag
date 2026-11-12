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

#ifdef WATER_REFRACTION
uniform sampler2D uSceneColor;
uniform mat4 uProjection;
uniform float uRefractionIor;
uniform float uRefractionStrength;
uniform float uRefractionMaxOffset;
uniform float uRefractionThicknessScale;
uniform float uRefractionForegroundBias;
uniform vec3 uRefractionFallbackColor;
uniform int uRefractionFallbackMode;
#endif
const float TWO_PI = 6.283185307179586;
const float SECONDARY_FREQUENCY_SCALE = 1.7;
const float SECONDARY_WEIGHT = 0.5;
const float SECONDARY_PHASE = 1.3;
const float REFLECTION_F0 = 0.02;
const float GRAZING_EPSILON = 1.0e-3;
#ifdef WATER_REFRACTION
const float REFRACTION_MIN_DOWNWARD = 0.05;
#endif

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
#ifdef WATER_REFRACTION
vec2 projectWorldToUv(vec3 worldPosition) {
    vec4 clip = uProjection * uView * vec4(worldPosition, 1.0);
    if (!(clip.w > 1.0e-6)) return vec2(-1.0);
    return clip.xy / clip.w * 0.5 + 0.5;
}
#endif

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
#ifdef WATER_REFRACTION
    // Screen-space refraction: bend the camera-to-surface ray with the wave
    // normal, advance it by the scaled water thickness and sample the separate
    // single-sample background copy at the resulting screen position.  The
    // output replaces the copied background (alpha = 1) instead of blending the
    // unrefracted colour, so the shifted lookup is observable.  The shared
    // opaque depth at the shifted pixel rejects foreground occlusion: a lookup
    // whose scene point is closer than the water plane is not underwater.
    vec2 centreUv = (vec2(pixel) + 0.5) / vec2(extent);
    vec3 background = texture(uSceneColor, centreUv).rgb;
    bool fallback = false;

    vec3 refractionNormal = normal;
    if (dot(refractionNormal, viewDirection) > 0.0) refractionNormal = -refractionNormal;
    vec3 refracted = refract(viewDirection, refractionNormal, 1.0 / uRefractionIor);
    if (!(dot(refracted, refracted) > 1.0e-8)) {
        fallback = true;
    } else {
        float downward = max(-refracted.y, REFRACTION_MIN_DOWNWARD);
        float travel = max(thickness, 0.0) * uRefractionThicknessScale / downward;
        vec3 refractedWorld = vWorldPosition + refracted * travel;
        vec2 shiftedUv = projectWorldToUv(refractedWorld);
        vec2 offset = (shiftedUv - centreUv) * uRefractionStrength;
        float offsetLength = length(offset);
        if (!(offsetLength <= uRefractionMaxOffset)) {
            if (offsetLength > uRefractionMaxOffset) {
                offset *= uRefractionMaxOffset / offsetLength;
            } else {
                fallback = true;
            }
        }
        shiftedUv = centreUv + offset;
        if (shiftedUv.x < 0.0 || shiftedUv.x > 1.0
                || shiftedUv.y < 0.0 || shiftedUv.y > 1.0) {
            fallback = true;
        }
        if (!fallback) {
            ivec2 shiftedPixel = clamp(ivec2(floor(shiftedUv * vec2(extent))),
                    ivec2(0), extent - 1);
            float shiftedDepth = texelFetch(uSceneDepth, shiftedPixel, 0).r;
            if (shiftedDepth >= 1.0) {
                // No opaque surface at the lookup: there is no thickness or
                // foreground relationship to validate, so keep the fallback.
                fallback = true;
            } else {
                vec4 shiftedClip = vec4(shiftedUv * 2.0 - 1.0,
                        shiftedDepth * 2.0 - 1.0, 1.0);
                vec4 shiftedViewH = uInverseProjection * shiftedClip;
                if (!(abs(shiftedViewH.w) > 1.0e-6)) {
                    fallback = true;
                } else {
                    vec4 shiftedView = shiftedViewH / shiftedViewH.w;
                    vec3 shiftedWorld = (uInverseView * vec4(shiftedView.xyz, 1.0)).xyz;
                    vec3 shiftedRay = shiftedWorld - uCameraPosition;
                    float shiftedRayLength = length(shiftedRay);
                    float waterDistance = -1.0;
                    if (shiftedRayLength > 1.0e-6) {
                        vec3 shiftedDirection = shiftedRay / shiftedRayLength;
                        if (abs(shiftedDirection.y) > 1.0e-6) {
                            waterDistance = (uWaterLevel - uCameraPosition.y)
                                    / shiftedDirection.y;
                        }
                    }
                    if (!(waterDistance > 0.0)
                            || waterDistance - length(shiftedView.xyz)
                                    > uRefractionForegroundBias) {
                        fallback = true;
                    }
                }
            }
        }
        if (!fallback) {
            background = texture(uSceneColor, shiftedUv).rgb;
        }
    }
    if (fallback && uRefractionFallbackMode == 1) {
        background = uRefractionFallbackColor;
    }
    fragColor = vec4(mix(background, color, alpha), 1.0);
#else
    fragColor = vec4(color, alpha);
#endif
}
