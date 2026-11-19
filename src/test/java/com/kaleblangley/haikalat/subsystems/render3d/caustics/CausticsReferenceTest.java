package com.kaleblangley.haikalat.subsystems.render3d.caustics;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent double-precision contract for the documented caustics formula.
 *
 * <p>The hard-coded expected values were computed from the design formula in a
 * separate scratch calculation (not by the Java reference) so a change to the
 * reference and the test cannot move the goal posts together. The same reference
 * is reused by the real-GL readback test.</p>
 */
class CausticsReferenceTest {
    private static final Vector3f LIGHT = new Vector3f(0.24f, -1.0f, 0.18f).normalize();

    private static CausticsSettings settings() {
        return CausticsSettings.builder()
                .enabled(true)
                .intensity(1.5f)
                .color(0.55f, 0.80f, 1.0f)
                .worldScaleMetres(2.5f)
                .animationSpeed(0.4f)
                .maximumProjectionDistance(12.0f)
                .waterPlane(0.0f)
                .waterRegion(-6.0f, -4.0f, 7.0f, 5.0f)
                .receiverHeight(-5.0f, -0.5f)
                .depthBias(0.05f)
                .distanceFalloff(0.12f)
                .edgeFadeDistance(0.8f)
                .maximumReceiverSlopeDegrees(55.0f)
                .patternTexture("")
                .build();
    }

    @Test
    void patternMatchesTheFixedReferenceSamples() {
        CausticsSettings settings = settings();
        assertEquals(0.054894839855745704,
                CausticsReference.pattern(0.0, 0.0, 0.0, settings), 1.0e-6);
        assertEquals(0.5222229748501518,
                CausticsReference.pattern(1.3, -0.7, 0.5, settings), 1.0e-6);
        assertEquals(0.10226722405501967,
                CausticsReference.pattern(-2.1, 3.4, 1.75, settings), 1.0e-6);
        assertEquals(0.35100234235070293,
                CausticsReference.pattern(4.0, 4.0, 2.5, settings), 1.0e-6);
    }

    @Test
    void amountMatchesTheFixedReferenceSamples() {
        CausticsSettings settings = settings();
        assertEquals(0.9232087711066256,
                CausticsReference.amount(0.4, -2.2, -1.1, 0.97, settings, LIGHT, 0.5), 1.0e-6);
        assertEquals(0.9020516678001975,
                CausticsReference.amount(2.0, -3.0, 1.5, 0.85, settings, LIGHT, 1.25), 1.0e-6);
        assertEquals(0.018686848569084976,
                CausticsReference.amount(-2.5, -1.0, 0.3, 0.999, settings, LIGHT, 2.0), 1.0e-6);
    }

    @Test
    void rejectionGatesReturnExactlyZero() {
        CausticsSettings settings = settings();
        assertEquals(0.0, CausticsReference.amount(0.4, -0.02, -1.1, 1.0, settings,
                LIGHT, 0.5), 0.0, "receiver inside the water depth bias");
        assertEquals(0.0, CausticsReference.amount(0.4, -20.0, -1.1, 1.0, settings,
                LIGHT, 0.5), 0.0, "receiver beyond the maximum projection distance");
        assertEquals(0.0, CausticsReference.amount(0.4, -2.2, -1.1, 0.2, settings,
                LIGHT, 0.5), 0.0, "receiver steeper than the maximum slope");
        assertEquals(0.0, CausticsReference.amount(100.0, -2.2, 100.0, 0.97, settings,
                LIGHT, 0.5), 0.0, "projected water entry outside the finite region");
        assertEquals(0.0, CausticsReference.amount(0.4, -0.5, -1.1, 0.97, settings,
                LIGHT, 0.5), 0.0, "receiver on the top edge of the height band");
        assertEquals(0.0, CausticsReference.amount(0.4, -2.2, -1.1, 0.97,
                settings.withEnabled(false), LIGHT, 0.5), 0.0,
                "disabled settings must be an exact no-op");
    }

    @Test
    void patternIsDeterministicBoundedAndAnimated() {
        CausticsSettings settings = settings();
        for (float time : new float[]{0.0f, 0.25f, 1.0f, 8.0f}) {
            for (float[] point : new float[][]{{0.0f, 0.0f}, {1.3f, -0.7f},
                    {-2.1f, 3.4f}, {4.0f, 4.0f}}) {
                double value = CausticsReference.pattern(point[0], point[1], time, settings);
                assertTrue(value >= 0.0 && value <= 1.0, "pattern must stay in [0, 1]");
                assertEquals(value, CausticsReference.pattern(point[0], point[1], time, settings),
                        0.0, "same inputs must reproduce exactly");
            }
        }
        assertNotEquals(CausticsReference.pattern(1.3, -0.7, 0.0, settings),
                CausticsReference.pattern(1.3, -0.7, 0.75, settings),
                "moving pattern must change with time");
        CausticsSettings still = settings.withEnabled(true);
        assertEquals(CausticsReference.pattern(1.3, -0.7, 0.0, still),
                CausticsReference.pattern(1.3, -0.7, 0.0, still),
                0.0);
        CausticsSettings frozen = CausticsSettings.builder()
                .enabled(true).animationSpeed(0.0f).build();
        assertEquals(CausticsReference.pattern(1.3, -0.7, 0.0, frozen),
                CausticsReference.pattern(1.3, -0.7, 7.0, frozen),
                0.0, "zero animation speed must freeze the pattern");
    }
}
