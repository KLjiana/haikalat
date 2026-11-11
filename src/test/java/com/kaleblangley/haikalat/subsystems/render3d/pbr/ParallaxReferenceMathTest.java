package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the independent double-precision parallax oracle with fixed,
 * hand-computable samples. Expectations are derived from the documented model,
 * not from production code or the shader:
 *
 * <ul>
 *   <li>constant height {@code h}: the march stops at depth {@code h} and the
 *       interpolated UV offset is {@code slope * heightScale * weight * h};</li>
 *   <li>linear height {@code h(u) = 0.25 + 0.5 u}: the depth error is linear,
 *       so the layer march plus one linear interpolation reproduces the
 *       analytic intersection exactly;</li>
 *   <li>height one never intersects within the bounded march and falls back to
 *       the fully offset UV.</li>
 * </ul>
 */
class ParallaxReferenceMathTest {
    private static final double U = 0.5;
    private static final double V = 0.25;
    private static final double VIEW_X = 0.6;
    private static final double VIEW_Y = 0.0;
    private static final double VIEW_Z = 0.8;
    private static final double HEIGHT_SCALE = 0.2;
    private static final int MIN_STEPS = 4;
    private static final int MAX_STEPS = 8;
    /** 8 + (4 - 8) * 0.8 = 4.8, rounded up by the shader's ceil. */
    private static final int EXPECTED_LAYERS = 5;
    /** 0.75 slope * 0.2 height over the full march. */
    private static final double FULL_OFFSET = 0.15;
    private static final double TOLERANCE = 1.0e-12;

    @Test
    void fadeMatchesTheSmoothstepEndpointsAndMidpoint() {
        assertEquals(1.0, ParallaxReferenceMath.fade(59.0, 60.0, 85.0), 0.0);
        assertEquals(1.0, ParallaxReferenceMath.fade(60.0, 60.0, 85.0), 0.0);
        assertEquals(0.5, ParallaxReferenceMath.fade(72.5, 60.0, 85.0), TOLERANCE,
                "the cubic smoothstep is exactly one half at the window midpoint");
        assertEquals(0.0, ParallaxReferenceMath.fade(85.0, 60.0, 85.0), 0.0);
        assertEquals(0.0, ParallaxReferenceMath.fade(120.0, 60.0, 85.0), 0.0);
        assertEquals(1.0, ParallaxReferenceMath.fade(8.0, 8.0, 20.0), 0.0);
        assertEquals(0.0, ParallaxReferenceMath.fade(20.0, 8.0, 20.0), 0.0);
    }

    @Test
    void angularAndDistanceWindowsMultiplyAndStayBounded() {
        assertEquals(1.0, ParallaxReferenceMath.weight(1.0, 5.0, 60.0, 85.0, 8.0, 20.0),
                TOLERANCE, "head-on and near must both be fully active");
        assertEquals(0.0, ParallaxReferenceMath.weight(0.0, 5.0, 60.0, 85.0, 8.0, 20.0),
                0.0, "a grazing view is fully faded");
        assertEquals(0.0, ParallaxReferenceMath.weight(1.0, 25.0, 60.0, 85.0, 8.0, 20.0),
                0.0, "a far view is fully faded");
        // 54.5 degrees is inside the angular window and 14 in the distance window.
        double weight = ParallaxReferenceMath.weight(Math.cos(Math.toRadians(54.5)), 14.0,
                60.0, 85.0, 8.0, 20.0);
        double expected = ParallaxReferenceMath.fade(54.5, 60.0, 85.0)
                * ParallaxReferenceMath.fade(14.0, 8.0, 20.0);
        assertEquals(expected, weight, TOLERANCE,
                "the combined weight is the product of the two smoothstep windows");
        assertTrue(weight > 0.0 && weight < 1.0);
        for (double angle = 0.0; angle <= 90.0; angle += 0.5) {
            double value = ParallaxReferenceMath.weight(Math.cos(Math.toRadians(angle)), 14.0,
                    60.0, 85.0, 8.0, 20.0);
            assertTrue(value >= 0.0 && value <= 1.0, "weight at " + angle + " is " + value);
        }
    }

