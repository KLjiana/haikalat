package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-input, double-precision reference cases for the stochastic estimator,
 * its temporal running average and the white-noise threshold.
 *
 * <p>The expected numbers are derived from the closed-form layer equations or
 * from the documented integer hash, not from the production pass or shader.
 * The same reference supplies the expectation for the statistical GL cases.</p>
 */
class StochasticTransparencyReferenceTest {

    @Test
    void twoLayerExpectationIsTheSortedAlphaComposite() {
        List<StochasticTransparencyReference.Layer> layers = List.of(
                new StochasticTransparencyReference.Layer(0.5, 2.0,
                        new double[]{1.0, 0.0, 0.0}),
                new StochasticTransparencyReference.Layer(0.25, 8.0,
                        new double[]{0.0, 1.0, 0.0}));
        // 1 - 0.5 * 0.75 = 0.625; premultiplied = 0.5*(1,0,0) + 0.5*0.25*(0,1,0).
        assertEquals(0.625, StochasticTransparencyReference.expectedCoverage(layers),
                1.0e-12);
        assertArrayEquals(new double[]{0.5, 0.125, 0.0},
                StochasticTransparencyReference.expectedPremultiplied(layers), 1.0e-12);
        assertArrayEquals(new double[]{0.5, 0.125, 0.375},
                StochasticTransparencyReference.expectedComposite(layers,
                        new double[]{0.0, 0.0, 1.0}), 1.0e-12);

        // The estimator is order independent: the same layers in reverse order
        // must produce the same expectation.
        assertArrayEquals(StochasticTransparencyReference.expectedComposite(
                        List.of(layers.get(1), layers.get(0)), new double[]{0.0, 0.0, 1.0}),
                StochasticTransparencyReference.expectedComposite(layers,
                        new double[]{0.0, 0.0, 1.0}), 1.0e-12);
    }

    @Test
    void opaqueAndEmptyLayerSetsStayExact() {
        assertArrayEquals(new double[]{0.0, 0.0, 1.0},
                StochasticTransparencyReference.expectedComposite(List.of(),
                        new double[]{0.0, 0.0, 1.0}), 1.0e-12);
        List<StochasticTransparencyReference.Layer> opaque = List.of(
                new StochasticTransparencyReference.Layer(1.0, 3.0,
                        new double[]{0.2, 0.4, 0.6}));
        assertArrayEquals(new double[]{0.2, 0.4, 0.6},
                StochasticTransparencyReference.expectedComposite(opaque,
                        new double[]{0.0, 0.0, 1.0}), 1.0e-12);
        assertEquals(1.0, StochasticTransparencyReference.expectedCoverage(opaque), 0.0);

        assertThrows(IllegalArgumentException.class,
                () -> new StochasticTransparencyReference.Layer(1.1, 1.0,
                        new double[]{0.0, 0.0, 0.0}));
        assertThrows(IllegalArgumentException.class,
                () -> new StochasticTransparencyReference.Layer(0.5, 0.0,
                        new double[]{0.0, 0.0, 0.0}));
        assertThrows(IllegalArgumentException.class,
                () -> new StochasticTransparencyReference.Layer(0.5, 1.0,
                        new double[]{0.0, 0.0}));
    }

    @Test
    void productionProbabilityMatchesTheIndependentFormula() {
        float[] gammas = {1.0f, 0.5f, 2.0f, 4.0f};
        float[] thresholds = {0.0f, 0.1f, 0.5f};
        for (float gamma : gammas) {
            for (float threshold : thresholds) {
                StochasticTransparencySettings settings = StochasticTransparencySettings
                        .builder().enabled(true).coverageGamma(gamma)
                        .alphaThreshold(threshold).build();
                for (int step = 0; step <= 200; step++) {
                    double alpha = step / 200.0;
                    assertEquals(
                            StochasticTransparencyReference.acceptanceProbability(
                                    settings, alpha),
                            settings.acceptanceProbability(alpha), 1.0e-15,
                            "gamma=" + gamma + " threshold=" + threshold
                                    + " alpha=" + alpha);
                }
            }
        }
    }

