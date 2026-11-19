package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Validation and defensive-copy contract for one weighted-OIT surface. */
class OitSurfaceTest {
    @Test
    void factoryCopiesVectorsAndExposesValueBasedComponents() {
        Vector3f center = new Vector3f(1.0f, 2.0f, 3.0f);
        Vector3f halfRight = new Vector3f(0.5f, 0.0f, 0.0f);
        Vector3f halfUp = new Vector3f(0.0f, 0.25f, 0.0f);
        Vector3f color = new Vector3f(0.4f, 0.5f, 0.6f);
        OitSurface surface = OitSurface.of(center, halfRight, halfUp, color, 0.75f);

        center.set(9.0f, 9.0f, 9.0f);
        color.set(9.0f, 9.0f, 9.0f);
        assertEquals(new Vector3f(1.0f, 2.0f, 3.0f), surface.center());
        assertEquals(new Vector3f(0.4f, 0.5f, 0.6f), surface.color());
        assertEquals(new Vector3f(0.5f, 0.0f, 0.0f), surface.halfRight());
        assertEquals(new Vector3f(0.0f, 0.25f, 0.0f), surface.halfUp());
        assertEquals(0.75f, surface.alpha());

        surface.center().set(0.0f);
        assertEquals(new Vector3f(1.0f, 2.0f, 3.0f), surface.center(),
                "the record accessor must not expose internal state");
    }

    @Test
    void rejectsNonFiniteGeometryColourAndAlpha() {
        float[] center = {0.0f, 0.0f, 0.0f};
        assertThrows(IllegalArgumentException.class, () -> new OitSurface(
                Float.NaN, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                1.0f, 1.0f, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new OitSurface(
                0.0f, 0.0f, 0.0f, Float.POSITIVE_INFINITY, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                1.0f, 1.0f, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new OitSurface(
                0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                -0.1f, 1.0f, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new OitSurface(
                0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                1.0f, 1.0f, 1.0f, 1.5f));
        assertThrows(IllegalArgumentException.class, () -> new OitSurface(
                0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                1.0f, 1.0f, 1.0f, Float.NaN));
        // A zero-size quad is degenerate but finite; it is the caller's choice.
        new OitSurface(center[0], center[1], center[2],
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f);
    }
}
