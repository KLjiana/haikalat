package com.kaleblangley.haikalat.subsystems.postprocess.ssr;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-computed contracts for {@link SsrRayReference}, the test-only mirror of
 * the documented screen-space reflection march.
 *
 * <p>Every expected number is derived analytically in the comments from the
 * fixture geometry (uniform world steps, view-depth separation, border distance
 * and the confidence product).  The production shader is not called here; the
 * real-GL test supplies the pixel-level evidence.</p>
 */
class SsrRayReferenceTest {
    private static final int WIDTH = 100;
    private static final int HEIGHT = 100;
    private static final float MAX_DISTANCE = 2.5f;
    private static final int STEPS = 10;

    /** u = 0.5 + 0.2 x, v = 0.5 + 0.2 y; outside [0,1]^2 is offscreen. */
    private static final SsrRayReference.Projector PROJECTOR = (position, destination) -> {
        float u = 0.5f + position.x() * 0.2f;
        float v = 0.5f + position.y() * 0.2f;
        destination.set(u, v);
        return u >= 0.0f && u <= 1.0f && v >= 0.0f && v <= 1.0f;
    };

    /** Stops when v leaves the screen; used to prove the offscreen rejection. */
    private static final SsrRayReference.Projector HALF_SCREEN = (position, destination) -> {
        float u = 0.5f + position.x() * 0.2f;
        float v = 0.5f + position.y() * 0.2f;
        destination.set(u, v);
        return u >= 0.0f && u <= 1.0f && v >= 0.0f && v <= 0.5f;
    };

    @Test
    void uniformWorldStepsAreBoundedAndProjectToKnownScreenPositions() {
        // direction (0, 0.6, -0.8) => v(t) = 0.5 + 0.3 t, depth(t) = 4 + 2 t.
        List<Float> inverseT = new ArrayList<>();
        SsrRayReference.Probe probe = probe(100.0f, 100.0f, neverHit());
        SsrRayReference.trace(new Vector3f(0.0f, 0.0f, -4.0f),
                new Vector3f(0.0f, 0.6f, -0.8f), 1.0f,
                (position, destination) -> {
                    boolean onScreen = PROJECTOR.project(position, destination);
                    inverseT.add((destination.y - 0.5f) / 0.3f);
                    return onScreen;
                }, probe,
                new SsrRayReference.March(MAX_DISTANCE, STEPS, 1.0f, 0.02f),
                WIDTH, HEIGHT, 20.0f, 0.15f);

        assertEquals(STEPS, inverseT.size(), "one projection per bounded step");
        for (int index = 0; index < inverseT.size(); index++) {
            assertEquals((index + 1) / (float) STEPS, inverseT.get(index), 1.0e-5f,
                    "world steps must be uniform fractions of maxDistance");
        }
    }

    @Test
    void hitUsesTheCrossingDepthAndTheDocumentedConfidenceProduct() {
        // v >= 0.7 starts at t = (0.7 - 0.5) / 0.3 = 0.6667 => step 7 (t = 0.7).
        // At step 7 depth = 4 + 2 * 0.7 = 5.4 and the probe stores 5.1, so the
        // separation is 0.3 inside a 1.0 thickness window.
        // depthConfidence = 1 - 0.3 / 1.0 = 0.7
        // edge: u = 0.5, v = 0.71 -> 29 px from the bottom border, fade 20 -> 1
        // facing = -dot((0,0,1), (0,0.6,-0.8)) = 0.8
        // grazing = smoothstep(0.05, 0.25, 1) = 1, distance = 1 - smoothstep(0.75,1,0.7) = 1
        // confidence = 0.7 * 0.8 = 0.56
        SsrRayReference.Result result = SsrRayReference.trace(
                new Vector3f(0.0f, 0.0f, -4.0f), new Vector3f(0.0f, 0.6f, -0.8f), 1.0f,
                PROJECTOR, probe(100.0f, 5.1f, wallFromV(0.7f)),
                new SsrRayReference.March(MAX_DISTANCE, STEPS, 1.0f, 0.02f),
                WIDTH, HEIGHT, 20.0f, 0.15f);

        assertTrue(result.hit(), "a crossing inside the thickness window must hit");
        assertEquals(7, result.samples(), "the march must stop at the first crossing");
        assertEquals(0.3f, result.separation(), 1.0e-4f);
        assertEquals(0.56f, result.confidence(), 1.0e-4f);
        assertEquals(0.5f, result.u(), 1.0e-5f);
        assertEquals(0.71f, result.v(), 1.0e-5f,
                "the reflected ray must move toward the upper screen region");
        // weight = 0.56 * smoothstep(0.15, 1, 0.56) = 0.56 * 0.473546 = 0.265186
        assertEquals(0.26519f, result.weight(), 1.0e-4f);
    }

    @Test
    void offscreenRayIsRejectedWithoutAHit() {
        SsrRayReference.Result result = SsrRayReference.trace(
                new Vector3f(0.0f, 0.0f, -4.0f), new Vector3f(0.0f, 0.6f, -0.8f), 1.0f,
                HALF_SCREEN, probe(100.0f, 5.1f, wallFromV(0.7f)),
                new SsrRayReference.March(MAX_DISTANCE, STEPS, 1.0f, 0.02f),
                WIDTH, HEIGHT, 20.0f, 0.15f);

        assertFalse(result.hit());
        assertEquals(0.0f, result.weight());
        assertEquals(1, result.samples(), "the march must stop on the first offscreen sample");
    }

