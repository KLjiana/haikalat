#version 460 core
// Bounded local analytic volumetric-particle medium.
//
// This program integrates exactly one medium: the ellipsoidal particle density
// described by the uniforms below. It deliberately never samples the scene
// colour or any volumetric-fog density/source texture, so the volumetric-fog
// medium is integrated once by its own pass and the particle medium once by
// this pass. The pass copies the (fog-composited) HDR source into its own
// target and blends the particle contribution over it:
//   OCCLUDING: FragColor.rgb + (1 - FragColor.a) * destination
//   ADDITIVE:  FragColor.rgb + destination
layout (binding = 0) uniform sampler2D uSceneDepth;

uniform vec2 uViewportSize;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform vec3 uBoundsCenter;
uniform vec3 uBoundsHalfExtent;
uniform vec3 uLightDirection;
uniform vec3 uLightColor;
uniform float uDensity;
uniform float uAbsorption;
uniform float uScattering;
uniform float uAnisotropy;
uniform float uOpticalDepthScale;
uniform float uStepSizeMetres;
uniform int uMaximumSteps;
uniform int uOccluding;

layout (location = 0) out vec4 FragColor;

const float PARTICLE_PI = 3.14159265358979323846;

// Quartic ellipsoid falloff: 1 at the center, 0 on the boundary, C1 at the
// boundary. The bounding box of the ellipsoid is the declared AABB.
float particleDensityWeight(vec3 worldPosition) {
    vec3 scaled = (worldPosition - uBoundsCenter) / uBoundsHalfExtent;
    float radiusSquared = dot(scaled, scaled);
    if (radiusSquared >= 1.0) {
        return 0.0;
    }
    float falloff = 1.0 - radiusSquared;
    return falloff * falloff;
}

// Normalized Henyey-Greenstein phase, same convention and floor as the
// volumetric-fog medium so both media use one phase definition.
float particlePhase(float anisotropy, float cosine) {
    float denominator = max(0.01, 1.0 + anisotropy * anisotropy - 2.0 * anisotropy * cosine);
    return (1.0 - anisotropy * anisotropy) / (4.0 * PARTICLE_PI * denominator * sqrt(denominator));
}

// Distance from the near-plane ray origin to the opaque surface. A cleared
// depth is "no occluder" and never truncates the local medium.
float particleSurfaceDistance(ivec2 pixel, vec2 ndc, vec3 viewOrigin) {
    float rawDepth = texelFetch(uSceneDepth, pixel, 0).r;
    if (!(rawDepth < 1.0)) {
        return 1.0e30;
    }
    vec4 viewPosition = uInverseProjection * vec4(ndc, 2.0 * rawDepth - 1.0, 1.0);
    viewPosition.xyz /= viewPosition.w;
    return length(viewPosition.xyz - viewOrigin);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec2 ndc = 2.0 * gl_FragCoord.xy / uViewportSize - 1.0;

    // Reconstruct the same world ray for perspective and orthographic
    // projections: the near-plane point is the origin, far - near the
    // direction. Both are transformed by the rigid inverse view.
    vec4 nearPosition = uInverseProjection * vec4(ndc, -1.0, 1.0);
    nearPosition.xyz /= nearPosition.w;
    vec4 farPosition = uInverseProjection * vec4(ndc, 1.0, 1.0);
    farPosition.xyz /= farPosition.w;
    vec3 viewOrigin = nearPosition.xyz;
    vec3 viewDirection = normalize(farPosition.xyz - viewOrigin);
    vec3 rayOrigin = (uInverseView * vec4(viewOrigin, 1.0)).xyz;
    vec3 rayDirection = normalize((uInverseView * vec4(viewDirection, 0.0)).xyz);

    // Ray/ellipsoid intersection in the normalized body space.
    vec3 scaledOrigin = (rayOrigin - uBoundsCenter) / uBoundsHalfExtent;
    vec3 scaledDirection = rayDirection / uBoundsHalfExtent;
    float a = dot(scaledDirection, scaledDirection);
    float b = 2.0 * dot(scaledOrigin, scaledDirection);
    float c = dot(scaledOrigin, scaledOrigin) - 1.0;
    float discriminant = b * b - 4.0 * a * c;
    if (!(a > 0.0) || !(discriminant > 0.0)) {
        FragColor = vec4(0.0);
        return;
    }
    float root = sqrt(discriminant);
    float entry = (-b - root) / (2.0 * a);
    float exit = (-b + root) / (2.0 * a);
    float intervalStart = max(entry, 0.0);
    float intervalEnd = min(exit, particleSurfaceDistance(pixel, ndc, viewOrigin));
    if (!(intervalEnd > intervalStart)) {
        FragColor = vec4(0.0);
        return;
    }

    // Fixed target step with a hard per-pixel step budget. A long interval
    // widens the step instead of exceeding the budget.
    float span = intervalEnd - intervalStart;
    float stepLength = max(span / float(uMaximumSteps), uStepSizeMetres);
    int steps = int(clamp(ceil(span / stepLength), 1.0, float(uMaximumSteps)));
    float delta = span / float(steps);

    float extinction = uDensity * (uAbsorption + uScattering);
    float opticalDepth = 0.0;
    for (int step = 0; step < uMaximumSteps; step++) {
        if (step >= steps) {
            break;
        }
        vec3 samplePosition = rayOrigin + rayDirection * (intervalStart + (float(step) + 0.5) * delta);
        opticalDepth += extinction * particleDensityWeight(samplePosition) * delta;
    }
    // Artistic blend weight against the existing volumetric fog: it scales the
    // particle optical depth exactly once, so 0 is an exact no-op and the
    // in-scatter stays energy-consistent with the transmittance.
    opticalDepth *= uOpticalDepthScale;
    float transmittance = exp(-min(opticalDepth, 88.0));

    float albedo = extinction > 0.0 ? (uDensity * uScattering) / extinction : 0.0;
    float cosine = clamp(dot(uLightDirection, -rayDirection), -1.0, 1.0);
    float phase = particlePhase(uAnisotropy, cosine);
    vec3 scattering = uLightColor * (phase * albedo * (1.0 - transmittance));
    float coverage = 1.0 - transmittance;
    FragColor = vec4(max(scattering, vec3(0.0)), uOccluding != 0 ? coverage : 0.0);
}
