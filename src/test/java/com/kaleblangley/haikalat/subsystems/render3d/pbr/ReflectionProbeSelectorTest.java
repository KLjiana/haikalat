package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the GL-free probe selection/oracle contract. Expected weights are
 * recomputed here with an independent double-precision implementation of the
 * documented field; the production selector is only the actual value under
 * test. Box/sphere projection expectations are hand-computed geometry.
 */
class ReflectionProbeSelectorTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void pointOutsideEveryProbeFallsBackToTheGlobalEnvironment() {
        ReflectionProbeSelector.Selection selection = ReflectionProbeSelector.select(
                List.of(probe("a", 0.0f)), new Vector3f(8.0f, 0.0f, 0.0f));
        assertTrue(selection.isEmpty());
        assertEquals(1.0f, selection.globalWeight(), EPSILON);
        assertEquals(0, selection.count());
        assertTrue(ReflectionProbeSelector.select(List.of(),
                new Vector3f()).isEmpty());
    }

    @Test
    void influenceWeightMatchesTheIndependentFieldAtTheBoundaryAndBlendPoints() {
        ReflectionProbeSettings probe = probe("a", 0.0f);
        assertWeight(1.0f, probe, new Vector3f(0.0f, 0.0f, 0.0f));
        // Influence boundary: half weight by construction.
        assertWeight(0.5f, probe, new Vector3f(1.0f, 0.0f, 0.0f));
        // Halfway through the one-unit blend shell.
        assertWeight(0.25f, probe, new Vector3f(1.5f, 0.0f, 0.0f));
        assertWeight(0.0f, probe, new Vector3f(2.0f, 0.0f, 0.0f));
        assertWeight(0.0f, probe, new Vector3f(2.25f, 0.0f, 0.0f));
    }

    @Test
    void weightAndSelectionAreContinuousAcrossTheInfluenceBoundaryAndBlend() {
        ReflectionProbeSettings probe = probe("a", 0.0f);
        float previous = ReflectionProbeSelector.influenceWeight(probe,
                new Vector3f(0.0f, 0.0f, 0.0f));
        float worst = 0.0f;
        for (int step = 1; step <= 400; step++) {
            float x = step * 0.005f;
            float current = ReflectionProbeSelector.influenceWeight(probe,
                    new Vector3f(x, 0.0f, 0.0f));
            worst = Math.max(worst, Math.abs(current - previous));
            previous = current;
        }
        assertTrue(worst < 0.01f, "influence field jumped by " + worst);
    }

    @Test
    void overlappingProbesNormalizeSmoothlyAndPreferTheNearerProbe() {
        ReflectionProbeSettings first = probe("a", 0.0f);
        ReflectionProbeSettings second = probe("b", 0.5f);
        List<ReflectionProbeSettings> probes = List.of(first, second);
        ReflectionProbeSelector.Selection centre = ReflectionProbeSelector.select(probes,
                new Vector3f(0.0f, 0.0f, 0.0f));
        assertEquals(2, centre.count());
        assertEquals(0.0f, centre.globalWeight(), EPSILON);
        assertEquals(1.0f, centre.totalProbeWeight(), EPSILON);
        assertTrue(centre.probes().get(0).weight()
                > centre.probes().get(1).weight());
        assertEquals(0.5714286f, centre.probes().get(0).weight(), 1.0e-4f);
        assertEquals(0.4285714f, centre.probes().get(1).weight(), 1.0e-4f);

        ReflectionProbeSelector.Selection nearerSecond = ReflectionProbeSelector.select(probes,
                new Vector3f(1.2f, 0.0f, 0.0f));
        assertEquals(2, nearerSecond.count());
        assertEquals(1, nearerSecond.probes().get(0).probeIndex(),
                "the second probe must win near its own volume");
        assertTrue(nearerSecond.probes().get(0).weight()
                        > nearerSecond.probes().get(1).weight());

        ReflectionProbeSelector.Selection handover = ReflectionProbeSelector.select(probes,
                new Vector3f(0.25f, 0.0f, 0.0f));
        assertEquals(2, handover.count(),
                "the handover point must keep both probes selected");
        assertEquals(0.5f, handover.probes().get(0).weight(), 0.01f);
        assertEquals(0.5f, handover.probes().get(1).weight(), 0.01f);
    }

    @Test
    void equalWeightsUseDistanceThenDeclarationOrderAsTheDeterministicTieBreak() {
        ReflectionProbeSettings first = probe("a", 0.0f);
        ReflectionProbeSettings second = probe("b", 0.0f);
        ReflectionProbeSelector.Selection selection = ReflectionProbeSelector.select(
                List.of(first, second), new Vector3f(0.0f, 0.0f, 0.0f));
        assertEquals(0, selection.probes().get(0).probeIndex());
        assertEquals(1, selection.probes().get(1).probeIndex());
        assertEquals(0.5f, selection.probes().get(0).weight(), EPSILON);
        assertEquals(0.5f, selection.probes().get(1).weight(), EPSILON);
    }

    @Test
    void disabledAndMutedProbesAreIgnored() {
        ReflectionProbeSettings enabled = probe("a", 0.0f);
        ReflectionProbeSettings disabled = enabled.disabledCopy();
        ReflectionProbeSettings muted = new ReflectionProbeSettings("muted", true,
                new Vector3f(), ReflectionProbeSettings.Shape.BOX, new Vector3f(1.0f), 1.0f,
                1.0f, ReflectionProbeSettings.Quality.TEST, 0.0f, 0.0f, "hdr");
        assertTrue(ReflectionProbeSelector.select(List.of(disabled, muted),
                new Vector3f()).isEmpty());
        ReflectionProbeSelector.Selection onlyEnabled = ReflectionProbeSelector.select(
                List.of(disabled, enabled), new Vector3f());
        assertEquals(1, onlyEnabled.count());
        assertEquals(1, onlyEnabled.probes().getFirst().probeIndex());
        assertEquals(1.0f, onlyEnabled.probes().getFirst().weight(), EPSILON);
    }

    @Test
    void disabledSlotAtItsOwnCentreFallsBackToGlobalWhileTheActiveProbeStillSelects() {
        ReflectionProbeSettings active = probe("active", -0.5f);
        ReflectionProbeSettings disabled = ReflectionProbeSettings.box("disabled",
                new Vector3f(2.0f, 0.0f, 0.0f), new Vector3f(1.0f), 1.0f,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f, "hdr").disabledCopy();
        List<ReflectionProbeSettings> mixed = List.of(active, disabled);
        ReflectionProbeSelector.Selection atActive = ReflectionProbeSelector.select(mixed,
                new Vector3f(-0.5f, 0.0f, 0.0f));
        assertEquals(1, atActive.count());
        assertEquals(0, atActive.probes().getFirst().probeIndex());
        assertEquals(1.0f, atActive.probes().getFirst().weight(), EPSILON);
        ReflectionProbeSelector.Selection atDisabled = ReflectionProbeSelector.select(mixed,
                new Vector3f(2.0f, 0.0f, 0.0f));
        assertTrue(atDisabled.isEmpty(),
                "a disabled slot must never become a selected candidate");
        assertEquals(1.0f, atDisabled.globalWeight(), EPSILON);
    }

    @Test
    void sphereInfluenceMatchesTheDocumentedBoundaryAndBlendField() {
        ReflectionProbeSettings sphere = ReflectionProbeSettings.sphere("sphere",
                new Vector3f(), 1.0f, 1.0f, ReflectionProbeSettings.Quality.TEST,
                1.0f, 0.0f, "hdr");
        assertEquals(1.0f, ReflectionProbeSelector.influenceWeight(sphere,
                new Vector3f()), 1.0e-4f);
        assertEquals(0.5f, ReflectionProbeSelector.influenceWeight(sphere,
                new Vector3f(1.0f, 0.0f, 0.0f)), 1.0e-4f);
        assertEquals(0.25f, ReflectionProbeSelector.influenceWeight(sphere,
                new Vector3f(1.5f, 0.0f, 0.0f)), 1.0e-4f);
        assertEquals(0.0f, ReflectionProbeSelector.influenceWeight(sphere,
                new Vector3f(2.0f, 0.0f, 0.0f)), 1.0e-4f);
    }

    @Test
    void intensityAndOwnRotationDoNotChangeTheSelectionField() {
        ReflectionProbeSettings base = probe("a", 0.0f);
        ReflectionProbeSettings bright = new ReflectionProbeSettings("bright", true,
                new Vector3f(), ReflectionProbeSettings.Shape.BOX, new Vector3f(1.0f), 1.0f,
                1.0f, ReflectionProbeSettings.Quality.TEST, 4.0f, 1.25f, "hdr");
        assertEquals(ReflectionProbeSelector.influenceWeight(base, new Vector3f()),
                ReflectionProbeSelector.influenceWeight(bright, new Vector3f()), 1.0e-6f,
                "intensity and probe rotation affect shading, not the influence field");
        assertEquals(1, ReflectionProbeSelector.select(List.of(bright),
                new Vector3f()).count());
    }

    @Test
    void boxProjectionReturnsTheCentreToHitDirectionAndFallsBackOnAMiss() {
        ReflectionProbeSettings box = ReflectionProbeSettings.box("box", new Vector3f(),
                new Vector3f(1.0f), 0.0f, ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f,
                "hdr");
        assertDirection(new Vector3f(-1.0f, 0.0f, 0.0f), box, new Vector3f(-2.0f, 0.0f, 0.0f),
                new Vector3f(1.0f, 0.0f, 0.0f));
        assertDirection(new Vector3f(1.0f, 0.0f, 0.0f), box, new Vector3f(2.0f, 0.0f, 0.0f),
                new Vector3f(-1.0f, 0.0f, 0.0f));
        assertDirection(new Vector3f(0.4082483f, 0.4082483f, 0.8164966f), box,
                new Vector3f(0.5f, 0.5f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f));
        assertDirection(new Vector3f(0.0f, 1.0f, 0.0f), box,
                new Vector3f(-2.0f, 2.0f, 0.0f), new Vector3f(0.0f, 1.0f, 0.0f));
    }

    @Test
    void sphereProjectionUsesTheNearOrFarPositiveRoot() {
        ReflectionProbeSettings sphere = ReflectionProbeSettings.sphere("sphere",
                new Vector3f(), 1.0f, 0.0f, ReflectionProbeSettings.Quality.TEST,
                1.0f, 0.0f, "hdr");
        assertDirection(new Vector3f(0.0f, 0.0f, 1.0f), sphere,
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f));
        assertDirection(new Vector3f(0.0f, 0.0f, 1.0f), sphere,
                new Vector3f(0.0f, 0.0f, 1.5f), new Vector3f(0.0f, 0.0f, -1.0f));
        assertDirection(new Vector3f(0.0f, 1.0f, 0.0f), sphere,
                new Vector3f(2.0f, 0.0f, 0.0f), new Vector3f(0.0f, 1.0f, 0.0f));
    }

    @Test
    void invalidInputsAreRejectedInsteadOfProducingNaN() {
        ReflectionProbeSettings probe = probe("a", 0.0f);
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSelector.select(
                List.of(probe), new Vector3f(Float.NaN, 0.0f, 0.0f)));
        assertThrows(IllegalArgumentException.class,
                () -> ReflectionProbeSelector.localCorrectionDirection(probe,
                        new Vector3f(), new Vector3f(), new Vector3f()));
        assertThrows(IllegalArgumentException.class,
                () -> ReflectionProbeSelector.localCorrectionDirection(probe,
                        new Vector3f(), new Vector3f(Float.NaN, 0.0f, 0.0f), new Vector3f()));
        assertThrows(NullPointerException.class, () -> ReflectionProbeSelector.select(
                List.of(probe), null));
        assertThrows(IllegalArgumentException.class, () -> ReflectionProbeSelector.select(
                List.of(probe("a", 0.0f), probe("b", 1.0f), probe("c", 2.0f)),
                new Vector3f()));
    }

    private static void assertWeight(float expected, ReflectionProbeSettings probe,
                                     Vector3f point) {
        float actual = ReflectionProbeSelector.influenceWeight(probe, point);
        assertEquals(expected, actual, 1.0e-4f,
                "weight at " + point + " does not match the independent field "
                        + referenceWeight(probe, point));
        assertEquals(referenceWeight(probe, point), actual, 1.0e-4f);
    }

    /** Independent scalar implementation of the documented weight field. */
    private static double referenceWeight(ReflectionProbeSettings probe, Vector3f point) {
        double px = point.x - probe.position().x;
        double py = point.y - probe.position().y;
        double pz = point.z - probe.position().z;
        double outside;
        double coreDepth;
        if (probe.shape() == ReflectionProbeSettings.Shape.SPHERE) {
            outside = Math.sqrt(px * px + py * py + pz * pz) - probe.radius();
            coreDepth = probe.radius();
        } else {
            Vector3f half = probe.halfExtents();
            double dx = Math.abs(px) - half.x;
            double dy = Math.abs(py) - half.y;
            double dz = Math.abs(pz) - half.z;
            double positive = Math.sqrt(Math.max(dx, 0.0) * Math.max(dx, 0.0)
                    + Math.max(dy, 0.0) * Math.max(dy, 0.0)
                    + Math.max(dz, 0.0) * Math.max(dz, 0.0));
            outside = positive + Math.min(Math.max(dx, Math.max(dy, dz)), 0.0);
            coreDepth = Math.min(half.x, Math.min(half.y, half.z));
        }
        if (outside <= 0.0) {
            double inset = Math.max(0.0, Math.min(1.0, -outside / coreDepth));
            return 0.5 + 0.5 * inset * inset * (3.0 - 2.0 * inset);
        }
        if (!(probe.blendDistance() > 0.0)) return 0.0;
        double t = Math.max(0.0, Math.min(1.0, outside / probe.blendDistance()));
        return 0.5 * (1.0 - t * t * (3.0 - 2.0 * t));
    }

    private static void assertDirection(Vector3f expected, ReflectionProbeSettings probe,
                                        Vector3f point, Vector3f direction) {
        Vector3f actual = ReflectionProbeSelector.localCorrectionDirection(probe, point,
                direction, new Vector3f());
        assertEquals(expected.x, actual.x, 1.0e-4f);
        assertEquals(expected.y, actual.y, 1.0e-4f);
        assertEquals(expected.z, actual.z, 1.0e-4f);
        assertEquals(1.0f, actual.length(), 1.0e-4f);
    }

    private static ReflectionProbeSettings probe(String name, float x) {
        return ReflectionProbeSettings.box(name, new Vector3f(x, 0.0f, 0.0f),
                new Vector3f(1.0f), 1.0f, ReflectionProbeSettings.Quality.TEST,
                1.0f, 0.0f, "/environments/pbr/probe-warm.hdr");
    }
}
