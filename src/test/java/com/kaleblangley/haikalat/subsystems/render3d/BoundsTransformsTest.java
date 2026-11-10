package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.scene.BoundsTransforms;
import com.kaleblangley.haikalat.subsystems.render3d.scene.WorldBounds;

import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoundsTransformsTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void transformsIdentityTranslationAndNonUniformMirroredScale() {
        Bounds3f local = Bounds3f.of(-1, -2, -3, 1, 2, 3);
        var world = new WorldBounds.Scratch();

        BoundsTransforms.world(local, new Matrix4f().translation(4, -5, 6)
                .scale(-2, 3, 0.5f), world);

        assertBounds(world.view(), 2, -11, 4.5f, 6, 1, 7.5f);
    }

    @Test
    void rotationAndShearUseAbsoluteLinearMatrix() {
        Bounds3f local = Bounds3f.of(-1, -2, -0.5f, 1, 2, 0.5f);
        var rotated = new WorldBounds.Scratch();
        BoundsTransforms.world(local, new Matrix4f().rotateZ((float) Math.PI / 2.0f),
                rotated);
        assertBounds(rotated.view(), -2, -1, -0.5f, 2, 1, 0.5f);

        var sheared = new WorldBounds.Scratch();
        Matrix4f shear = new Matrix4f().m10(2.0f);
        BoundsTransforms.world(Bounds3f.of(-1, -1, -1, 1, 1, 1), shear,
                sheared);
        assertBounds(sheared.view(), -3, -1, -1, 3, 1, 1);
    }

    @Test
    void zeroScaleAndUnboundedRemainConservative() {
        var world = new WorldBounds.Scratch();
        BoundsTransforms.world(Bounds3f.of(-1, -1, -1, 1, 1, 1),
                new Matrix4f().scale(0, 1, 1), world);
        assertBounds(world.view(), 0, -1, -1, 0, 1, 1);

        BoundsTransforms.world(Bounds3f.unbounded(), new Matrix4f(), world);
        assertTrue(world.view().unbounded());
    }

    @Test
    void rejectsPerspectiveNonFiniteAndOverflowWithIdentity() {
        var world = new WorldBounds.Scratch();
        IllegalArgumentException perspective = assertThrows(IllegalArgumentException.class,
                () -> BoundsTransforms.world(Bounds3f.of(-1, -1, -1, 1, 1, 1),
                        new Matrix4f().perspective(1, 1, 0.1f, 100), world));
        assertTrue(perspective.getMessage().contains("model matrix"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundsTransforms.world(Bounds3f.of(0, 0, 0, 1, 1, 1),
                        new Matrix4f().m30(Float.NaN), world));
        assertThrows(IllegalArgumentException.class,
                () -> BoundsTransforms.world(Bounds3f.of(-Float.MAX_VALUE, 0, 0,
                                Float.MAX_VALUE, 1, 1),
                        new Matrix4f().scale(2), world));
    }

    private static void assertBounds(WorldBounds actual, float minX, float minY, float minZ,
                                     float maxX, float maxY, float maxZ) {
        assertFalse(actual.unbounded());
        assertEquals(minX, actual.minX(), EPSILON);
        assertEquals(minY, actual.minY(), EPSILON);
        assertEquals(minZ, actual.minZ(), EPSILON);
        assertEquals(maxX, actual.maxX(), EPSILON);
        assertEquals(maxY, actual.maxY(), EPSILON);
        assertEquals(maxZ, actual.maxZ(), EPSILON);
    }
}
