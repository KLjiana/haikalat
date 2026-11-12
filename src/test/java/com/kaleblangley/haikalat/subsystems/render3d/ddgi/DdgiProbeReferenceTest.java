package com.kaleblangley.haikalat.subsystems.render3d.ddgi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-input contract for the independent DDGI probe weighting, visibility,
 * temporal blend and budget reference.
 *
 * <p>Expected values are written here from the documented formulas, not read
 * back from the reference implementation. The same cases pin the shared shader
 * formula in {@code ddgi-common.glsl}; the real-GL class is the only place that
 * can prove the GPU agrees.</p>
 */
class DdgiProbeReferenceTest {
    @Test
    void singleProbeAtTheReceiverReturnsThatProbeRadiance() {
        DdgiSettings settings = DdgiSettings.builder()
                .enabled(true).gridOrigin(0.0f, 0.0f, 0.0f).spacingMetres(4.0f)
                .probes(1, 1, 1).maximumRayDistance(8.0f).borderFadeMetres(0.25f).build();
        double[] radiance = {3.5};
        assertEquals(3.5, DdgiProbeReference.sample(settings, 2.0, 1.0, 2.0,
                0.0, 1.0, 0.0, radiance, DdgiProbeReference.NO_OCCLUDER), 1.0e-9);
        List<DdgiProbeReference.ProbeWeight> weights = DdgiProbeReference.weights(
                settings, 2.0, 1.0, 2.0, 0.0, 1.0, 0.0);
        assertEquals(8, weights.size());
        double total = 0.0;
        for (DdgiProbeReference.ProbeWeight weight : weights) {
            assertEquals(0, weight.probe());
            total += weight.weight();
        }
        assertEquals(1.0, total, 1.0e-12);
    }

    @Test
    void midpointBetweenTwoProbesSplitsWeightEvenly() {
        DdgiSettings settings = DdgiSettings.builder()
                .enabled(true).gridOrigin(0.0f, 0.0f, 0.0f).spacingMetres(2.0f)
                .probes(2, 1, 1).maximumRayDistance(4.0f).borderFadeMetres(0.25f).build();
        double[] radiance = {2.0, 6.0};
        assertEquals(4.0, DdgiProbeReference.sample(settings, 2.0, 0.5, 1.0,
                0.0, 1.0, 0.0, radiance, DdgiProbeReference.NO_OCCLUDER), 1.0e-9);

        List<DdgiProbeReference.ProbeWeight> weights = DdgiProbeReference.weights(
                settings, 2.0, 0.5, 1.0, 0.0, 1.0, 0.0);
        double probeZero = 0.0;
        double probeOne = 0.0;
        for (DdgiProbeReference.ProbeWeight weight : weights) {
            if (weight.probe() == 0) probeZero += weight.weight();
            if (weight.probe() == 1) probeOne += weight.weight();
        }
        assertEquals(probeZero, probeOne, 1.0e-12);
        assertEquals(0.5, probeZero / (probeZero + probeOne), 1.0e-12);
    }

    @Test
    void cornerClampingKeepsRawWeightsNormalizedAtTheGridEdge() {
        DdgiSettings settings = DdgiSettings.builder()
                .enabled(true).gridOrigin(0.0f, 0.0f, 0.0f).spacingMetres(2.0f)
                .probes(2, 1, 1).maximumRayDistance(4.0f).borderFadeMetres(0.5f).build();
        List<DdgiProbeReference.ProbeWeight> weights = DdgiProbeReference.weights(
                settings, 3.99, 0.5, 1.0, 0.0, 1.0, 0.0);
        double total = 0.0;
        for (DdgiProbeReference.ProbeWeight weight : weights) {
            total += weight.weight();
        }
        double expectedNormalWeight = 0.5 / Math.sqrt(0.99 * 0.99 + 0.5 * 0.5);
        assertEquals(expectedNormalWeight, total, 1.0e-12,
                "clamped duplicate corners must still sum to one trilinear weight"
                        + " times the normal-side gate");
    }

