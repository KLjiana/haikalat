package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the cloud reconstruction and history composition rules.
 *
 * <p>Pins the depth-aware spatial weights, the disocclusion/out-of-raster
 * rejection, the clamp-before-blend order and the dependency of the blended
 * result on the accepted history. The expected constants come from a separate
 * scratch calculation of the documented formulas. This class never creates a GL
 * context; the real reconstruction runs in the guarded {@code VolumetricCloudGlTest}.</p>
 */
class VolumetricCloudReconstructionTest {
    private static final double EPSILON = 1.0e-12;

    @Test
    void depthAwareSpatialWeightsRejectDisoccludedTapsAndStayNormalized() {
        double[] taps = {100.0, 102.0, 130.0, -1.0};
        VolumetricCloudReference.Reconstructed reconstructed =
                VolumetricCloudReference.spatialWeights(10.25, 20.75, 100.0, taps, 2.0);
        double[] weights = reconstructed.weights();
        assertEquals(0.8907674992564198, weights[0], 1.0e-12);
        assertEquals(0.10923168328004497, weights[1], 1.0e-12);
        assertEquals(8.174635351504416e-07, weights[2], 1.0e-12,
                "a tap 30 m behind the center is rejected by the depth window");
        assertEquals(0.0, weights[3], 0.0,
                "a background tap is rejected at a surface pixel");
        double sum = 0.0;
        for (double weight : weights) {
            sum += weight;
        }
        assertEquals(1.0, sum, EPSILON, "accepted taps must be renormalized");

        VolumetricCloudReference.Reconstructed backgrounds =
                VolumetricCloudReference.spatialWeights(10.25, 20.75, -1.0,
                        new double[]{-1.0, -1.0, -1.0, -1.0}, 2.0);
        double backgroundSum = 0.0;
        for (double weight : backgrounds.weights()) {
            backgroundSum += weight;
        }
        assertEquals(1.0, backgroundSum, EPSILON,
                "background/background taps are compatible");
        assertEquals(1.0, backgrounds.similarity()[0], 0.0);
    }

    @Test
    void allRejectedTapsFallBackToTheNearestLowResolutionTexel() {
        VolumetricCloudReference.Reconstructed fallback =
                VolumetricCloudReference.spatialWeights(10.25, 20.75, 100.0,
                        new double[]{500.0, 500.0, 500.0, 500.0}, 2.0);
        assertEquals(0.0, fallback.weights()[0], 0.0);
        assertEquals(0.0, fallback.weights()[1], 0.0);
        assertEquals(1.0, fallback.weights()[2], 0.0,
                "the nearest texel (base + 1 in y) is used when every tap is rejected");
        assertEquals(0.0, fallback.weights()[3], 0.0);
    }

    @Test
    void historyAcceptanceRejectsDisocclusionOutOfRasterAndStaleState() {
        assertTrue(VolumetricCloudReference.historyAccepted(true, true, 0.5, 0.5,
                1000.0, 1000.0, true));
        assertTrue(VolumetricCloudReference.historyAccepted(true, true, 0.5, 0.5,
                1000.0, 1019.0, true), "a depth delta inside 2% is accepted");
        assertFalse(VolumetricCloudReference.historyAccepted(true, true, 0.5, 0.5,
                1000.0, 1021.0, true), "a disocclusion beyond the depth window is rejected");
        assertFalse(VolumetricCloudReference.historyAccepted(true, true, -0.01, 0.5,
                1000.0, 1000.0, true), "an out-of-raster reprojection is rejected");
        assertFalse(VolumetricCloudReference.historyAccepted(true, true, 0.5, 1.01,
                1000.0, 1000.0, true));
        assertFalse(VolumetricCloudReference.historyAccepted(true, true, 0.5, 0.5,
                1000.0, 1000.0, false), "an invalid stored anchor is rejected");
        assertFalse(VolumetricCloudReference.historyAccepted(false, true, 0.5, 0.5,
                1000.0, 1000.0, true), "a camera-cut/exposure frame rejects all history");
        assertFalse(VolumetricCloudReference.historyAccepted(true, false, 0.5, 0.5,
                1000.0, 1000.0, true), "a sky ray without a layer anchor keeps no history");
    }

    @Test
    void historyIsClampedToTheCurrentRangeBeforeBlending() {
        assertEquals(0.8, VolumetricCloudReference.historyBlend(0.5, 0.9, 0.2, 0.8, 1.0),
                1.0e-12, "history above the current range clamps to the maximum");
        assertEquals(0.5, VolumetricCloudReference.historyBlend(0.5, 0.9, 0.2, 0.8, 0.0),
                0.0, "history weight 0 is an exact spatial-only result");
        assertEquals(0.65, VolumetricCloudReference.historyBlend(0.5, 0.7, 0.2, 0.8, 0.75),
                1.0e-12, "an in-range history is blended after clamping");
        assertEquals(0.4, VolumetricCloudReference.historyBlend(0.5, 0.1, 0.4, 0.8, 1.0),
                0.0, "clamping happens before the weight, not after");
    }

    @Test
    void blendedResultDependsOnlyOnAcceptedHistory() {
        double spatial = 0.35;
        double history = 0.80;
        boolean accepted = VolumetricCloudReference.historyAccepted(true, true, 0.5, 0.5,
                500.0, 500.0, true);
        double withHistory = accepted
                ? VolumetricCloudReference.historyBlend(spatial, history, 0.1, 0.9, 0.9)
                : spatial;
        double withoutHistory = VolumetricCloudReference.historyBlend(spatial, history,
                0.1, 0.9, 0.0);
        assertNotEquals(withHistory, withoutHistory, "the accepted history must change the result");

        boolean rejected = VolumetricCloudReference.historyAccepted(true, true, 0.5, 0.5,
                500.0, 900.0, true);
        double rejectedResult = rejected
                ? VolumetricCloudReference.historyBlend(spatial, history, 0.1, 0.9, 0.9)
                : spatial;
        assertEquals(spatial, rejectedResult, 0.0,
                "a rejected history must fall back to the spatial result exactly");
    }
}
