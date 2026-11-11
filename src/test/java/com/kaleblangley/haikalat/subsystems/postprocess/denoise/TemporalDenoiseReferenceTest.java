package com.kaleblangley.haikalat.subsystems.postprocess.denoise;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-input, double-precision reference cases for reprojection and the
 * depth-matched neighbourhood / moment clamp.
 *
 * <p>Expected values are written from the closed-form inputs rather than
 * calling the production shader or Java pass.  The same reference is used by
 * the GL convergence case for its high-sample expectation.</p>
 */
class TemporalDenoiseReferenceTest {
    @Test
    void reprojectionFollowsTheFixedPreviousViewProjection() {
        Matrix4d inverseProjection = new Matrix4d();
        Matrix4d inverseView = new Matrix4d();
        Vector3d world = TemporalDenoiseReference.reconstructWorld(
                inverseProjection, inverseView, 0.75, 0.5, 0.75);
        assertNotNull(world);
        assertEquals(0.5, world.x, 1.0e-12);
        assertEquals(0.0, world.y, 1.0e-12);
        assertEquals(0.5, world.z, 1.0e-12);

        Matrix4d previousViewProjection = new Matrix4d().translate(0.1, 0.0, 0.0);
        double[] previousUv = TemporalDenoiseReference.previousUv(
                previousViewProjection, world);
        assertNotNull(previousUv);
        assertEquals(0.8, previousUv[0], 1.0e-12);
        assertEquals(0.5, previousUv[1], 1.0e-12);

        Vector4d previousClip = new Vector4d(world, 1.0).mul(previousViewProjection);
        assertEquals(0.5, TemporalDenoiseReference.previousViewDepth(
                new Matrix4d(), previousClip), 1.0e-12);

        assertTrue(TemporalDenoiseReference.acceptsReprojection(
                previousUv, 0.75, 0.5, 100.0, 100.0, 6.0));
        assertFalse(TemporalDenoiseReference.acceptsReprojection(
                previousUv, 0.75, 0.5, 100.0, 100.0, 4.0),
                "a five-pixel move must be rejected by a four-pixel budget");
        assertFalse(TemporalDenoiseReference.acceptsReprojection(
                new double[]{1.1, 0.5}, 0.75, 0.5, 100.0, 100.0, 1000.0),
                "out-of-raster history must be rejected regardless of the budget");
        assertNull(TemporalDenoiseReference.previousUv(
                new Matrix4d().zero(), world),
                "a non-positive w must not yield a raster position");
    }

    @Test
    void depthAndNormalChecksUseTheDocumentedWindows() {
        Vector3d normal = new Vector3d(0.0, 0.0, 1.0);
        assertTrue(TemporalDenoiseReference.acceptsHistory(
                10.0, 10.1, 0.02, normal, new Vector3d(0.0, 0.0, 1.0), 0.85));
        assertFalse(TemporalDenoiseReference.acceptsHistory(
                10.0, 10.3, 0.02, normal, new Vector3d(0.0, 0.0, 1.0), 0.85),
                "a 0.3 m difference exceeds 2% of ten metres");
        assertFalse(TemporalDenoiseReference.acceptsHistory(
                10.0, 10.0, 0.02, normal,
                new Vector3d(0.0, 1.0, 0.0), 0.85),
                "a 90-degree normal difference must reject the history");
        assertEquals(0.2, TemporalDenoiseReference.depthTolerance(10.0, 0.02),
                1.0e-12);
        assertEquals(1.0e-4, TemporalDenoiseReference.depthTolerance(0.01, 1.0e-5),
                1.0e-18, "the epsilon floor must keep a tiny window non-degenerate");
    }

    @Test
    void brightnessShockResetsOnRelativeLuminanceJumps() {
        assertTrue(TemporalDenoiseReference.brightnessShock(1.0, 10.0, 0.5));
        assertFalse(TemporalDenoiseReference.brightnessShock(10.0, 10.5, 0.5));
        assertFalse(TemporalDenoiseReference.brightnessShock(0.0, 0.0, 0.0),
                "identical black must not count as a shock");
        assertTrue(TemporalDenoiseReference.brightnessShock(0.0, 1.0, 0.5));
    }

    @Test
    void neighbourhoodBoundsFollowTheFixedThreeByThreeInput() {
        double[][][] colours = new double[3][3][];
        boolean[][] included = new boolean[3][3];
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                colours[x][y] = TemporalDenoiseReference.rgb(
                        0.1 * x + 0.05 * y, 0.1 * x + 0.05 * y,
                        0.1 * x + 0.05 * y);
                included[x][y] = true;
            }
        }
        double[] stats = TemporalDenoiseReference.neighbourhoodBounds(
                colours, included, 1, 1, 1,
                TemporalDenoiseReference.rgb(0.15, 0.15, 0.15));
        assertEquals(0.0, stats[0], 0.0);
        assertEquals(0.3, stats[3], 1.0e-12);
        assertEquals(0.15, stats[6], 1.0e-12);
        assertEquals(0.008333333333333333, stats[7], 1.0e-12);

        included[2][2] = false;
        double[] masked = TemporalDenoiseReference.neighbourhoodBounds(
                colours, included, 1, 1, 1,
                TemporalDenoiseReference.rgb(0.15, 0.15, 0.15));
        assertEquals(0.25, masked[3], 1.0e-12);
        assertEquals(1.05 / 8.0, masked[6], 1.0e-12);
        assertEquals(0.1875 / 8.0 - (1.05 / 8.0) * (1.05 / 8.0),
                masked[7], 1.0e-12);
    }

    @Test
    void momentClampLimitsHistoryToTheCurrentDistribution() {
        double[] clamped = TemporalDenoiseReference.clampHistory(
                TemporalDenoiseReference.rgb(10.0, 10.0, 10.0),
                TemporalDenoiseReference.rgb(0.1, 0.1, 0.1),
                TemporalDenoiseReference.rgb(0.9, 0.9, 0.9),
                0.5, 0.0, 2.0);
        assertEquals(0.5, clamped[0], 1.0e-12);
        assertEquals(0.5, clamped[1], 1.0e-12);
        assertEquals(0.5, clamped[2], 1.0e-12);

        double[] bounded = TemporalDenoiseReference.clampHistory(
                TemporalDenoiseReference.rgb(2.0, 2.0, 2.0),
                TemporalDenoiseReference.rgb(0.0, 0.0, 0.0),
                TemporalDenoiseReference.rgb(1.0, 1.0, 1.0),
                0.15, 1.0 / 120.0, 2.0);
        assertEquals(0.15 + 2.0 * Math.sqrt(1.0 / 120.0),
                bounded[0], 1.0e-12);
    }

    @Test
    void luminanceUsesTheShaderWeights() {
        assertEquals(0.2126, TemporalDenoiseReference.luminance(
                TemporalDenoiseReference.rgb(1.0, 0.0, 0.0)), 1.0e-12);
        assertEquals(0.7152, TemporalDenoiseReference.luminance(
                TemporalDenoiseReference.rgb(0.0, 1.0, 0.0)), 1.0e-12);
        assertEquals(0.0722, TemporalDenoiseReference.luminance(
                TemporalDenoiseReference.rgb(0.0, 0.0, 1.0)), 1.0e-12);
    }
}
