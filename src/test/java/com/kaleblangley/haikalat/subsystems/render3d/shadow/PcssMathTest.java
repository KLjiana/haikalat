package com.kaleblangley.haikalat.subsystems.render3d.shadow;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the PCSS CPU contract.  Expected values come from an independent
 * reference implementation in this test (a separate arrangement of the
 * documented equations) and from hand-computed samples, never from the
 * production helper's own output.
 */
class PcssMathTest {
    private static final double GOLDEN_ANGLE =
            Math.PI * (3.0 - Math.sqrt(5.0));

    @Test
    void diskTapsMatchTheIndependentGoldenAngleArithmeticAndStayInTheDisk() {
        for (int taps : new int[]{1, 8, 16, 32}) {
            float radius = 7.5f;
            for (int index = 0; index < taps; index++) {
                float[] actual = PcssMath.diskOffset(index, taps, radius);
                double expectedRadius = Math.sqrt((index + 0.5) / taps) * radius;
                double expectedAngle = index * GOLDEN_ANGLE;
                assertEquals(expectedRadius * Math.cos(expectedAngle), actual[0], 2.0e-5f,
                        "x tap " + index + "/" + taps);
                assertEquals(expectedRadius * Math.sin(expectedAngle), actual[1], 2.0e-5f,
                        "y tap " + index + "/" + taps);
                double length = Math.hypot(actual[0], actual[1]);
                assertTrue(length <= radius + 1.0e-6f);
                assertTrue(length > 0.0f, "the first tap must not collapse to the receiver");
            }
        }
        float[] first = PcssMath.diskOffset(0, 16, 4.0f);
        assertEquals(Math.sqrt(0.5 / 16.0) * 4.0f, first[0], 1.0e-6f);
        assertEquals(0.0f, first[1], 1.0e-6f, "tap zero points along +X");
    }

    @Test
    void blockerAverageExcludesReceiverPlaneSamplesAndRespectsTheBias() {
        float[] depths = {0.20f, 0.30f, 0.48f, 0.50f, 0.90f};
        float average = PcssMath.averageBlockerDepth(0.5f, 0.01f, depths);
        assertEquals((0.20f + 0.30f + 0.48f) / 3.0f, average, 1.0e-6f);
        assertEquals(Float.NaN,
                PcssMath.averageBlockerDepth(0.5f, 0.01f, new float[]{0.6f, 0.9f}),
                "no sample in front of the receiver means no blocker");
        assertEquals(0.0f, PcssMath.averageBlockerDepth(0.5f, 0.01f, new float[]{0.0f}),
                1.0e-6f);
        // Strict '<' matches the shader: a sample exactly at receiver-bias is not a blocker.
        assertEquals(Float.NaN,
                PcssMath.averageBlockerDepth(0.5f, 0.1f, new float[]{0.4f}));
    }

    @Test
    void penumbraMatchesHandComputedDepthRatioAndTheFilterClamp() {
        // (0.30 - 0.28) * 0.5 / 0.28 = 0.035714 world; / 0.02 world/texel = 1.7857.
        assertEquals(1.7857143f,
                PcssMath.penumbraTexels(0.30f, 0.28f, 0.5f, 0.02f, 8.0f), 1.0e-5f);
        assertEquals(0.0f,
                PcssMath.penumbraTexels(0.30f, 0.30f, 2.0f, 0.02f, 8.0f),
                0.0f, "a receiver-plane blocker cannot create a penumbra");
        assertEquals(12.0f,
                PcssMath.penumbraTexels(0.30f, 0.28f, 8.0f, 0.02f, 12.0f),
                0.0f, "the filter radius is a hard cap");
        assertEquals(0.0f,
                PcssMath.penumbraTexels(0.30f, 0.28f, 0.0f, 0.02f, 8.0f),
                0.0f, "a zero light size is an exact no-op");
    }

    @Test
    void largerLightAndFartherBlockersWidenThePenumbra() {
        float smallLight = PcssMath.penumbraTexels(0.6f, 0.4f, 0.5f, 0.02f, 256.0f);
        float largeLight = PcssMath.penumbraTexels(0.6f, 0.4f, 2.0f, 0.02f, 256.0f);
        assertTrue(largeLight > smallLight);
        float nearBlocker = PcssMath.penumbraTexels(0.6f, 0.55f, 2.0f, 0.02f, 256.0f);
        float farBlocker = PcssMath.penumbraTexels(0.6f, 0.20f, 2.0f, 0.02f, 256.0f);
        assertTrue(farBlocker > nearBlocker,
                "the standard PCSS ratio grows as the blocker gets closer to the light");
    }

    @Test
    void productionPenumbraMatchesAnIndependentReferenceOverAFixedMatrix() {
        Random random = new Random(0x5EEDC0DEL);
        for (int sample = 0; sample < 64; sample++) {
            float receiver = 0.05f + random.nextFloat() * 0.9f;
            float blocker = receiver * (0.1f + random.nextFloat() * 0.8f);
            float light = random.nextFloat() * PcssSettings.MAX_LIGHT_WORLD_SIZE;
            float texelWorld = 0.001f + random.nextFloat() * 0.2f;
            float maxRadius = 1.0f + random.nextFloat()
                    * PcssSettings.MAX_FILTER_RADIUS_TEXELS;

            double occluder = Math.max(blocker, 1.0e-4);
            double expected = Math.max((double) receiver - blocker, 0.0)
                    * light / occluder / Math.max(texelWorld, 1.0e-6);
            expected = Math.min(Math.max(expected, 0.0), maxRadius);

            float actual = PcssMath.penumbraTexels(receiver, blocker, light,
                    texelWorld, maxRadius);
            assertEquals(expected, actual, 2.0e-4f, "penumbra sample " + sample);
        }
    }

    @Test
    void tileClampUsesTheDocumentedRange() {
        assertEquals(0.25f, PcssMath.clampToTile(0.10f, 0.25f, 0.75f), 0.0f);
        assertEquals(0.75f, PcssMath.clampToTile(0.90f, 0.25f, 0.75f), 0.0f);
        assertEquals(0.50f, PcssMath.clampToTile(0.50f, 0.25f, 0.75f), 0.0f);

        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.clampToTile(Float.NaN, 0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.clampToTile(0.5f, 0.8f, 0.2f));
    }

    @Test
    void rejectsInvalidSamplingInputs() {
        assertThrows(IllegalArgumentException.class, () -> PcssMath.diskOffset(0, 0, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> PcssMath.diskOffset(4, 4, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> PcssMath.diskOffset(0, 4, -1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.averageBlockerDepth(Float.NaN, 0.0f, new float[]{0.1f}));
        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.averageBlockerDepth(0.5f, -0.1f, new float[]{0.1f}));
        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.averageBlockerDepth(0.5f, 0.0f, null));
        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.averageBlockerDepth(0.5f, 0.0f, new float[]{Float.NaN}));
        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.penumbraTexels(0.5f, 0.4f, -1.0f, 0.01f, 8.0f));
        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.penumbraTexels(0.5f, 0.4f, Float.NaN, 0.01f, 8.0f));
        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.penumbraTexels(0.5f, 0.4f, 1.0f, 0.0f, 8.0f));
        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.penumbraTexels(0.5f, Float.NaN, 1.0f, 0.01f, 8.0f));
        assertThrows(IllegalArgumentException.class,
                () -> PcssMath.penumbraTexels(1.5f, 0.4f, 1.0f, 0.01f, 8.0f));
    }
}
