package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-computed checks of the independent collision reference.
 *
 * <p>The expected values below are ordinary algebra on the documented model, not
 * a second call to the reference. They pin the penetration sign, the skin
 * offset, reflection with restitution, tangential friction, the two-sided
 * thickness band and the horizontal-plane reconstruction used by the GL
 * fixture.</p>
 */
class GpuParticleCollisionReferenceTest {
    private static final float TOLERANCE = 1.0e-6f;

    @Test
    void bounceResolvesPenetrationAndReflectsTheNormalVelocity() {
        GpuParticleCollisionReference.Result result = GpuParticleCollisionReference.resolve(
                0.0f, -1.05f, 0.0f, 1.0f, -2.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                0.2f, 0.5f, 0.0f);

        assertTrue(result.collided());
        assertEquals(0.0f, result.x(), TOLERANCE);
        assertEquals(-0.999f, result.y(), TOLERANCE);
        assertEquals(0.0f, result.z(), TOLERANCE);
        assertEquals(1.0f, result.velocityX(), TOLERANCE);
        assertEquals(1.0f, result.velocityY(), TOLERANCE,
                "normal velocity -2 with restitution 0.5 must reflect to +1");
        assertEquals(0.0f, result.velocityZ(), TOLERANCE);
    }

    @Test
    void thicknessBandIsInclusiveAtTheBoundaryAndRejectsDeeperParticles() {
        GpuParticleCollisionReference.Result inside = GpuParticleCollisionReference.resolve(
                0.0f, -1.125f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                0.125f, 0.0f, 0.0f);
        assertTrue(inside.collided(), "penetration equal to thickness is inside the band");
        assertEquals(-0.999f, inside.y(), TOLERANCE);

        GpuParticleCollisionReference.Result outside = GpuParticleCollisionReference.resolve(
                0.0f, -1.125f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                0.0625f, 0.0f, 0.0f);
        assertFalse(outside.collided(),
                "a particle deeper behind the surface than the thickness band keeps free flight");
        assertEquals(-1.125f, outside.y(), 0.0f);
        assertEquals(0.0f, outside.velocityY(), 0.0f);

        GpuParticleCollisionReference.Result inFront = GpuParticleCollisionReference.resolve(
                0.0f, -0.875f, 0.0f, 0.0f, -2.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                0.2f, 1.0f, 0.0f);
        assertFalse(inFront.collided(), "a particle in front of the surface is untouched");
        assertEquals(-0.875f, inFront.y(), 0.0f);
    }

    @Test
    void restitutionAndFrictionAreIndependentScales() {
        GpuParticleCollisionReference.Result slide = GpuParticleCollisionReference.resolve(
                0.0f, -1.05f, 0.0f, 1.0f, -2.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                0.2f, 0.0f, 0.5f);
        assertEquals(0.5f, slide.velocityX(), TOLERANCE,
                "half the tangential velocity is removed by friction");
        assertEquals(0.0f, slide.velocityY(), TOLERANCE,
                "zero restitution cancels the normal velocity");

        GpuParticleCollisionReference.Result elastic = GpuParticleCollisionReference.resolve(
                0.0f, -1.05f, 0.0f, 1.0f, -2.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                0.2f, 1.0f, 0.0f);
        assertEquals(1.0f, elastic.velocityX(), TOLERANCE);
        assertEquals(2.0f, elastic.velocityY(), TOLERANCE);
    }

    @Test
    void surfaceNormalIsNormalizedAndPlaneSurfaceMatchesTheFixedRay() {
        GpuParticleCollisionReference.Result normalized = GpuParticleCollisionReference.resolve(
                0.0f, -1.05f, 0.0f, 0.0f, -2.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f, 4.0f, 0.0f,
                0.2f, 0.5f, 0.0f);
        assertEquals(-0.999f, normalized.y(), TOLERANCE);
        assertEquals(1.0f, normalized.velocityY(), TOLERANCE,
                "a non-unit normal must be normalized before the response");

        Vector3f surface = GpuParticleCollisionReference.horizontalPlaneSurface(
                new Vector3f(0.0f, 3.0f, 3.0f), new Vector3f(0.0f, -1.05f, 0.0f), -1.0f);
        assertEquals(0.0f, surface.x, TOLERANCE);
        assertEquals(-1.0f, surface.y, 0.0f);
        assertEquals(0.037037037f, surface.z, 1.0e-5f,
                "the camera-to-particle ray reaches y=-1 just in front of the origin");

        GpuParticleCollisionReference.Result fromPlane = GpuParticleCollisionReference.resolve(
                0.0f, -1.05f, 0.0f, 0.0f, -2.0f, 0.0f,
                surface.x, surface.y, surface.z, 0.0f, 1.0f, 0.0f,
                0.2f, 0.5f, 0.0f);
        assertTrue(fromPlane.collided());
        assertEquals(-0.999f, fromPlane.y(), TOLERANCE);
    }

    @Test
    void degenerateInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionReference.resolve(
                0.0f, -1.05f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.2f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionReference.resolve(
                0.0f, -1.05f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleCollisionReference.resolve(
                Float.NaN, -1.05f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                0.2f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> GpuParticleCollisionReference.horizontalPlaneSurface(
                        new Vector3f(0.0f, 3.0f, 3.0f), new Vector3f(0.0f, 3.0f, 4.0f), -1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> GpuParticleCollisionReference.horizontalPlaneSurface(
                        new Vector3f(0.0f, 3.0f, 3.0f), new Vector3f(0.0f, 4.0f, 4.0f), -1.0f));
    }
}