    @Test
    void outsideProbeBoundsIsExactlyZero() {
        DdgiSettings settings = DdgiSettings.builder()
                .enabled(true).gridOrigin(0.0f, 0.0f, 0.0f).spacingMetres(2.0f)
                .probes(2, 1, 1).maximumRayDistance(4.0f).borderFadeMetres(0.5f).build();
        assertEquals(0.0, DdgiProbeReference.borderWeight(settings, 4.5, 1.0, 1.0), 0.0);
        assertEquals(0.2, DdgiProbeReference.borderWeight(settings, 3.9, 1.0, 1.0), 1.0e-9,
                "0.1 m inside a 0.5 m fade is a 0.2 border weight");
        assertEquals(0.0, DdgiProbeReference.sample(settings, 4.5, 1.0, 1.0,
                0.0, 1.0, 0.0, new double[]{5.0, 5.0}, DdgiProbeReference.NO_OCCLUDER), 0.0);
    }

    @Test
    void probesBehindTheNormalDoNotContribute() {
        DdgiSettings settings = DdgiSettings.builder()
                .enabled(true).gridOrigin(0.0f, 0.0f, 0.0f).spacingMetres(2.0f)
                .probes(1, 1, 1).maximumRayDistance(4.0f).borderFadeMetres(0.25f).build();
        assertEquals(0.0, DdgiProbeReference.sample(settings, 1.0, 3.0, 1.0,
                0.0, 1.0, 0.0, new double[]{7.0}, DdgiProbeReference.NO_OCCLUDER), 0.0);
    }

    @Test
    void visibilityIsAHardBoundedGate() {
        DdgiSettings settings = DdgiSettings.builder()
                .enabled(true).gridOrigin(0.0f, 0.0f, 0.0f).spacingMetres(2.0f)
                .probes(1, 1, 1).maximumRayDistance(4.0f).build();
        assertEquals(0.0, DdgiProbeReference.visibility(settings, 0, 3.0, 1.0, 1.0,
                (probe, x, y, z) -> 1.8), 1.0e-9,
                "0.2 m behind the recorded surface exceeds the 0.1 m tolerance");
        assertEquals(1.0, DdgiProbeReference.visibility(settings, 0, 3.0, 1.0, 1.0,
                (probe, x, y, z) -> 1.95), 1.0e-9);
        assertEquals(0.0, DdgiProbeReference.visibility(settings, 0, 3.0, 1.0, 1.0,
                (probe, x, y, z) -> 0.0), 1.0e-9);
        assertEquals(1.0, DdgiProbeReference.visibility(settings, 0, 1.0, 1.0, 1.0,
                (probe, x, y, z) -> 0.0), 1.0e-9,
                "a receiver at the probe centre is always visible");
    }

    @Test
    void visibilityGatesTheIndirectSample() {
        DdgiSettings settings = DdgiSettings.builder()
                .enabled(true).gridOrigin(0.0f, 0.0f, 0.0f).spacingMetres(2.0f)
                .probes(2, 2, 2).maximumRayDistance(8.0f).borderFadeMetres(0.05f).build();
        double[] radiance = {4.0, 4.0, 4.0, 4.0, 4.0, 4.0, 4.0, 4.0};
        double visible = DdgiProbeReference.sample(settings, 3.9, 3.9, 3.9,
                -1.0, 0.0, 0.0, radiance, DdgiProbeReference.NO_OCCLUDER);
        double occluded = DdgiProbeReference.sample(settings, 3.9, 3.9, 3.9,
                -1.0, 0.0, 0.0, radiance, (probe, x, y, z) -> 0.0);
        assertEquals(4.0, visible, 1.0e-9);
        assertEquals(0.0, occluded, 1.0e-9,
                "a receiver behind every recorded probe surface must receive exactly nothing");
    }

    @Test
    void hysteresisBlendsAndClampsOnFixedInputs() {
        assertEquals(4.0, DdgiProbeReference.hysteresis(2.0, 6.0, 0.5, 32.0), 1.0e-12);
        assertEquals(6.0, DdgiProbeReference.hysteresis(2.0, 6.0, 0.0, 32.0), 1.0e-12);
        assertEquals(2.0, DdgiProbeReference.hysteresis(2.0, 6.0, 1.0, 32.0), 1.0e-12);
        assertEquals(5.0, DdgiProbeReference.hysteresis(10.0, 10.0, 0.5, 5.0), 1.0e-12);
        assertEquals(0.0, DdgiProbeReference.hysteresis(-3.0, 1.0, 0.5, 5.0), 1.0e-12,
                "negative history must not produce negative incident radiance");
    }

