package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-JVM reference for a rotationally symmetric horizon cap. This special
 * case is not a general screen-space slice integral; production projection
 * and integration are checked against angular quadrature in
 * GtaoReconstructionGlTest.
 */
class GtaoAnalyticReferenceTest {
    private static final int REFERENCE_SAMPLES = 65_536;

    @Test
    void noHorizonHasNoOcclusion() {
        assertEquals(0.0, cosineWeightedHorizonOcclusion(0.0), 1.0e-12);
    }

    @Test
    void symmetricThirtyDegreeHorizonUsesSineSquaredIntegral() {
        double horizonSine = Math.sin(Math.toRadians(30.0));
        assertEquals(0.25, cosineWeightedHorizonOcclusion(horizonSine), 1.0e-12);
    }

    @Test
    void deterministicCosineWeightedHemisphereMatchesAnalyticReference() {
        double horizonSine = Math.sin(Math.toRadians(30.0));
        double expected = cosineWeightedHorizonOcclusion(horizonSine);
        double measured = cosineWeightedHemisphereOcclusion(
                horizonSine, REFERENCE_SAMPLES, 0.0);
        assertEquals(expected, measured, 2.0e-4);
    }

    @Test
    void rotationallySymmetricReferenceIsIndependentOfAzimuthPhase() {
        double horizonSine = Math.sin(Math.toRadians(30.0));
        double expected = cosineWeightedHorizonOcclusion(horizonSine);
        for (double phase : new double[]{0.0, 0.125, 0.37, 0.731}) {
            assertEquals(expected, cosineWeightedHemisphereOcclusion(
                    horizonSine, REFERENCE_SAMPLES, phase), 2.0e-4,
                    "azimuth phase " + phase);
        }
    }

    private static double cosineWeightedHorizonOcclusion(double horizonSine) {
        double clamped = Math.max(0.0, Math.min(1.0, horizonSine));
        return clamped * clamped;
    }

    /**
     * Deterministic cosine-weighted hemisphere samples.  For a horizon cap
     * whose normal dot product is h, the cosine-weighted CDF is h².  The
     * second Halton dimension changes azimuth without changing the expected
     * value, so phase regressions are visible without a random seed.
     */
    private static double cosineWeightedHemisphereOcclusion(double horizonSine,
                                                              int samples,
                                                              double phase) {
        int occluded = 0;
        for (int index = 0; index < samples; index++) {
            double radial = Math.sqrt((index + 0.5) / samples);
            double z = Math.sqrt(Math.max(0.0, 1.0 - radial * radial));
            double azimuth = (halton(index + 1, 2) + phase) % 1.0 * Math.PI * 2.0;
            // Consume the azimuth so this remains an actual hemisphere sample
            // rather than a scalar CDF shortcut.
            double x = radial * Math.cos(azimuth);
            double y = radial * Math.sin(azimuth);
            if (x * x + y * y + z * z < 0.999999) {
                throw new AssertionError("non-unit reference sample");
            }
            if (z <= horizonSine) occluded++;
        }
        return (double) occluded / samples;
    }

    private static double halton(int index, int base) {
        double result = 0.0;
        double fraction = 1.0 / base;
        int value = index;
        while (value > 0) {
            result += (value % base) * fraction;
            value /= base;
            fraction /= base;
        }
        return result;
    }
}
