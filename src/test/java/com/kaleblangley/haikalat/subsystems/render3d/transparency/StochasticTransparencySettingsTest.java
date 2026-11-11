package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the stochastic-transparency configuration value: the
 * disabled no-op identity, all accepted ranges, and the exact probability /
 * history-weight formulas that the shader and JVM reference must share.
 */
class StochasticTransparencySettingsTest {

    @Test
    void disabledIsTheExactNoOpValue() {
        StochasticTransparencySettings disabled = StochasticTransparencySettings.disabled();
        assertFalse(disabled.active());
        assertEquals(StochasticTransparencySettings.DEFAULT_SAMPLES_PER_FRAME,
                disabled.samplesPerFrame());
        assertEquals(StochasticTransparencySettings.JitterMode.HASH, disabled.jitterMode());
        assertEquals(0.0f, disabled.alphaThreshold());
        assertEquals(1.0f, disabled.coverageGamma());
        assertEquals(1.0f, disabled.historyWeight());
        assertEquals(16, disabled.maximumHistoryLength());
        assertEquals(2, disabled.neighborhoodClampRadius());
        assertEquals(1.0f, disabled.reactiveThreshold(),
                "default reactive window is one, i.e. the coverage shock is disabled");
        assertEquals(32.0f, disabled.velocityRejectionPixels());
        assertEquals(4_096, disabled.maximumSurfaces());

        StochasticTransparencySettings active = StochasticTransparencySettings.defaults();
        assertTrue(active.active());
        assertFalse(active.withEnabled(false).active());
        assertEquals(disabled, disabled.withEnabled(false));
        assertNotEquals(disabled, active);
    }

    @Test
    void builderRoundTripsEveryBoundedField() {
        StochasticTransparencySettings settings = StochasticTransparencySettings.builder()
                .enabled(true).samplesPerFrame(8).seed(7)
                .jitterMode(StochasticTransparencySettings.JitterMode.BAYER_4X4)
                .alphaThreshold(0.1f).coverageGamma(0.5f).temporalAccumulation(false)
                .historyWeight(0.75f).maximumHistoryLength(32)
                .neighborhoodClampRadius(3).reactiveThreshold(0.25f)
                .velocityRejectionPixels(12.0f).depthRejectionThreshold(0.05f)
                .maximumSurfaces(128).build();
        assertTrue(settings.active());
        assertEquals(8, settings.samplesPerFrame());
        assertEquals(7, settings.seed());
        assertEquals(StochasticTransparencySettings.JitterMode.BAYER_4X4,
                settings.jitterMode());
        assertEquals(0.1f, settings.alphaThreshold());
        assertEquals(0.5f, settings.coverageGamma());
        assertFalse(settings.temporalAccumulation());
        assertEquals(0.75f, settings.historyWeight());
        assertEquals(32, settings.maximumHistoryLength());
        assertEquals(3, settings.neighborhoodClampRadius());
        assertEquals(0.25f, settings.reactiveThreshold());
        assertEquals(12.0f, settings.velocityRejectionPixels());
        assertEquals(0.05f, settings.depthRejectionThreshold());
        assertEquals(128, settings.maximumSurfaces());

        assertEquals(2, settings.withSamplesPerFrame(2).samplesPerFrame());
        assertEquals(128, settings.withSamplesPerFrame(2).maximumSurfaces(),
                "with* copies keep the unrelated fields");
        assertEquals(9, settings.withSeed(9).seed());
        assertTrue(settings.withTemporalAccumulation(true).temporalAccumulation());
        assertEquals(0.5f, settings.withHistoryWeight(0.5f).historyWeight());
        assertEquals(4, settings.withMaximumHistoryLength(4).maximumHistoryLength());
        assertEquals(8, settings.withMaximumSurfaces(8).maximumSurfaces());
    }

