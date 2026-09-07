package com.kaleblangley.haikalat.subsystems.render3d;

/** Small GL-free reference functions used to validate the volume integration. */
final class OutdoorVolumeMath {
    private OutdoorVolumeMath() {
    }

    static float transmittance(float density, float distance) {
        requireFiniteNonNegative(density, "density");
        requireFiniteNonNegative(distance, "distance");
        return (float) Math.exp(-density * distance);
    }

    static float uniformSegmentScattering(float density, float distance) {
        requireFiniteNonNegative(density, "density");
        requireFiniteNonNegative(distance, "distance");
        if (density == 0.0f || distance == 0.0f) return 0.0f;
        return 1.0f - transmittance(density, distance);
    }

    static float henyeyGreenstein(float cosTheta, float anisotropy) {
        if (!Float.isFinite(cosTheta) || !Float.isFinite(anisotropy)
                || cosTheta < -1.0f || cosTheta > 1.0f
                || anisotropy <= -0.95f || anisotropy >= 0.95f) {
            throw new IllegalArgumentException("invalid phase function input");
        }
        double g = anisotropy;
        double denominator = 1.0 + g * g - 2.0 * g * cosTheta;
        return (float) ((1.0 - g * g) / (4.0 * Math.PI * Math.pow(denominator, 1.5)));
    }

    private static void requireFiniteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