    @Test
    void coverageIsTheSampleAverageAndMomentsAreClosedForm() {
        assertEquals(0.0, StochasticTransparencyReference.coverageFromHits(0, 4), 0.0);
        assertEquals(0.25, StochasticTransparencyReference.coverageFromHits(1, 4), 0.0);
        assertEquals(1.0, StochasticTransparencyReference.coverageFromHits(4, 4), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> StochasticTransparencyReference.coverageFromHits(5, 4));
        assertThrows(IllegalArgumentException.class,
                () -> StochasticTransparencyReference.coverageFromHits(-1, 4));

        double[] moments = StochasticTransparencyReference.coverageMoments(
                new double[]{0.0, 0.25, 0.5, 0.75, 1.0});
        assertEquals(0.5, moments[0], 1.0e-12);
        assertEquals(0.375, moments[1], 1.0e-12);
        assertEquals(0.125, moments[2], 1.0e-12);
    }

    @Test
    void temporalWeightIsZeroBeforeTheFirstCommitAndBoundedBelowOne() {
        StochasticTransparencySettings settings = StochasticTransparencySettings
                .builder().enabled(true).historyWeight(1.0f)
                .maximumHistoryLength(16).build();
        assertEquals(0.0, StochasticTransparencyReference.temporalWeight(settings, 0), 0.0);
        assertEquals(0.5, StochasticTransparencyReference.temporalWeight(settings, 1),
                1.0e-12);
        assertEquals(16.0 / 17.0,
                StochasticTransparencyReference.temporalWeight(settings, 16), 1.0e-12);
        assertEquals(16.0 / 17.0,
                StochasticTransparencyReference.temporalWeight(settings, 200), 1.0e-12);
        assertTrue(StochasticTransparencyReference.temporalWeight(settings, 200) < 1.0);

        StochasticTransparencySettings nonTemporal = settings.withTemporalAccumulation(false);
        assertEquals(0.0, StochasticTransparencyReference.temporalWeight(nonTemporal, 8), 0.0);
    }

    @Test
    void runningAverageConvergesToAConstantEstimate() {
        StochasticTransparencySettings settings = StochasticTransparencySettings
                .builder().enabled(true).historyWeight(1.0f)
                .maximumHistoryLength(32).build();
        double current = 0.3;
        double history = 0.0;
        int length = 0;
        for (int frame = 0; frame < 512; frame++) {
            double weight = StochasticTransparencyReference.temporalWeight(settings, length);
            history = StochasticTransparencyReference.accumulate(history, current, weight);
            length = Math.min(length + 1, 64);
            assertTrue(history >= 0.0 && history <= 1.0,
                    "the running average of a coverage signal must stay in [0, 1]");
        }
        assertEquals(0.3, history, 1.0e-12,
                "a constant estimate must converge to itself exactly");
        assertEquals(0.3, StochasticTransparencyReference.accumulate(0.3, 0.3, 0.9),
                1.0e-12);
        assertThrows(IllegalArgumentException.class,
                () -> StochasticTransparencyReference.accumulate(0.3, 0.3, 1.1));
    }

    @Test
    void hashUnitsAreUniformDeterministicAndSeedDependent() {
        int buckets = 8;
        long[] counts = new long[buckets];
        int pixels = 64;
        int frames = 16;
        long total = 0;
        double sum = 0.0;
        for (int frame = 0; frame < frames; frame++) {
            for (int y = 0; y < pixels; y++) {
                for (int x = 0; x < pixels; x++) {
                    double unit = StochasticTransparencyReference.unitHash(
                            x, y, 0, frame % 4, frame, 0x5EED_5EED);
                    assertTrue(unit >= 0.0 && unit < 1.0);
                    sum += unit;
                    counts[(int) (unit * buckets)]++;
                    total++;
                    assertEquals(unit, StochasticTransparencyReference.unitHash(
                            x, y, 0, frame % 4, frame, 0x5EED_5EED), 0.0,
                            "identical inputs must reproduce the same threshold");
                }
            }
        }
        assertEquals(0.5, sum / total, 0.01, "the hash must be centred near one half");
        double expected = (double) total / buckets;
        for (int bucket = 0; bucket < buckets; bucket++) {
            double fraction = counts[bucket] / (double) total;
            assertEquals(1.0 / buckets, fraction, 0.008,
                    "hash bucket " + bucket + " must be uniformly populated");
            assertTrue(Math.abs(counts[bucket] - expected) < expected * 0.12);
        }
        assertFalse(Double.compare(
                        StochasticTransparencyReference.unitHash(0, 0, 0, 0, 0, 0),
                        StochasticTransparencyReference.unitHash(0, 0, 0, 0, 0, 1)) == 0,
                "changing the seed must change the threshold sequence");
        assertFalse(Double.compare(
                        StochasticTransparencyReference.unitHash(0, 0, 0, 0, 0, 0),
                        StochasticTransparencyReference.unitHash(0, 0, 0, 1, 0, 0)) == 0,
                "changing the sample index must change the threshold sequence");
    }