    @Test
    void constantHalfHeightStopsAtTheSurfaceLayerAndInterpolatesHalfway() {
        ParallaxReferenceMath.Result result = ParallaxReferenceMath.march(U, V, VIEW_X, VIEW_Y,
                VIEW_Z, HEIGHT_SCALE, MIN_STEPS, MAX_STEPS, 1.0, 1.0, false, (u, v) -> 0.5);

        assertEquals(EXPECTED_LAYERS, result.layerCount());
        assertEquals(3, result.steps(), "depth 0.2/0.4 are above h=0.5 and depth 0.6 is below");
        assertTrue(result.hit());
        assertEquals(1.0, result.weight(), 0.0);
        // Analytic hit: depth == 0.5, lateral offset 0.15 * 0.5 = 0.075.
        assertEquals(U - 0.075, result.u(), TOLERANCE);
        assertEquals(V, result.v(), 0.0);
    }

    @Test
    void linearHeightRampReproducesTheAnalyticIntersection() {
        ParallaxReferenceMath.HeightSampler ramp = (u, v) -> 0.25 + 0.5 * u;
        ParallaxReferenceMath.Result result = ParallaxReferenceMath.march(U, V, VIEW_X, VIEW_Y,
                VIEW_Z, HEIGHT_SCALE, MIN_STEPS, MAX_STEPS, 1.0, 1.0, false, ramp);

        // h(u) = 0.25 + 0.5u; hit depth d = h0 / (1 + 0.5 * 0.15) with h0 = 0.5.
        double expectedDepth = 0.5 / (1.0 + 0.5 * FULL_OFFSET);
        double expectedU = U - FULL_OFFSET * expectedDepth;
        assertEquals(0.4302325581395349, expectedU, TOLERANCE,
                "the closed-form intersection used by the next assertion");
        assertEquals(expectedU, result.u(), TOLERANCE,
                "a linear height field is reproduced exactly by one interpolation step");
        assertEquals(V, result.v(), 0.0);
        assertTrue(result.hit());
    }

    @Test
    void constantFullHeightExhaustsTheBoundedMarchWithoutAHit() {
        ParallaxReferenceMath.Result result = ParallaxReferenceMath.march(U, V, VIEW_X, VIEW_Y,
                VIEW_Z, HEIGHT_SCALE, MIN_STEPS, MAX_STEPS, 1.0, 1.0, false, (u, v) -> 1.0);

        assertEquals(EXPECTED_LAYERS, result.steps());
        assertFalse(result.hit(), "depth never exceeds a height of one inside the march");
        assertEquals(U - FULL_OFFSET, result.u(), TOLERANCE,
                "the bounded fallback returns the fully offset UV, not the original UV");
        assertTrue(Double.isFinite(result.u()) && Double.isFinite(result.v()));
    }

    @Test
    void zeroHeightAndDisabledInputsKeepTheInterpolatedUvExactly() {
        ParallaxReferenceMath.Result flat = ParallaxReferenceMath.march(U, V, VIEW_X, VIEW_Y,
                VIEW_Z, HEIGHT_SCALE, MIN_STEPS, MAX_STEPS, 1.0, 1.0, false, (u, v) -> 0.0);
        assertEquals(U, flat.u(), 0.0);
        assertEquals(V, flat.v(), 0.0);
        assertEquals(0, flat.steps(), "a zero surface layer is an immediate non-hit");
        assertFalse(flat.hit());

        ParallaxReferenceMath.Result zeroScale = ParallaxReferenceMath.march(U, V, VIEW_X, VIEW_Y,
                VIEW_Z, 0.0, MIN_STEPS, MAX_STEPS, 1.0, 1.0, false, (u, v) -> 1.0);
        assertEquals(U, zeroScale.u(), 0.0);
        assertEquals(0, zeroScale.steps());

        ParallaxReferenceMath.Result faded = ParallaxReferenceMath.march(U, V, VIEW_X, VIEW_Y,
                VIEW_Z, HEIGHT_SCALE, MIN_STEPS, MAX_STEPS, 0.0, 1.0, false, (u, v) -> 1.0);
        assertEquals(U, faded.u(), 0.0);
        assertEquals(0, faded.steps(), "a zero angular weight must not march");

        ParallaxReferenceMath.Result grazing = ParallaxReferenceMath.march(U, V, VIEW_X, VIEW_Y,
                1.0e-9, HEIGHT_SCALE, MIN_STEPS, MAX_STEPS, 1.0, 1.0, false, (u, v) -> 1.0);
        assertEquals(U, grazing.u(), 0.0, "a view below the tangent plane fails closed");
        assertEquals(V, grazing.v(), 0.0);
    }

