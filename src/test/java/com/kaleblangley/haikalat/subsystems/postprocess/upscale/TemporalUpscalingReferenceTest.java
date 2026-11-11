package com.kaleblangley.haikalat.subsystems.postprocess.upscale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-input recomputation of the independent temporal-upscaling reference.
 * Every expected value below is hand-computed from the documented formula, so a
 * production regression cannot satisfy the test by calling the same helper.
 */
class TemporalUpscalingReferenceTest {
    private static final double[] IDENTITY = {
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0};

    @Test
    void luminanceUsesTheDocumentedRec709Weights() {
        assertEquals(0.2126, TemporalUpscalingReference.luminance(1.0, 0.0, 0.0), 1.0e-12);
        assertEquals(0.7152, TemporalUpscalingReference.luminance(0.0, 1.0, 0.0), 1.0e-12);
        assertEquals(0.0722, TemporalUpscalingReference.luminance(0.0, 0.0, 1.0), 1.0e-12);
    }

    @Test
    void previousViewProjectionReprojectsToHandComputedUvAndDepth() {
        double[] previousViewProjection = {
                1.0, 0.0, 0.0, 0.1,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0};
        double[] clip = TemporalUpscalingReference.transform(previousViewProjection,
                new double[]{0.2, 0.4, -1.0, 1.0});
        assertArrayEquals(new double[]{0.3, 0.4, -1.0, 1.0}, clip, 1.0e-12);

        double[] uv = TemporalUpscalingReference.previousUv(clip);
        assertArrayEquals(new double[]{0.65, 0.70}, uv, 1.0e-12);
        assertEquals(1.0, TemporalUpscalingReference.previousViewDepth(clip, IDENTITY),
                1.0e-12);
    }

    @Test
    void stableOutputPositionIsTheReprojectionOrigin() {
        // A surface reconstructed at stable output position (0.5, 0.5) must
        // reproject onto that same output pixel when the previous view-projection
        // is static.  Reprojecting from the containing low-res texel centre would
        // instead shift history by up to half a low-res texel.
        double[] previousViewProjection = {
                0.5, 0.0, 0.0, 0.0,
                0.0, 0.5, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0};
        double[] staticClip = TemporalUpscalingReference.transform(previousViewProjection,
                new double[]{0.0, 0.0, 0.0, 1.0});
        assertArrayEquals(new double[]{0.5, 0.5},
                TemporalUpscalingReference.previousUv(staticClip), 1.0e-12);

        double[] translatedViewProjection = {
                0.5, 0.0, 0.0, -0.1,
                0.0, 0.5, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0};
        double[] movedClip = TemporalUpscalingReference.transform(translatedViewProjection,
                new double[]{0.0, 0.0, 0.0, 1.0});
        assertArrayEquals(new double[]{0.45, 0.5},
                TemporalUpscalingReference.previousUv(movedClip), 1.0e-12);
    }

    @Test
    void reprojectionBudgetIsMeasuredInOutputPixels() {
        double displacement = TemporalUpscalingReference.reprojectionPixels(
                new double[]{0.5, 0.5}, new double[]{0.65, 0.7}, 64, 64);
        assertEquals(16.0, displacement, 1.0e-12);
        assertTrue(displacement <= 16.0);
        assertFalse(displacement <= 15.99);
    }

    @Test
    void depthToleranceMatchesTheDocumentedAbsoluteAndRelativeWindow() {
        assertEquals(0.04, TemporalUpscalingReference.depthTolerance(2.0), 1.0e-12);
        assertEquals(0.04, TemporalUpscalingReference.depthTolerance(-2.0), 1.0e-12);
        assertEquals(0.02, TemporalUpscalingReference.depthTolerance(0.5), 1.0e-12);
        assertEquals(0.02, TemporalUpscalingReference.depthTolerance(0.0), 1.0e-12);
        assertEquals(2.0, TemporalUpscalingReference.depthTolerance(100.0), 1.0e-12);
        assertEquals(0.02, TemporalUpscalingReference.depthTolerance(1.0e-6), 1.0e-12);
    }

    @Test
    void exposureWindowIsRelativeToTheLargerScalar() {
        assertEquals(0.05, TemporalUpscalingReference.exposureRelativeChange(4.0, 3.8),
                1.0e-12);
        assertEquals(0.5, TemporalUpscalingReference.exposureRelativeChange(2.0, 1.0),
                1.0e-12);
        assertTrue(TemporalUpscalingReference.exposureAccepted(1.05, 1.0, 0.1));
        assertFalse(TemporalUpscalingReference.exposureAccepted(1.2, 1.0, 0.1));
        assertTrue(TemporalUpscalingReference.exposureAccepted(2.0, 1.0, 0.5));
        assertFalse(TemporalUpscalingReference.exposureAccepted(2.0, 1.0, 0.49));
    }

    @Test
    void reactiveThresholdUsesRelativeLuminanceChange() {
        assertFalse(TemporalUpscalingReference.reactiveReset(2.0, 1.0, 0.5));
        assertTrue(TemporalUpscalingReference.reactiveReset(2.2, 1.0, 0.5));
        assertTrue(TemporalUpscalingReference.reactiveReset(1.0001, 1.0, 0.0));
        assertTrue(TemporalUpscalingReference.reactiveReset(Double.NaN, 1.0, 0.5));
        assertTrue(TemporalUpscalingReference.reactiveReset(1.0, Double.POSITIVE_INFINITY,
                0.5));
    }

    @Test
    void bilinearResampleMatchesHandComputedFixedSamples() {
        double[] image = {
                1.0, 0.0, 0.0, 1.0, 2.0, 0.0, 0.0, 1.0,
                3.0, 0.0, 0.0, 1.0, 5.0, 0.0, 0.0, 1.0};
        assertArrayEquals(new double[]{2.75, 0.0, 0.0, 1.0},
                TemporalUpscalingReference.bilinearRgba(image, 2, 2, 0.5, 0.5), 1.0e-12);
        assertArrayEquals(new double[]{1.0, 0.0, 0.0, 1.0},
                TemporalUpscalingReference.bilinearRgba(image, 2, 2, 0.25, 0.25), 1.0e-12);
        assertArrayEquals(new double[]{5.0, 0.0, 0.0, 1.0},
                TemporalUpscalingReference.bilinearRgba(image, 2, 2, 0.75, 0.75), 1.0e-12);
    }

    @Test
    void sharpeningMatchesHandComputedFixedCrossAndClampsOvershoot() {
        double[] center = {0.6, 0.6, 0.6};
        double[] north = {0.4, 0.4, 0.4};
        double[] south = {0.5, 0.5, 0.5};
        double[] east = {0.7, 0.7, 0.7};
        double[] west = {0.3, 0.3, 0.3};
        // average = 0.475, center - average = 0.125, amount 0.5 -> 0.6625.
        assertArrayEquals(new double[]{0.6625, 0.6625, 0.6625},
                TemporalUpscalingReference.sharpen(center, north, south, east, west, 0.5),
                1.0e-12);

        double[] brightCenter = {1.0, 1.0, 1.0};
        double[] black = {0.0, 0.0, 0.0};
        // Unbounded unsharp would reach 2.0; the local range caps it at 1.0.
        assertArrayEquals(new double[]{1.0, 1.0, 1.0},
                TemporalUpscalingReference.sharpen(brightCenter, black, black, black, black, 1.0),
                1.0e-12);
        assertArrayEquals(brightCenter,
                TemporalUpscalingReference.sharpen(brightCenter, black, black, black, black, 0.0),
                1.0e-12);
    }
}