    @Test
    void seedAndSampleOrderDoNotChangeTheExpectedAcceptanceRate() {
        double probability = 0.3;
        int pixels = 64;
        int frames = 8;
        for (int seed : new int[]{1, 2, 0x5EED_5EED}) {
            int accepted = 0;
            int total = 0;
            for (int frame = 0; frame < frames; frame++) {
                for (int y = 0; y < pixels; y++) {
                    for (int x = 0; x < pixels; x++) {
                        double unit = StochasticTransparencyReference.unitHash(
                                x, y, 0, frame % 2, frame, seed);
                        if (StochasticTransparencyReference.accepts(unit, probability)) {
                            accepted++;
                        }
                        total++;
                    }
                }
            }
            double rate = accepted / (double) total;
            assertEquals(probability, rate, 4.0 * Math.sqrt(
                    probability * (1.0 - probability) / total),
                    "seed " + seed + " must match the acceptance probability");
        }
        assertFalse(StochasticTransparencyReference.accepts(0.0, 0.0),
                "a zero probability must reject the zero threshold");
        assertTrue(StochasticTransparencyReference.accepts(0.0, 1.0),
                "a unit probability must accept every threshold");
    }

    @Test
    void gammaAndThresholdRemapTheExpectedCoverage() {
        StochasticTransparencySettings squared = StochasticTransparencySettings.builder()
                .enabled(true).coverageGamma(2.0f).build();
        List<StochasticTransparencyReference.Layer> layers =
                StochasticTransparencyReference.layers(squared, List.of(
                        new OitSurface(0.0f, 0.0f, -2.0f,
                                1.0f, 0.0f, 0.0f,
                                0.0f, 1.0f, 0.0f,
                                0.5f, 0.5f, 0.5f, 0.5f)));
        assertEquals(0.25, layers.get(0).probability(), 1.0e-12);
        assertEquals(0.25, StochasticTransparencyReference.expectedCoverage(layers),
                1.0e-12);

        StochasticTransparencySettings thresholded = StochasticTransparencySettings.builder()
                .enabled(true).alphaThreshold(0.5f).build();
        List<StochasticTransparencyReference.Layer> skipped =
                StochasticTransparencyReference.layers(thresholded, List.of(
                        new OitSurface(0.0f, 0.0f, -2.0f,
                                1.0f, 0.0f, 0.0f,
                                0.0f, 1.0f, 0.0f,
                                0.5f, 0.5f, 0.5f, 0.5f)));
        assertEquals(0.0, skipped.get(0).probability(), 0.0);
        assertEquals(0.0, StochasticTransparencyReference.expectedCoverage(skipped), 0.0);
    }

    @Test
    void depthToleranceMatchesTheProductionWindow() {
        StochasticTransparencySettings settings = StochasticTransparencySettings.builder()
                .enabled(true).depthRejectionThreshold(0.02f).build();
        assertEquals(settings.depthTolerance(10.0),
                StochasticTransparencyReference.depthTolerance(settings, 10.0), 1.0e-12);
        assertEquals(0.2, StochasticTransparencyReference.depthTolerance(settings, 10.0),
                1.0e-6);
        assertEquals(0.02, StochasticTransparencyReference.depthTolerance(settings, 0.0),
                1.0e-6,
                "the one-metre depth floor makes the near-camera window relative to one metre");
        StochasticTransparencySettings minimumWindow = StochasticTransparencySettings.builder()
                .enabled(true)
                .depthRejectionThreshold(
                        StochasticTransparencySettings.MINIMUM_DEPTH_REJECTION)
                .build();
        assertEquals((double) StochasticTransparencySettings.MINIMUM_DEPTH_REJECTION,
                StochasticTransparencyReference.depthTolerance(minimumWindow, 0.0),
                1.0e-11,
                "the float-stored floor and the documented 1e-4 differ only by float rounding");
        assertEquals(settings.depthTolerance(0.0),
                StochasticTransparencyReference.depthTolerance(settings, 0.0), 1.0e-11);
    }
}