    @Test
    void singleRayUpdateMatchesTheDocumentedDirectRadiance() {
        DdgiSettings settings = DdgiSettings.builder()
                .enabled(true).gridOrigin(0.0f, 0.0f, 0.0f).spacingMetres(2.0f)
                .probes(1, 1, 1).maximumRayDistance(4.0f).raySteps(16)
                .skyRadiance(0.1f, 0.1f, 0.1f).build();
        double albedo = 0.8;
        double lightRadiance = 2.0;
        double expected = albedo * (1.0 / Math.PI) * lightRadiance;
        assertEquals(expected, DdgiProbeReference.updateRay(settings, 0,
                0.0, -1.0, 0.0, (probe, x, y, z) -> 1.0,
                (x, y, z) -> new double[]{0.0, 1.0, 0.0},
                0.0, -1.0, 0.0, lightRadiance, albedo, 0.1), 1.0e-12);
        assertEquals(0.1, DdgiProbeReference.updateRay(settings, 0,
                0.0, -1.0, 0.0, (probe, x, y, z) -> Double.POSITIVE_INFINITY,
                (x, y, z) -> new double[]{0.0, 1.0, 0.0},
                0.0, -1.0, 0.0, lightRadiance, albedo, 0.1), 1.0e-12,
                "a ray without a hit must return the bounded sky term");
        assertEquals(0.0, DdgiProbeReference.updateRay(settings, 0,
                0.0, -1.0, 0.0, (probe, x, y, z) -> 1.0,
                (x, y, z) -> new double[]{0.0, -1.0, 0.0},
                0.0, -1.0, 0.0, lightRadiance, albedo, 0.1), 1.0e-12,
                "a surface facing away from the light receives no direct term and no sky");
        assertThrows(IllegalArgumentException.class,
                () -> DdgiProbeReference.updateRay(settings, 0,
                        0.0, 0.0, 0.0, (probe, x, y, z) -> 1.0,
                        (x, y, z) -> new double[]{0.0, 1.0, 0.0},
                        0.0, -1.0, 0.0, lightRadiance, albedo, 0.1));
    }

    @Test
    void referenceScheduleMatchesTheProductionBudgetOnFixedInputs() {
        int[] destination = new int[8];
        for (int cursor = -1; cursor < 6; cursor++) {
            int count = DdgiProbeBudget.writeSchedule(6, 3, cursor, destination);
            int[] expected = DdgiProbeReference.schedule(6, 3, cursor);
            assertEquals(expected.length, count);
            assertArrayEquals(expected, java.util.Arrays.copyOf(destination, count));
        }
    }

    @Test
    void productionBorderClampMatchesTheReferenceOnFixedInputs() {
        DdgiSettings settings = DdgiSettings.builder()
                .enabled(true).gridOrigin(-1.0f, -2.0f, -3.0f).spacingMetres(1.5f)
                .probes(2, 3, 2).maximumRayDistance(6.0f).borderFadeMetres(0.4f).build();
        float[][] points = {
                {-1.0f, -2.0f, -3.0f},
                {0.5f, 0.25f, -1.5f},
                {1.9f, 2.4f, 0.0f},
                {3.0f, 2.5f, 0.0f},
                {-1.2f, 0.0f, 0.0f}};
        for (float[] point : points) {
            assertEquals(DdgiProbeReference.borderWeight(settings, point[0], point[1], point[2]),
                    settings.borderWeight(point[0], point[1], point[2]), 1.0e-6,
                    "border clamp mismatch at " + java.util.Arrays.toString(point));
        }
        assertTrue(settings.borderWeight(0.5f, 0.25f, -1.5f) > 0.0f);
    }

    @Test
    void referenceRejectsAZeroNormal() {
        DdgiSettings settings = DdgiSettings.builder()
                .enabled(true).gridOrigin(0.0f, 0.0f, 0.0f).spacingMetres(2.0f)
                .probes(1, 1, 1).maximumRayDistance(4.0f).build();
        assertThrows(IllegalArgumentException.class,
                () -> DdgiProbeReference.weights(settings, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0));
    }
}
