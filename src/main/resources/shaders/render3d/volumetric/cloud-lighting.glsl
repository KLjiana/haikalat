#define CLOUD_PI 3.14159265358979323846

// Normalized Henyey-Greenstein phase, the same convention and denominator floor
// as the volumetric-fog and particle media.
float cloudPhase(float anisotropy, float cosine) {
    float denominator = max(0.01, 1.0 + anisotropy * anisotropy - 2.0 * anisotropy * cosine);
    return (1.0 - anisotropy * anisotropy) / (4.0 * CLOUD_PI * denominator * sqrt(denominator));
}

// Bounded optical depth toward the light. The light travel direction is
// uLightDirection, so the direction toward the light is its negation. A light
// at or below the layer (toLight.y <= 0) contributes no direct in-scatter.
float cloudLightTau(vec3 samplePosition, vec3 toLight) {
    if (!(toLight.y > 1.0e-4)) {
        return CLOUD_MAX_OPTICAL_DEPTH;
    }
    float span = (uLayerTop - samplePosition.y) / toLight.y;
    span = clamp(span, 0.0, uMaximumLightDistance);
    if (!(span > 0.0)) {
        return 0.0;
    }
    float delta = span / float(uLightSteps);
    float opticalDepth = 0.0;
    for (int step = 0; step < uLightSteps; step++) {
        vec3 position = samplePosition + toLight * ((float(step) + 0.5) * delta);
        opticalDepth += cloudDensityAt(position, delta, toLight) * delta;
    }
    return opticalDepth;
}

// Reuse the measured light optical depth for two broader lobes with progressively
// weaker extinction. The 1-T factor gates this approximation on prior interactions;
// lobe weights sum to less than one and each extra order pays another albedo factor.
float cloudSunScatter(float tau, vec3 phase, float higherOrderWeight) {
    float direct = exp(-min(tau, CLOUD_MAX_OPTICAL_DEPTH));
    if (!(higherOrderWeight > 0.0)) {
        return phase.x * direct;
    }
    float redistribution = higherOrderWeight * (1.0 - direct);
    float broadTransmission = exp(-0.25 * tau);
    return phase.x * direct + redistribution * (
            0.5 * phase.y * broadTransmission * broadTransmission
            + 0.25 * redistribution * phase.z * broadTransmission);
}
