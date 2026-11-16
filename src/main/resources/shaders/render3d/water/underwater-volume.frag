#version 460 core

// Bounded underwater volume pass (#11/#30 extension).
//
// ARTISTIC, SCREEN-SPACE APPROXIMATION, NOT PARTICIPATING MEDIA. For a camera
// strictly below the water plane the fragment reconstructs the world position
// of the visible surface from the single-sample scene depth and computes
//
//     path        = clamp(length(world - camera), 0, uMaximumDistance)
//     attenuation = exp(-clamp(uAbsorptionDensity, 0, 8) * path)
//     output.rgb  = scene.rgb * attenuation + uScatterColor * (1 - attenuation)
//
// This is a Beer-Lambert path attenuation plus a scatter tint. There is no
// dispersion, no multi-layer water, no wave displacement of the entry plane and
// no absorption between the camera and the first visible surface beyond the
// reconstructed depth. Only the visible depth buffer can be attenuated.
//
// A camera at or above the water plane keeps the scene colour unchanged. The
// production executor records only the colour blit on that path, so the frame
// stays byte-identical to the disabled baseline while the graph topology stays
// stable; the uniform branch below keeps the shader correct on its own.
//
// Every division is guarded, the density and path are clamped and the exponent
// is bounded, so the output is finite for every finite input. The pass never
// writes depth and never samples the colour target it writes.

uniform sampler2D uSceneColor;
uniform sampler2D uSceneDepth;
uniform mat4 uInverseViewProjection;
uniform vec3 uCameraPosition;
uniform vec2 uViewportSize;
uniform float uWaterLevel;
uniform float uAbsorptionDensity;
uniform vec3 uScatterColor;
uniform float uMaximumDistance;

layout(location = 0) out vec4 FragColor;

const float MAXIMUM_DENSITY = 8.0;
const float MINIMUM_DISTANCE_METRES = 0.1;
const float MAXIMUM_DISTANCE_METRES = 500.0;
const float MAXIMUM_COLOR_COMPONENT = 16.0;
const float MINIMUM_PATH_EXPONENT = -80.0;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 scene = texelFetch(uSceneColor, pixel, 0);

    if (!(uCameraPosition.y < uWaterLevel)) {
        FragColor = scene;
        return;
    }
    if (!(uViewportSize.x > 0.0) || !(uViewportSize.y > 0.0)) {
        FragColor = scene;
        return;
    }

    float depth = texelFetch(uSceneDepth, pixel, 0).r;
    if (!(depth >= 0.0) || !(depth <= 1.0)) {
        FragColor = scene;
        return;
    }

    vec2 uv = gl_FragCoord.xy / uViewportSize;
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldHomogeneous = uInverseViewProjection * clip;
    if (!(worldHomogeneous.w > 1.0e-6)) {
        FragColor = scene;
        return;
    }
    vec3 world = worldHomogeneous.xyz / worldHomogeneous.w;

    float path = length(world - uCameraPosition);
    if (!(path >= 0.0)) {
        FragColor = scene;
        return;
    }
    float maximumDistance = clamp(uMaximumDistance, MINIMUM_DISTANCE_METRES,
            MAXIMUM_DISTANCE_METRES);
    path = min(path, maximumDistance);
    float density = clamp(uAbsorptionDensity, 0.0, MAXIMUM_DENSITY);

    // exp(-80) is ~1.8e-35: finite and far below display precision, which keeps
    // the multiply-add away from underflow-to-zero noise.
    float exponent = max(-density * path, MINIMUM_PATH_EXPONENT);
    float attenuation = exp(exponent);
    vec3 scatter = clamp(uScatterColor, 0.0, MAXIMUM_COLOR_COMPONENT);
    vec3 tinted = scene.rgb * attenuation + scatter * (1.0 - attenuation);
    if (any(isnan(tinted)) || any(isinf(tinted))) {
        FragColor = scene;
        return;
    }
    FragColor = vec4(tinted, scene.a);
}