    @Test
    void flippedHeightUsesTheComplement() {
        ParallaxReferenceMath.Result normal = ParallaxReferenceMath.march(U, V, VIEW_X, VIEW_Y,
                VIEW_Z, HEIGHT_SCALE, MIN_STEPS, MAX_STEPS, 1.0, 1.0, false, (u, v) -> 0.25);
        ParallaxReferenceMath.Result flipped = ParallaxReferenceMath.march(U, V, VIEW_X, VIEW_Y,
                VIEW_Z, HEIGHT_SCALE, MIN_STEPS, MAX_STEPS, 1.0, 1.0, true, (u, v) -> 0.75);
        assertEquals(normal.u(), flipped.u(), TOLERANCE);
        assertEquals(normal.v(), flipped.v(), TOLERANCE);
        assertEquals(normal.steps(), flipped.steps());
    }

    @Test
    void slopeCapBoundsTheOffsetForNearGrazingViews() {
        ParallaxReferenceMath.Result result = ParallaxReferenceMath.march(0.99, 0.5, 8.0, 0.0,
                1.0e-3, 0.1, 1, 1, 1.0, 1.0, false, (u, v) -> 1.0);

        assertEquals(1, result.layerCount());
        assertEquals(1, result.steps());
        assertFalse(result.hit());
        assertEquals(0.19, result.u(), 1.0e-9,
                "slope is capped at 8 and scale is 0.1, so the offset is bounded by 0.8");
        assertTrue(Math.abs(result.u() - 0.99) <= 8.0 * 0.1 + 1.0e-9);
        assertTrue(Double.isFinite(result.u()) && Double.isFinite(result.v()));
    }

    @Test
    void stepCountIsClampedToTheCompileTimeCap() {
        ParallaxReferenceMath.Result result = ParallaxReferenceMath.march(U, V, VIEW_X, VIEW_Y,
                VIEW_Z, HEIGHT_SCALE, 500, 1, 1.0, 1.0, false, (u, v) -> 1.0);
        assertEquals(ParallaxReferenceMath.MAXIMUM_STEPS, result.layerCount(),
                "both step bounds clamp into [1, 128] with max >= min");
        assertEquals(ParallaxReferenceMath.MAXIMUM_STEPS, result.steps(),
                "the loop never exceeds the compile-time cap");
    }

    @Test
    void outOfRangeUvStaysFiniteWithARepeatingSampler() {
        double[] lastSample = new double[2];
        ParallaxReferenceMath.HeightSampler repeat = (u, v) -> {
            lastSample[0] = u;
            lastSample[1] = v;
            double wrapped = u - Math.floor(u);
            return wrapped;
        };
        ParallaxReferenceMath.Result result = ParallaxReferenceMath.march(0.99, 0.5, 8.0, 0.0,
                1.0e-3, 1.0, 1, 1, 1.0, 1.0, false, repeat);

        assertTrue(Double.isFinite(result.u()) && Double.isFinite(result.v()));
        assertTrue(Double.isFinite(lastSample[0]) && Double.isFinite(lastSample[1]));
        assertEquals(0.99 - 8.0 * 0.99, result.u(), 1.0e-9,
                "the UV may leave [0,1]; the bound sampler owns wrap behaviour");
    }
}
