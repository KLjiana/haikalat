package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM regression for the cloud GL fixture cameras.
 *
 * <p>The first serialized GL run of {@code VolumetricCloudGlTest} executed
 * seven cases but five failed before any pixel comparison because the
 * orthographic fixture projection had a {@code 2e-10} determinant, below the
 * {@link ExternalCamera} {@code 1e-8} invertibility threshold. This test proves
 * the replacement perspective projection is accepted by the real guard and pins
 * the failure mode, so the fixture cannot silently regress without a GPU run.
 * No GL context is created.</p>
 */
class VolumetricCloudCameraFixtureTest {
    private static final float GUARD_THRESHOLD = 1.0e-8f;

    @Test
    void topDownFixtureProjectionIsInvertibleAndAcceptedByTheCameraGuard() {
        Matrix4f projection = CloudTestCameras.topDownProjection();
        float determinant = projection.determinant();
        assertTrue(Float.isFinite(determinant));
        assertTrue(Math.abs(determinant) > GUARD_THRESHOLD,
                "the fixture projection must be above the ExternalCamera threshold, det="
                        + determinant);
        ExternalCamera camera = CloudTestCameras.topDown();
        assertEquals(CloudTestCameras.CLOUD_HEIGHT, camera.position().y, 0.0f);
        assertEquals(CloudTestCameras.CLOUD_CENTER_X, camera.position().x, 0.0f);
        ExternalCamera moved = CloudTestCameras.topDown(CloudTestCameras.CLOUD_CENTER_X + 20.0f,
                CloudTestCameras.CLOUD_CENTER_Z);
        assertTrue(VolumetricCloudPass.cameraCut(camera, moved, settings()),
                "a 20 m fixture move must remain a camera cut");
        assertFalse(VolumetricCloudPass.cameraCut(camera,
                CloudTestCameras.topDown(CloudTestCameras.CLOUD_CENTER_X + 2.0f,
                        CloudTestCameras.CLOUD_CENTER_Z), settings()),
                "a 2 m fixture move must stay inside the cut threshold");
    }

    @Test
    void horizonFixtureProjectionIsAcceptedByTheCameraGuard() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(50.0),
                1.0f, 0.125f, 5_000.0f);
        assertTrue(Math.abs(projection.determinant()) > GUARD_THRESHOLD);
        ExternalCamera camera = CloudTestCameras.horizon();
        assertEquals(400.0f, camera.position().y, 0.0f);
    }

    @Test
    void orthographicProjectionOverTheSameAreaIsRejectedByTheCameraGuard() {
        Matrix4f orthographic = CloudTestCameras.rejectedOrthographicProjection();
        assertTrue(Math.abs(orthographic.determinant()) < GUARD_THRESHOLD,
                "the pre-fix fixture projection is below the guard threshold, det="
                        + orthographic.determinant());
        Matrix4f view = new Matrix4f().lookAt(
                new Vector3f(0.0f, CloudTestCameras.CLOUD_HEIGHT, 0.0f), new Vector3f(),
                new Vector3f(0.0f, 0.0f, -1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalCamera.of(view, orthographic,
                        new Vector3f(0.0f, CloudTestCameras.CLOUD_HEIGHT, 0.0f), 0.0f),
                "the guard must keep rejecting the singular fixture projection");
    }

    private static VolumetricCloudSettings settings() {
        return new VolumetricCloudSettings(true,
                new CloudShadowSettings(true, 0.5f, 0.4f, 0.0004f, 0.0f, 0.65f),
                1_000.0f, 1_400.0f, 0.02f, 0.05f, 0.95f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.3f,
                32, 40.0f, 4, 2_000.0f, 0.5f, 0.9f, 0.1f, 2.0f, 5.0f, 30.0f);
    }
}
