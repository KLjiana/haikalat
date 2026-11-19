package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent Snell/projection reference used by the real-GL refraction oracle.
 * Values are hand-derived from the fixed camera and lens below; no production
 * helper computes the expected numbers.
 */
class RefractionReferenceMathTest {
    private static final int WIDTH = 120;
    private static final int HEIGHT = 120;
    private static final Matrix4f PROJECTION = new Matrix4f()
            .perspective((float) Math.toRadians(60.0), 1.0f, 0.1f, 100.0f);
    private static final Matrix4f VIEW = new Matrix4f().lookAt(
            0.0f, 0.0f, 3.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
    private static final Vector3f CAMERA = new Vector3f(0.0f, 0.0f, 3.0f);
    private static final Vector3f ORIGIN = new Vector3f(0.0f, 0.0f, 0.0f);
    private static final float SIN_30 = 0.5f;
    private static final float COS_30 = 0.8660254f;

    @Test
    void normalIncidenceProducesNoScreenOffset() {
        Vector2f offset = RefractionReferenceMath.screenOffsetUv(PROJECTION, VIEW,
                ORIGIN, new Vector3f(0.0f, 0.0f, 1.0f), CAMERA, 1.5f, 1.0f,
                WIDTH, HEIGHT);

        assertEquals(0.0f, offset.x(), 1.0e-6f);
        assertEquals(0.0f, offset.y(), 1.0e-6f);
    }

    @Test
    void iorOneAndZeroThicknessAreExactNoOps() {
        Vector3f tilted = new Vector3f(SIN_30, 0.0f, COS_30);
        assertEquals(0.0f, RefractionReferenceMath.screenOffsetUv(PROJECTION, VIEW,
                ORIGIN, tilted, CAMERA, 1.0f, 1.0f, WIDTH, HEIGHT).length(), 1.0e-6f);
        assertEquals(0.0f, RefractionReferenceMath.screenOffsetUv(PROJECTION, VIEW,
                ORIGIN, tilted, CAMERA, 1.5f, 0.0f, WIDTH, HEIGHT).length(), 1.0e-6f);
    }

    @Test
    void tiltedNormalBendsTowardTheOppositeSideOfTheSurfaceNormal() {
        Vector3f tilted = new Vector3f(SIN_30, 0.0f, COS_30);
        Vector2f offset = RefractionReferenceMath.screenOffsetUv(PROJECTION, VIEW,
                ORIGIN, tilted, CAMERA, 1.5f, 1.0f, WIDTH, HEIGHT);

        // 30 degree incidence at IOR 1.5 refracts to ~19.47 degrees; advancing
        // one world unit moves the projected point by -4.7675 pixels at the
        // fixed 120 px / 60 degree lens below. The normal points toward +X, so
        // the transmitted ray leaves toward -X.
        assertEquals(-4.7675f, offset.x() * WIDTH, 0.02f);
        assertEquals(0.0f, offset.y() * HEIGHT, 1.0e-4f);
    }

    @Test
    void largerThicknessAndIorIncreaseTheOffsetMagnitude() {
        Vector3f tilted = new Vector3f(SIN_30, 0.0f, COS_30);
        float thin = Math.abs(RefractionReferenceMath.screenOffsetUv(PROJECTION, VIEW,
                ORIGIN, tilted, CAMERA, 1.5f, 0.25f, WIDTH, HEIGHT).x());
        float thick = Math.abs(RefractionReferenceMath.screenOffsetUv(PROJECTION, VIEW,
                ORIGIN, tilted, CAMERA, 1.5f, 1.0f, WIDTH, HEIGHT).x());
        float denser = Math.abs(RefractionReferenceMath.screenOffsetUv(PROJECTION, VIEW,
                ORIGIN, tilted, CAMERA, 2.0f, 1.0f, WIDTH, HEIGHT).x());

        assertTrue(denser > thick, "higher IOR must bend the ray farther");
        assertTrue(thick > thin, "larger thickness must spread the projected offset");

        Vector3f shallow = RefractionReferenceMath.refract(
                new Vector3f(0.0f, 0.0f, -1.0f), tilted, 1.5f);
        assertEquals(-0.1827295f, shallow.x, 1.0e-5f);
        assertEquals(-0.9831630f, shallow.z, 1.0e-5f);
    }

    @Test
    void pixelRayMeetsTheGlassPlaneAtTheExpectedWorldPoint() {
        Vector3f centre = RefractionReferenceMath.worldOnZPlane(PROJECTION, VIEW,
                WIDTH / 2.0f - 0.5f, HEIGHT / 2.0f - 0.5f, WIDTH, HEIGHT, 0.0f);
        assertTrue(centre != null);
        assertEquals(0.0f, centre.x, 1.0e-4f);
        assertEquals(0.0f, centre.y, 1.0e-4f);
        assertEquals(0.0f, centre.z, 1.0e-4f);

        Vector3f right = RefractionReferenceMath.worldOnZPlane(PROJECTION, VIEW,
                WIDTH - 0.5f, HEIGHT / 2.0f - 0.5f, WIDTH, HEIGHT, 0.0f);
        assertTrue(right != null);
        assertTrue(right.x > 0.0f, "the right edge must map to +X on the glass plane");
    }

    @Test
    void bilinearSamplingMatchesTheDocumentedClampContract() {
        float[] twoByTwo = {
                1.0f, 0.0f, 0.0f, 1.0f,   0.0f, 1.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f, 1.0f,   1.0f, 1.0f, 1.0f, 1.0f
        };
        // Pixel centres are exact texels; corners clamp to the border.
        float[] bottomLeft = RefractionReferenceMath.sampleBilinear(
                twoByTwo, 2, 2, 0.25f, 0.25f);
        assertEquals(1.0f, bottomLeft[0], 0.0f);
        assertEquals(0.0f, bottomLeft[1], 0.0f);
        assertEquals(0.0f, bottomLeft[2], 0.0f);
        float[] middle = RefractionReferenceMath.sampleBilinear(
                twoByTwo, 2, 2, 0.5f, 0.5f);
        assertEquals(0.5f, middle[0], 1.0e-6f);
        assertEquals(0.5f, middle[1], 1.0e-6f);
        assertEquals(0.5f, middle[2], 1.0e-6f);
    }
}