    @Test
    void separationBeyondTheThicknessIsOcclusionNotAReflection() {
        // Same crossing but the stored surface is 1.0 instead of 5.1:
        // separation = 5.4 - 1.0 = 4.4 > 1.0 => occluded, no hit.
        SsrRayReference.Result result = SsrRayReference.trace(
                new Vector3f(0.0f, 0.0f, -4.0f), new Vector3f(0.0f, 0.6f, -0.8f), 1.0f,
                PROJECTOR, probe(100.0f, 1.0f, wallFromV(0.7f)),
                new SsrRayReference.March(MAX_DISTANCE, STEPS, 1.0f, 0.02f),
                WIDTH, HEIGHT, 20.0f, 0.15f);

        assertFalse(result.hit(), "a thick occluder must not become a reflection");
        assertEquals(7, result.samples());
    }

    @Test
    void confidenceBelowTheFloorKeepsTheExactEnvironmentFallback() {
        // Case 1 has confidence 0.56; a 0.6 floor rejects it.
        SsrRayReference.Result result = SsrRayReference.trace(
                new Vector3f(0.0f, 0.0f, -4.0f), new Vector3f(0.0f, 0.6f, -0.8f), 1.0f,
                PROJECTOR, probe(100.0f, 5.1f, wallFromV(0.7f)),
                new SsrRayReference.March(MAX_DISTANCE, STEPS, 1.0f, 0.02f),
                WIDTH, HEIGHT, 20.0f, 0.6f);

        assertFalse(result.hit());
        assertEquals(0.0f, result.weight());

        Vector3f base = new Vector3f(0.2f, 0.3f, 0.4f);
        Vector3f environment = new Vector3f(1.5f, 0.7f, 0.9f);
        Vector3f composite = SsrRayReference.composite(base, environment,
                new Vector3f(32.0f, 0.0f, 0.0f), 0.3f, 0.9f, result.weight());
        assertEquals(base.x, composite.x, 0.0f,
                "a rejected hit must leave the forward environment colour bit-exact");
        assertEquals(base.y, composite.y, 0.0f);
        assertEquals(base.z, composite.z, 0.0f);
    }

    @Test
    void roughBackfacingAndGrazingReceiversAreIneligible() {
        assertTrue(SsrRayReference.eligible(0.2f, 0.4f, 0.6f));
        assertFalse(SsrRayReference.eligible(0.5f, 0.4f, 0.6f),
                "roughness above the cutoff must fall back");
        assertFalse(SsrRayReference.eligible(0.1f, 0.4f, 0.02f),
                "a grazing receiver must fall back");
        assertFalse(SsrRayReference.eligible(0.1f, 0.4f, 0.0f),
                "a backfacing receiver must fall back");
    }

    @Test
    void grazingAngleReducesConfidenceAndSmallEdgeDistanceFadesIt() {
        float straight = SsrRayReference.confidence(0.1f, 1.0f, 40.0f, 20.0f, 1.0f, 1.0f, 0.5f);
        float grazing = SsrRayReference.confidence(0.1f, 1.0f, 40.0f, 20.0f, 1.0f, 0.1f, 0.5f);
        float edge = SsrRayReference.confidence(0.1f, 1.0f, 5.0f, 20.0f, 1.0f, 1.0f, 0.5f);
        assertEquals(0.9f, straight, 1.0e-5f,
                "depth confidence 0.9 with every other term saturated");
        assertTrue(grazing < straight, "grazing must be less confident");
        assertTrue(edge < straight, "a hit near the border must fade");
        assertEquals(0.0f, SsrRayReference.hitWeight(0.2f, 0.3f), 0.0f);
    }

    @Test
    void environmentReplacementAtFullWeightReplacesTheEnvironmentTerm() {
        // base already contains the forward IBL specular (environment). A full
        // replacement is base - environment + hitRadiance.
        Vector3f base = new Vector3f(0.1f, 0.2f, 0.3f);
        Vector3f environment = new Vector3f(0.1f, 0.2f, 0.3f);
        Vector3f hit = new Vector3f(2.0f, 1.0f, 0.5f);
        Vector3f composite = SsrRayReference.composite(base, environment, hit, 1.0f, 1.0f, 1.0f);
        assertEquals(hit.x, composite.x, 1.0e-6f);
        assertEquals(hit.y, composite.y, 1.0e-6f);
        assertEquals(hit.z, composite.z, 1.0e-6f);

        Vector3f differentEnvironment = new Vector3f(0.4f, 0.5f, 0.6f);
        Vector3f shifted = SsrRayReference.composite(base, differentEnvironment, hit,
                1.0f, 1.0f, 1.0f);
        assertEquals(1.7f, shifted.x, 1.0e-6f,
                "0.1 - 0.4 + 2.0 replaces exactly one environment contribution");
        assertEquals(0.7f, shifted.y, 1.0e-6f);
        assertEquals(0.2f, shifted.z, 1.0e-6f);
        assertEquals(0.04f, SsrRayReference.fresnelSchlickRoughness(1.0f, 0.04f, 0.2f),
                1.0e-6f, "head-on dielectric Fresnel is f0");
        assertTrue(SsrRayReference.fresnelSchlickRoughness(0.0f, 0.04f, 0.2f) > 0.5f,
                "grazing Fresnel must approach one");
    }

    private static SsrRayReference.Probe probe(float beforeDepth, float afterDepth,
                                               java.util.function.DoublePredicate wall) {
        Vector3f normal = new Vector3f(0.0f, 0.0f, 1.0f);
        return new SsrRayReference.Probe() {
            @Override
            public float viewDepth(float u, float v) {
                return wall.test(v) ? afterDepth : beforeDepth;
            }

            @Override
            public Vector3fc viewNormal(float u, float v) {
                return normal;
            }
        };
    }

    private static java.util.function.DoublePredicate wallFromV(float start) {
        return value -> value >= start;
    }

    private static java.util.function.DoublePredicate neverHit() {
        return value -> false;
    }

}
