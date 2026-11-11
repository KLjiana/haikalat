package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VfxDistortionTest {
    @Test
    void contributionIsDefensivelyCopiedAndKeepsStableIdentity() {
        Vector3f center = new Vector3f(1.0f, 2.0f, -3.0f);
        Vector2f offset = new Vector2f(0.04f, -0.02f);
        VfxDistortion distortion = new VfxDistortion(7L, 2, center, 1.5f, offset,
                0.5f, 0.1f, 1.0f, 0.25f, 3.0f);
        center.set(9.0f);
        offset.set(0.2f, 0.2f);

        assertEquals(new Vector3f(1.0f, 2.0f, -3.0f), distortion.worldCenter());
        assertEquals(new Vector2f(0.04f, -0.02f), distortion.uvOffset());
        assertEquals(new VfxDistortion.Key(7L, 2), distortion.key());
        assertNotEquals(new VfxDistortion.Key(7L, 3), distortion.key());
        assertEquals(7L, distortion.key().effectId());
        assertEquals(2, distortion.key().distortionIndex());
    }

    @Test
    void identityAndContributionBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> distortion(0L, 0));
        assertThrows(IllegalArgumentException.class, () -> distortion(1L, -1));
        assertThrows(IllegalArgumentException.class, () -> distortion(1L, 0, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new VfxDistortion(1L, 0, new Vector3f(Float.NaN, 0, 0), 1.0f,
                        new Vector2f(), 1.0f, 0.0f, 0.0f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new VfxDistortion(1L, 0, new Vector3f(), 1.0f,
                        new Vector2f(0.26f, 0.0f), 1.0f, 0.0f, 0.0f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new VfxDistortion(1L, 0, new Vector3f(), 1.0f,
                        new Vector2f(), 1.01f, 0.0f, 0.0f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new VfxDistortion(1L, 0, new Vector3f(), 1.0f,
                        new Vector2f(), 1.0f, -0.1f, 0.0f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new VfxDistortion(1L, 0, new Vector3f(), 1.0f,
                        new Vector2f(), 1.0f, 0.0f, 1.1f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new VfxDistortion(1L, 0, new Vector3f(), 1.0f,
                        new Vector2f(), 1.0f, 0.0f, 0.0f, Float.NaN, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new VfxDistortion(1L, 0, new Vector3f(), 1.0f,
                        new Vector2f(), 1.0f, 0.0f, 0.0f, 0.0f, -1.0f));
    }

    @Test
    void zeroOffsetAndZeroMaskAreRepresentable() {
        VfxDistortion distortion = new VfxDistortion(3L, 0, new Vector3f(), 1.0f,
                new Vector2f(), 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        assertEquals(0.0f, distortion.mask());
        assertEquals(0.0f, distortion.uvOffset().lengthSquared());
    }

    private static VfxDistortion distortion(long effectId, int index) {
        return distortion(effectId, index, 1.0f);
    }

    private static VfxDistortion distortion(long effectId, int index, float radius) {
        return new VfxDistortion(effectId, index, new Vector3f(), radius,
                new Vector2f(), 1.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }
}
