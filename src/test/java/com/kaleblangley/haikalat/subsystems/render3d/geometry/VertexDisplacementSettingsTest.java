package com.kaleblangley.haikalat.subsystems.render3d.geometry;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM oracle for the local sinusoidal displacement model: bounded offsets,
 * deterministic time/phase, analytic normals checked against an independent
 * finite-difference height field, and the exact default-off no-op.
 */
class VertexDisplacementSettingsTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void disabledIsExactNoOp() {
        VertexDisplacementSettings disabled = VertexDisplacementSettings.disabled();
        Vector3f position = new Vector3f(0.3f, -0.7f, 2.0f);
        Vector3f normal = new Vector3f(0.0f, 1.0f, 0.0f);
        Vector3f offset = new Vector3f(9.0f);
        Vector3f displaced = new Vector3f(9.0f);
        Vector3f displacedNormal = new Vector3f(9.0f);

        assertFalse(disabled.active());
        assertEquals(0.0f, disabled.maxOffset());
        disabled.offset(position, 12.5f, offset);
        disabled.displacedPosition(position, 12.5f, displaced);
        disabled.displacedNormal(position, normal, 12.5f, displacedNormal);
        assertEquals(0.0f, offset.length());
        assertEquals(position, displaced);
        assertEquals(normal, displacedNormal);
    }

    @Test
    void amplitudeFrequencyAndDirectionAreNormalizedAndBounded() {
        VertexDisplacementSettings settings = new VertexDisplacementSettings(true,
                0.2f, 3.0f, -2.0f, 1.0f,
                new Vector3f(0.0f, 3.0f, 0.0f), new Vector3f(4.0f, 0.0f, 0.0f));
        assertEquals(1.0f, settings.axis().length(), EPSILON);
        assertEquals(1.0f, settings.waveDirection().length(), EPSILON);
        assertTrue(settings.active());
        assertEquals(0.2f, settings.maxOffset(), EPSILON);

        assertThrows(IllegalArgumentException.class, () -> new VertexDisplacementSettings(true,
                VertexDisplacementSettings.MAX_AMPLITUDE + 0.01f, 1.0f, 0.0f, 0.0f,
                new Vector3f(0, 1, 0), new Vector3f(1, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new VertexDisplacementSettings(true,
                -0.01f, 1.0f, 0.0f, 0.0f,
                new Vector3f(0, 1, 0), new Vector3f(1, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new VertexDisplacementSettings(true,
                0.1f, VertexDisplacementSettings.MAX_FREQUENCY + 0.01f, 0.0f, 0.0f,
                new Vector3f(0, 1, 0), new Vector3f(1, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new VertexDisplacementSettings(true,
                0.1f, 1.0f, VertexDisplacementSettings.MAX_SPEED + 0.01f, 0.0f,
                new Vector3f(0, 1, 0), new Vector3f(1, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new VertexDisplacementSettings(true,
                0.1f, 1.0f, 0.0f, Float.NaN,
                new Vector3f(0, 1, 0), new Vector3f(1, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new VertexDisplacementSettings(true,
                0.1f, 1.0f, 0.0f, 0.0f,
                new Vector3f(), new Vector3f(1, 0, 0)));
        assertThrows(NullPointerException.class, () -> new VertexDisplacementSettings(true,
                0.1f, 1.0f, 0.0f, 0.0f, null, new Vector3f(1, 0, 0)));
    }

    @Test
    void offsetIsBoundedForAllSampledPositionsTimesAndPhases() {
        VertexDisplacementSettings settings = VertexDisplacementSettings.localSinusoidal(
                0.18f, 2.0f, 1.5f, 0.4f);
        Vector3f position = new Vector3f();
        Vector3f offset = new Vector3f();
        for (int x = -32; x <= 32; x++) {
            for (int t = 0; t <= 16; t++) {
                position.set(x * 0.37f, (x % 5) * 0.11f, x * -0.19f);
                settings.offset(position, t * 0.61f, offset);
                assertTrue(offset.length() <= settings.amplitude() + EPSILON,
                        "offset must stay within the authored amplitude");
            }
        }
    }

    @Test
    void sameTimeAndPhaseReproduceTheSameDeformation() {
        VertexDisplacementSettings settings = VertexDisplacementSettings.localSinusoidal(
                0.12f, 1.25f, 0.75f, 0.9f);
        Vector3f position = new Vector3f(1.1f, -0.2f, 0.4f);
        Vector3f first = settings.displacedPosition(position, 2.25f, new Vector3f());
        Vector3f second = settings.displacedPosition(position, 2.25f, new Vector3f());
        Vector3f later = settings.displacedPosition(position, 2.25f + 1.0f / 0.75f, new Vector3f());
        assertEquals(first.x, second.x, 0.0f);
        assertEquals(first.y, second.y, 0.0f);
        assertEquals(first.z, second.z, 0.0f);
        // speed is in cycles/second: adding one cycle period of time repeats it.
        assertEquals(first.x, later.x, 2.0e-5f);
        assertEquals(first.y, later.y, 2.0e-5f);
        assertEquals(first.z, later.z, 2.0e-5f);
    }

    @Test
    void analyticNormalMatchesIndependentHeightFieldFiniteDifference() {
        VertexDisplacementSettings settings = new VertexDisplacementSettings(true,
                0.15f, 1.2f, 0.5f, 0.3f,
                new Vector3f(0, 1, 0), new Vector3f(1, 0, 0));
        float time = 0.8f;
        float step = 1.0e-4f;
        for (float x = -1.5f; x <= 1.5f; x += 0.25f) {
            Vector3f position = new Vector3f(x, 0.0f, 0.0f);
            Vector3f actual = settings.displacedNormal(position, new Vector3f(0, 1, 0),
                    time, new Vector3f());
            double slope = (height(settings, x + step, time) - height(settings, x - step, time))
                    / (2.0 * step);
            Vector3f expected = new Vector3f((float) -slope, 1.0f, 0.0f).normalize();
            assertEquals(expected.x, actual.x, 2.0e-4f);
            assertEquals(expected.y, actual.y, 2.0e-4f);
            assertEquals(expected.z, actual.z, 2.0e-4f);
        }
    }

    @Test
    void degenerateNormalFallsBackToInputAndReturnedVectorsAreDefensive() {
        VertexDisplacementSettings settings = VertexDisplacementSettings.localSinusoidal(
                0.1f, 1.0f, 1.0f, 0.0f);
        Vector3f zero = settings.displacedNormal(new Vector3f(), new Vector3f(), 0.5f,
                new Vector3f());
        assertEquals(0.0f, zero.length());

        Vector3f axis = settings.axis();
        axis.set(9.0f, 9.0f, 9.0f);
        assertEquals(1.0f, settings.axis().length(), EPSILON,
                "record axis accessor must not expose internal state");
        Vector3f direction = settings.waveDirection();
        direction.set(0.0f);
        assertEquals(1.0f, settings.waveDirection().length(), EPSILON,
                "record wave-direction accessor must not expose internal state");
    }

    private static double height(VertexDisplacementSettings settings, float x, float time) {
        double phase = 2.0 * Math.PI * (settings.frequency() * x + settings.speed() * time)
                + settings.phase();
        return settings.amplitude() * Math.sin(phase);
    }
}
