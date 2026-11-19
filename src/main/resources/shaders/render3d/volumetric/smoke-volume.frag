#version 460 core
// Bounded volume-rendering consumer of the smoke simulation density field.
//
// The pass samples exactly one 3D density texture owned by SmokeSimulation and
// one resolved opaque scene-depth texture. It reconstructs the world ray from
// the inverse projection/view, intersects the grid's world-space AABB, marches
// the visible interval with a fixed target step and a hard step budget, and
// truncates the interval at the opaque surface. The medium is integrated once:
// there is no fog density/source sampler and no scene-colour sampler, so the
// volumetric-fog and particle media stay owned by their own passes. Output is
// premultiplied in-scatter plus coverage, blended over the copied HDR source:
//   OCCLUDING: FragColor.rgb + (1 - FragColor.a) * destination
//   ADDITIVE:  FragColor.rgb + destination
layout (binding = 0) uniform sampler3D uSmokeField;
layout (binding = 1) uniform sampler2D uSceneDepth;

uniform vec2 uViewportSize;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform vec3 uBoundsCenter;
uniform vec3 uBoundsHalfExtent;
uniform vec3 uLightDirection;
uniform vec3 uLightColor;
uniform float uDensityScale;
uniform float uAbsorption;
uniform float uScattering;
uniform float uAnisotropy;
uniform float uOpticalDepthScale;
uniform float uStepSizeMetres;
uniform int uMaximumSteps;
uniform int uOccluding;

layout (location = 0) out vec4 FragColor;

const float SMOKE_PI = 3.14159265358979323846;
const float SMOKE_MAXIMUM_OPTICAL_DEPTH = 88.0;

// Normalized Henyey-Greenstein phase, same convention and denominator floor as
// the volumetric-fog and particle media.
float smokePhase(float anisotropy, float cosine) {
    float denominator = max(0.01, 1.0 + anisotropy * anisotropy - 2.0 * anisotropy * cosine);
    return (1.0 - anisotropy * anisotropy) / (4.0 * SMOKE_PI * denominator * sqrt(denominator));
}

// Distance from the near-plane ray origin to the opaque surface. A cleared
// depth is "no occluder" and never truncates the smoke volume.
float smokeSurfaceDistance(ivec2 pixel, vec2 ndc, vec3 viewOrigin) {
    float rawDepth = texelFetch(uSceneDepth, pixel, 0).r;
    if (!(rawDepth < 1.0)) {
        return 1.0e30;
    }
    vec4 viewPosition = uInverseProjection * vec4(ndc, 2.0 * rawDepth - 1.0, 1.0);
    viewPosition.xyz /= viewPosition.w;
    return length(viewPosition.xyz - viewOrigin);
}

// Slab intersection of the world ray with the axis-aligned grid bounds.
// A zero direction component is handled without dividing by zero, so a camera
// exactly on a slab plane cannot produce a NaN. A miss produces first > second.
vec2 smokeBoxInterval(vec3 origin, vec3 direction) {
    vec3 minimum = uBoundsCenter - uBoundsHalfExtent;
    vec3 maximum = uBoundsCenter + uBoundsHalfExtent;
    float nearDistance = -1.0e30;
    float farDistance = 1.0e30;
    for (int axis = 0; axis < 3; axis++) {
        float axisOrigin = axis == 0 ? origin.x : (axis == 1 ? origin.y : origin.z);
        float axisDirection = axis == 0 ? direction.x
                : (axis == 1 ? direction.y : direction.z);
        float axisMinimum = axis == 0 ? minimum.x : (axis == 1 ? minimum.y : minimum.z);
        float axisMaximum = axis == 0 ? maximum.x : (axis == 1 ? maximum.y : maximum.z);
        if (abs(axisDirection) < 1.0e-9) {
            if (axisOrigin < axisMinimum || axisOrigin > axisMaximum) {
                return vec2(1.0, -1.0);
            }
        } else {
            float first = (axisMinimum - axisOrigin) / axisDirection;
            float second = (axisMaximum - axisOrigin) / axisDirection;
            nearDistance = max(nearDistance, min(first, second));
            farDistance = min(farDistance, max(first, second));
        }
    }
    return vec2(nearDistance, farDistance);
}

vec3 smokeUv(vec3 worldPosition) {
    vec3 minimum = uBoundsCenter - uBoundsHalfExtent;
    return clamp((worldPosition - minimum) / (2.0 * uBoundsHalfExtent), 0.0, 1.0);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec2 ndc = 2.0 * gl_FragCoord.xy / uViewportSize - 1.0;

    vec4 nearPosition = uInverseProjection * vec4(ndc, -1.0, 1.0);
    nearPosition.xyz /= nearPosition.w;
    vec4 farPosition = uInverseProjection * vec4(ndc, 1.0, 1.0);
    farPosition.xyz /= farPosition.w;
    vec3 viewOrigin = nearPosition.xyz;
    vec3 viewDirection = normalize(farPosition.xyz - viewOrigin);
    vec3 rayOrigin = (uInverseView * vec4(viewOrigin, 1.0)).xyz;
    vec3 rayDirection = normalize((uInverseView * vec4(viewDirection, 0.0)).xyz);

    vec2 box = smokeBoxInterval(rayOrigin, rayDirection);
    float intervalStart = max(box.x, 0.0);
    float intervalEnd = min(box.y, smokeSurfaceDistance(pixel, ndc, viewOrigin));
    if (!(intervalEnd > intervalStart)) {
        FragColor = vec4(0.0);
        return;
    }

    // Fixed target step with a hard per-pixel step budget: a long interval
    // widens the step instead of exceeding the budget.
    float span = intervalEnd - intervalStart;
    float stepLength = max(span / float(uMaximumSteps), uStepSizeMetres);
    int steps = int(clamp(ceil(span / stepLength), 1.0, float(uMaximumSteps)));
    float delta = span / float(steps);

    float coefficient = uDensityScale * (uAbsorption + uScattering);
    float albedo = (uAbsorption + uScattering) > 0.0
            ? uScattering / (uAbsorption + uScattering) : 0.0;
    float cosine = clamp(dot(uLightDirection, -rayDirection), -1.0, 1.0);
    float phase = smokePhase(uAnisotropy, cosine);

    vec3 scattering = vec3(0.0);
    float transmittance = 1.0;
    for (int step = 0; step < uMaximumSteps; step++) {
        if (step >= steps) {
            break;
        }
        vec3 samplePosition = rayOrigin + rayDirection
                * (intervalStart + (float(step) + 0.5) * delta);
        float density = texture(uSmokeField, smokeUv(samplePosition)).r;
        if (!(density >= 0.0)) {
            density = 0.0;
        }
        float sampleOpticalDepth = coefficient * density * delta * uOpticalDepthScale;
        float sampleTransmittance = exp(-min(sampleOpticalDepth, SMOKE_MAXIMUM_OPTICAL_DEPTH));
        scattering += transmittance * uLightColor
                * (phase * albedo * (1.0 - sampleTransmittance));
        transmittance *= sampleTransmittance;
    }

    FragColor = vec4(max(scattering, vec3(0.0)), uOccluding != 0 ? 1.0 - transmittance : 0.0);
}
