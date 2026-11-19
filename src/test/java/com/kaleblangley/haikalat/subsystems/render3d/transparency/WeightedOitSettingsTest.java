package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded-value and weight-formula contract for the weighted blended OIT
 * collection.
 *
 * <p>The weight oracle is written independently in this test from the formula
 * documented on {@link WeightedOitSettings}. Fixed inputs are hand-computed so
 * the CPU value can be reproduced without executing production code; the
 * threshold constants pin the accepted ranges so a silent relaxation cannot
 * make an invalid HDR/weight combination pass.</p>
 */
class WeightedOitSettingsTest {
    private static final WeightedOitSettings WEIGHTED = new WeightedOitSettings(
            true, true, 10.0f, 20.0f, 1.0e-4f, 300.0f, 0.0f, 64);
    private static final double TOLERANCE = 1.0e-12;

    @Test
    void disabledValueIsAnExactNoOpAndTheBuilderDefaultsMatchIt() {
        WeightedOitSettings disabled = WeightedOitSettings.disabled();
        assertFalse(disabled.active(), "disabled settings must not activate the pass");
        assertEquals(disabled, WeightedOitSettings.builder().build(),
                "builder defaults must be the documented no-op value");
        assertEquals(disabled, disabled.withEnabled(false));
        assertTrue(disabled.withEnabled(true).active());
        assertEquals(disabled.depthScale(), disabled.withEnabled(true).depthScale(),
                "toggling enabled must preserve the sub-configuration");
        assertEquals(disabled.maximumSurfaces(), disabled.withEnabled(true).maximumSurfaces());
    }

    @Test
    void rejectsOutOfRangeOrNonFiniteParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOitSettings(true, true, Float.NaN, 20.0f, 1.0e-4f, 300.0f,
                        0.0f, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOitSettings(true, true, 0.0f, 20.0f, 1.0e-4f, 300.0f,
                        0.0f, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOitSettings(true, true, 1_000.1f, 20.0f, 1.0e-4f, 300.0f,
                        0.0f, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOitSettings(true, true, 10.0f, 0.0f, 1.0e-4f, 300.0f,
                        0.0f, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOitSettings(true, true, 10.0f, 20.0f, -1.0f, 300.0f,
                        0.0f, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOitSettings(true, true, 10.0f, 20.0f, 1.0e-4f, 0.0f,
                        0.0f, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOitSettings(true, true, 10.0f, 20.0f, 10.0f, 5.0f,
                        0.0f, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOitSettings(true, true, 10.0f, 20.0f, 1.0e-4f, 300.0f,
                        1.0f, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOitSettings(true, true, 10.0f, 20.0f, 1.0e-4f, 300.0f,
                        0.0f, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOitSettings(true, true, 10.0f, 20.0f, 1.0e-4f, 300.0f,
                        0.0f, WeightedOitSettings.MAXIMUM_SURFACES + 1));
    }

    @Test
    void weightMatchesTheIndependentDoubleFormulaAtFixedDepths() {
        // weight(z) = clamp(10 / (1 + (|z|/20)^2), 1e-4, 300).
        assertEquals(10.0, WEIGHTED.weightForViewDepth(0.0), TOLERANCE);
        assertEquals(10.0 / 1.0625, WEIGHTED.weightForViewDepth(5.0), TOLERANCE);
        assertEquals(5.0, WEIGHTED.weightForViewDepth(20.0), TOLERANCE);
        assertEquals(2.0, WEIGHTED.weightForViewDepth(40.0), TOLERANCE);
        assertEquals(10.0 / 26.0, WEIGHTED.weightForViewDepth(100.0), TOLERANCE);
        assertEquals(10.0 / 2501.0, WEIGHTED.weightForViewDepth(1000.0), TOLERANCE);
        assertEquals(WEIGHTED.weightForViewDepth(-20.0), WEIGHTED.weightForViewDepth(20.0),
                "the weight is a function of |view depth|");

        // Independent reference implementation over a fixed input sweep.
        double[] depths = {0.0, 0.5, 3.0, 9.0, 20.0, 37.5, 100.0, 1.0e4};
        for (double depth : depths) {
            double expected = referenceWeight(WEIGHTED, depth);
            assertEquals(expected, WEIGHTED.weightForViewDepth(depth), 1.0e-12,
                    "weight at depth " + depth);
            assertTrue(WEIGHTED.weightForViewDepth(depth) >= WEIGHTED.minimumWeight()
                            && WEIGHTED.weightForViewDepth(depth) <= WEIGHTED.maximumWeight(),
                    "weight must stay inside the configured bounds at depth " + depth);
        }
    }

    @Test
    void weightClampsAtBothBoundariesAndDisablesExactly() {
        WeightedOitSettings hugeScale = new WeightedOitSettings(true, true, 1000.0f, 1.0f,
                1.0e-4f, 300.0f, 0.0f, 8);
        assertEquals(300.0, hugeScale.weightForViewDepth(0.0), TOLERANCE,
                "depthScale above maximumWeight must clamp to maximumWeight");
        assertEquals(300.0, hugeScale.weightForViewDepth(1.0e-4), TOLERANCE);

        WeightedOitSettings tiny = new WeightedOitSettings(true, true, 10.0f, 20.0f,
                0.005f, 300.0f, 0.0f, 8);
        assertEquals(0.005, tiny.weightForViewDepth(1.0e9), 1.0e-9,
                "a very distant surface must clamp to minimumWeight");

        WeightedOitSettings unweighted = new WeightedOitSettings(true, false, 10.0f, 20.0f,
                1.0e-4f, 300.0f, 0.0f, 8);
        for (double depth : new double[]{0.0, 3.0, 20.0, 1.0e6}) {
            assertEquals(1.0, unweighted.weightForViewDepth(depth), TOLERANCE,
                    "disabled depth weighting is exactly one");
        }
        assertThrows(IllegalArgumentException.class,
                () -> WEIGHTED.weightForViewDepth(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> WEIGHTED.weightForViewDepth(Double.POSITIVE_INFINITY));
    }

    @Test
    void recordEqualityWithEnabledAndCapacityCopiesIsValueBased() {
        WeightedOitSettings copy = WeightedOitSettings.builder()
                .enabled(true).depthWeighting(true).depthScale(10.0f).referenceDepth(20.0f)
                .minimumWeight(1.0e-4f).maximumWeight(300.0f).alphaThreshold(0.0f)
                .maximumSurfaces(64).build();
        assertEquals(WEIGHTED, copy);
        assertEquals(WEIGHTED.hashCode(), copy.hashCode());
        assertNotEquals(WEIGHTED, WEIGHTED.withEnabled(false));
        assertEquals(128, WEIGHTED.withMaximumSurfaces(128).maximumSurfaces());
        assertEquals(64, WEIGHTED.maximumSurfaces());
        assertEquals(0.25f, new WeightedOitSettings(true, true, 10.0f, 20.0f, 1.0e-4f,
                300.0f, 0.25f, 8).alphaThreshold());
    }

    private static double referenceWeight(WeightedOitSettings settings, double viewDepth) {
        if (!settings.depthWeighting()) {
            return 1.0;
        }
        double z = Math.abs(viewDepth);
        double x = z / settings.referenceDepth();
        double raw = settings.depthScale() / (1.0 + x * x);
        return Math.min(settings.maximumWeight(), Math.max(settings.minimumWeight(), raw));
    }
}
