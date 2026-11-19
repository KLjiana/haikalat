#ifndef HAIKALAT_ATMOSPHERE_COMMON
#define HAIKALAT_ATMOSPHERE_COMMON

// Shared single-scattering helpers for the atmosphere LUTs.
//
// All distances in this file are normalized by the planet radius.  A normalized
// radius of 1.0 is the planet surface and 1.0 + thickness / radius is the top of
// the atmosphere.  Keeping geometry O(1) avoids the catastrophic cancellation
// that plain float arithmetic shows for 6.4e6 metre radii; the optical
// coefficients are likewise pre-multiplied by the planet radius by the Java
// pass so that density * thickness is the only remaining length term.

const float ATMOSPHERE_PI = 3.141592653589793;
const float ATMOSPHERE_PATH_EPSILON = 1.0e-6;

// Distance from a point inside the atmosphere to the top shell along a ray.
// radius is normalized, mu is the cosine between the ray and the local zenith.
float atmosphereDistanceToTop(float radius, float mu, float topRadius) {
    float discriminant = radius * radius * (mu * mu - 1.0) + topRadius * topRadius;
    if (discriminant <= 0.0) return 0.0;
    return -radius * mu + sqrt(discriminant);
}

// The transmittance LUT stores mu in [0, 1] only: the sun is treated as
// occluded whenever it is at or below the local horizontal.  This deliberately
// ignores the altitude horizon dip (documented limitation) and keeps the table
// free of interpolation with planet-intersecting rays.
vec2 atmosphereTransmittanceUv(float radius, float mu, float groundRadius, float topRadius) {
    float normalizedAltitude = (radius - groundRadius) / max(topRadius - groundRadius, 1.0e-6);
    return vec2(sqrt(clamp(normalizedAltitude, 0.0, 1.0)), clamp(mu, 0.0, 1.0));
}

float atmosphereRayleighPhase(float cosTheta) {
    return 3.0 / (16.0 * ATMOSPHERE_PI) * (1.0 + cosTheta * cosTheta);
}

// Henyey-Greenstein phase function; |g| is bounded below 1, so this stays
// positive and finite.
float atmosphereMiePhase(float cosTheta, float g) {
    float g2 = g * g;
    float denominator = max(1.0 + g2 - 2.0 * g * cosTheta, 1.0e-4);
    return (1.0 - g2) / (4.0 * ATMOSPHERE_PI * denominator * sqrt(denominator));
}

// Midpoint position and quadrature weight for a quadratic sample distribution
// that concentrates samples near the dense start of a ray.  Callers provide
// f in (0, 1); the returned distance is pathLength * f^2 and the weight is
// pathLength * 2f / sampleCount.
float atmosphereQuadraticDistance(float pathLength, float f) {
    return pathLength * f * f;
}

float atmosphereQuadraticWeight(float pathLength, float f, int sampleCount) {
    return pathLength * 2.0 * f / float(sampleCount);
}

#endif
