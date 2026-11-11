package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the pass's screen-space bounding-box projection.
 *
 * <p>The pass scissor and the coverage cap both come from projecting the eight
 * corners of the local body. This test proves the perspective/orthographic
 * clipping, the conservative straddle-near-plane rule and the coverage fraction
 * without a GL context. The guarded real-GL test covers the actual scissor and
 * the cap rejection.</p>
 */
class VolumetricParticleProjectionTest {
    private static final int SIZE = 100;

    private static VolumetricParticleSettings body(Vector3f center, Vector3f halfExtent) {
        return new VolumetricParticleSettings(true, 1.0f, 0.2f, 0.8f, center, halfExtent,
                16, 0.1f, new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.0f,
                VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 1.0f);
    }

    @Test
    void orthographicBodyProjectsToItsExactBoundingRectangle() {
        Matrix4f orthographic = new Matrix4f().ortho(-4.0f, 4.0f, -4.0f, 4.0f, 0.1f, 100.0f);
        VolumetricParticlePass.Projection projection = VolumetricParticlePass.project(
                body(new Vector3f(0.0f, 0.0f, -10.0f), new Vector3f(1.0f)), orthographic,
                SIZE, SIZE);
        assertTrue(projection.visible());
        assertEquals(37, projection.x());
        assertEquals(37, projection.y());
        assertEquals(26, projection.width());
        assertEquals(26, projection.height());
        assertEquals(0.0676f, projection.coverage(), 1.0e-6f);
    }

    @Test
    void perspectiveBodyProjectsWithTheNearFaceDominatingTheBounds() {
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(90.0f), 1.0f,
                0.1f, 100.0f);
        VolumetricParticlePass.Projection projection = VolumetricParticlePass.project(
                body(new Vector3f(0.0f, 0.0f, -10.0f), new Vector3f(1.0f)), perspective,
                SIZE, SIZE);
        assertTrue(projection.visible());
        // 1 / (9 * tan(45deg)) = 0.1111 NDC -> pixels 44.44 .. 55.56.
        assertEquals(44, projection.x());
        assertEquals(44, projection.y());
        assertEquals(12, projection.width());
        assertEquals(12, projection.height());
        assertEquals(144.0f / 10_000.0f, projection.coverage(), 1.0e-6f);
    }

    @Test
    void bodyBehindTheEyeProjectsToNothing() {
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(90.0f), 1.0f,
                0.1f, 100.0f);
        VolumetricParticlePass.Projection projection = VolumetricParticlePass.project(
                body(new Vector3f(0.0f, 0.0f, 10.0f), new Vector3f(1.0f)), perspective,
                SIZE, SIZE);
        assertFalse(projection.visible());
        assertEquals(0.0f, projection.coverage(), 0.0f);
    }

    @Test
    void bodyStraddlingTheEyePlaneIsConservativelyFullTarget() {
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(90.0f), 1.0f,
                0.1f, 100.0f);
        VolumetricParticlePass.Projection projection = VolumetricParticlePass.project(
                body(new Vector3f(0.0f, 0.0f, -0.05f), new Vector3f(2.0f)), perspective,
                SIZE, SIZE);
        assertTrue(projection.visible());
        assertEquals(0, projection.x());
        assertEquals(0, projection.y());
        assertEquals(SIZE, projection.width());
        assertEquals(SIZE, projection.height());
        assertEquals(1.0f, projection.coverage(), 0.0f);
    }

    @Test
    void offScreenBodyIsClippedAndFullyOffScreenBodyIsEmpty() {
        Matrix4f orthographic = new Matrix4f().ortho(-4.0f, 4.0f, -4.0f, 4.0f, 0.1f, 100.0f);
        VolumetricParticlePass.Projection clipped = VolumetricParticlePass.project(
                body(new Vector3f(-3.9f, 0.0f, -10.0f), new Vector3f(1.0f)), orthographic,
                SIZE, SIZE);
        assertTrue(clipped.visible());
        assertEquals(0, clipped.x());
        assertEquals(14, clipped.width(), "the left part of the body is clipped to the target");
        assertEquals(37, clipped.y());
        assertEquals(26, clipped.height());
        VolumetricParticlePass.Projection empty = VolumetricParticlePass.project(
                body(new Vector3f(100.0f, 0.0f, -10.0f), new Vector3f(1.0f)), orthographic,
                SIZE, SIZE);
        assertFalse(empty.visible());
        assertEquals(0.0f, empty.coverage(), 0.0f);
    }

    @Test
    void coverageUsesTheAxisAlignedBoxRatherThanTheEllipsoid() {
        Matrix4f orthographic = new Matrix4f().ortho(-4.0f, 4.0f, -4.0f, 4.0f, 0.1f, 100.0f);
        VolumetricParticlePass.Projection square = VolumetricParticlePass.project(
                body(new Vector3f(0.0f, 0.0f, -10.0f), new Vector3f(1.0f, 1.0f, 0.01f)),
                orthographic, SIZE, SIZE);
        assertEquals(26, square.width());
        assertEquals(26, square.height());
        assertEquals(0.0676f, square.coverage(), 1.0e-6f,
                "the conservative box bounds the projected coverage, not the inscribed ellipse");
    }
}