    @Test
    void constructorRejectsOutOfRangeAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class,
                () -> base().samplesPerFrame(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().samplesPerFrame(65).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().jitterMode(null).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().alphaThreshold(-0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().alphaThreshold(1.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().coverageGamma(0.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().coverageGamma(4.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().historyWeight(-0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().historyWeight(1.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().maximumHistoryLength(1).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().maximumHistoryLength(65).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().neighborhoodClampRadius(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().neighborhoodClampRadius(5).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().reactiveThreshold(-0.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().reactiveThreshold(1.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().velocityRejectionPixels(-1.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().velocityRejectionPixels(4_097.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().depthRejectionThreshold(0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().depthRejectionThreshold(0.6f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().maximumSurfaces(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().maximumSurfaces(65_537).build());

        assertThrows(IllegalArgumentException.class,
                () -> base().alphaThreshold(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().coverageGamma(Float.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().historyWeight(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().reactiveThreshold(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().velocityRejectionPixels(Float.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().depthRejectionThreshold(Float.NaN).build());
    }

    @Test
    void acceptanceProbabilityIsTheDocumentedFormula() {
        StochasticTransparencySettings linear = base().build();
        assertEquals(0.0, linear.acceptanceProbability(0.0), 1.0e-12);
        assertEquals(0.25, linear.acceptanceProbability(0.25), 1.0e-12);
        assertEquals(0.5, linear.acceptanceProbability(0.5), 1.0e-12);
        assertEquals(1.0, linear.acceptanceProbability(1.0), 1.0e-12);

        StochasticTransparencySettings squared = base().coverageGamma(2.0f).build();
        assertEquals(0.0625, squared.acceptanceProbability(0.25), 1.0e-12);
        assertEquals(0.25, squared.acceptanceProbability(0.5), 1.0e-12);
        assertEquals(1.0, squared.acceptanceProbability(1.0), 1.0e-12);

        StochasticTransparencySettings thresholded = base().alphaThreshold(0.2f).build();
        assertEquals(0.0, thresholded.acceptanceProbability(0.2), 0.0,
                "alpha equal to the threshold is discarded");
        assertEquals(0.0, thresholded.acceptanceProbability(0.19), 0.0);
        assertEquals(0.3, thresholded.acceptanceProbability(0.3), 1.0e-12);

        assertThrows(IllegalArgumentException.class,
                () -> linear.acceptanceProbability(-0.1));
        assertThrows(IllegalArgumentException.class,
                () -> linear.acceptanceProbability(1.1));
        assertThrows(IllegalArgumentException.class,
                () -> linear.acceptanceProbability(Double.NaN));

        double previous = -1.0;
        for (int step = 0; step <= 100; step++) {
            double alpha = step / 100.0;
            double probability = linear.acceptanceProbability(alpha);
            assertTrue(probability >= previous, "probability must be monotone non-decreasing");
            previous = probability;
        }
    }

    @Test
    void historyWeightIsBoundedByTheHistoryHorizon() {
        StochasticTransparencySettings settings = base()
                .historyWeight(1.0f).maximumHistoryLength(8).build();
        assertEquals(0.0, settings.historyWeightForLength(0), 0.0);
        assertEquals(0.5, settings.historyWeightForLength(1), 1.0e-12);
        assertEquals(2.0 / 3.0, settings.historyWeightForLength(2), 1.0e-12);
        assertEquals(8.0 / 9.0, settings.historyWeightForLength(8), 1.0e-12);
        assertEquals(8.0 / 9.0, settings.historyWeightForLength(64), 1.0e-12,
                "the horizon caps the effective history length");
        assertTrue(settings.historyWeightForLength(8) < 1.0,
                "the current frame must always contribute");

        StochasticTransparencySettings capped = base()
                .historyWeight(0.5f).maximumHistoryLength(8).build();
        assertEquals(0.25, capped.historyWeightForLength(1), 1.0e-12);
        assertEquals(4.0 / 9.0, capped.historyWeightForLength(8), 1.0e-12);

        StochasticTransparencySettings nonTemporal = base()
                .temporalAccumulation(false).build();
        assertEquals(0.0, nonTemporal.historyWeightForLength(4), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> settings.historyWeightForLength(-1));
    }

    @Test
    void depthToleranceUsesTheRelativeWindowWithAFloor() {
        StochasticTransparencySettings settings = base().depthRejectionThreshold(0.02f).build();
        assertEquals(0.2, settings.depthTolerance(10.0), 1.0e-6);
        assertEquals(0.02, settings.depthTolerance(0.5), 1.0e-6,
                "the one-metre depth floor keeps the absolute window stable near the camera");
        assertEquals(0.02, settings.depthTolerance(0.0), 1.0e-6);
        StochasticTransparencySettings minimumWindow = base()
                .depthRejectionThreshold(StochasticTransparencySettings.MINIMUM_DEPTH_REJECTION)
                .build();
        assertEquals((double) StochasticTransparencySettings.MINIMUM_DEPTH_REJECTION,
                minimumWindow.depthTolerance(0.0), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> settings.depthTolerance(Double.NaN));
    }

    @Test
    void jitterModesMapToTheShaderCodes() {
        assertEquals(0, StochasticTransparencySettings.JitterMode.HASH.glCode());
        assertEquals(1,
                StochasticTransparencySettings.JitterMode.INTERLEAVED_GRADIENT.glCode());
        assertEquals(2, StochasticTransparencySettings.JitterMode.BAYER_4X4.glCode());
    }

    private static StochasticTransparencySettings.Builder base() {
        return StochasticTransparencySettings.builder().enabled(true);
    }
}
