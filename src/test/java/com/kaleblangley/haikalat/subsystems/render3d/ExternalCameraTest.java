package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExternalCameraTest {
    @Test
    void copiesHostDataAndRetainsExactProjection() {
        Matrix4f view = new Matrix4f().lookAt(
                1, 2, 3, 1, 2, 2, 0, 1, 0);
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(73), 16.0f / 9.0f, 0.05f, 800.0f);
        Vector3f position = new Vector3f(1, 2, 3);
        ExternalCamera camera = new ExternalCamera(view, projection,
                new Matrix4f(projection).mul(view), position,
                0.35f, 0.05f, 800.0f, 12);

        view.identity();
        projection.identity();
        position.zero();

        assertEquals(1.0f, camera.position().x);
        assertEquals(0.35f, camera.partialTick());
        assertEquals(12, camera.revision());
        assertNotEquals(new Matrix4f(), camera.getViewMatrix());
        assertEquals(camera.projection(), CameraProjection.stable(
                camera, 10, 10, new Matrix4f()));
        assertTrue(new Matrix4f(camera.viewProjection())
                .mul(camera.inverseViewProjection()).equals(new Matrix4f(), 1.0e-4f));
    }

    @Test
    void rejectsNonFiniteOrSingularInput() {
        assertThrows(IllegalArgumentException.class, () -> ExternalCamera.of(
                new Matrix4f().m00(Float.NaN), new Matrix4f(),
                new Vector3f(), 0.0f));
        assertThrows(IllegalArgumentException.class, () -> ExternalCamera.of(
                new Matrix4f().zero(), new Matrix4f(),
                new Vector3f(), 0.0f));
    }
}
