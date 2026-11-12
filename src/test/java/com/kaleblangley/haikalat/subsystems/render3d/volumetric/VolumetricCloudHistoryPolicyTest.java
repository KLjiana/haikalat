package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the cloud history policy: the camera-cut and exposure rules
 * that decide whether the previous frame may be reprojected at all.
 *
 * <p>Uses the real immutable {@link ExternalCamera} snapshots and the
 * package-private decisions the GL pass applies; no GL context is created.</p>
 */
class VolumetricCloudHistoryPolicyTest {
    private static VolumetricCloudSettings settings() {
        return new VolumetricCloudSettings(true,
                new CloudShadowSettings(true, 0.5f, 0.4f, 0.0004f, 0.0f, 0.65f),
                1_000.0f, 1_400.0f, 0.02f, 0.05f, 0.95f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.3f,
                32, 40.0f, 4, 2_000.0f, 0.5f, 0.9f, 0.1f, 2.0f, 5.0f, 30.0f);
    }

    private static ExternalCamera camera(float x, float y, float z, float yawDegrees,
                                         float fovDegrees) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fovDegrees),
                1.0f, 0.1f, 5_000.0f);
        Matrix4f view = new Matrix4f()
                .rotateY((float) Math.toRadians(yawDegrees))
                .translate(-x, -y, -z);
        return ExternalCamera.of(view, projection, new Vector3f(x, y, z), 0.0f);
    }

    @Test
    void historyIsAcceptedForSmallCameraChangesAndRejectedForCuts() {
        VolumetricCloudSettings settings = settings();
        ExternalCamera base = camera(0.0f, 40.0f, 0.0f, 0.0f, 50.0f);
        assertFalse(VolumetricCloudPass.cameraCut(base, base, settings),
                "an identical camera must keep the history");

        ExternalCamera smallMove = camera(4.9f, 40.0f, 0.0f, 0.0f, 50.0f);
        assertFalse(VolumetricCloudPass.cameraCut(base, smallMove, settings),
                "a translation below the configured limit is not a cut");

        ExternalCamera largeMove = camera(6.0f, 40.0f, 0.0f, 0.0f, 50.0f);
        assertTrue(VolumetricCloudPass.cameraCut(base, largeMove, settings),
                "a translation above the configured limit is a cut");

        ExternalCamera smallTurn = camera(0.0f, 40.0f, 0.0f, 10.0f, 50.0f);
        assertFalse(VolumetricCloudPass.cameraCut(base, smallTurn, settings));

        ExternalCamera largeTurn = camera(0.0f, 40.0f, 0.0f, 40.0f, 50.0f);
        assertTrue(VolumetricCloudPass.cameraCut(base, largeTurn, settings),
                "a rotation above the configured limit is a cut");

        ExternalCamera zoomed = camera(0.0f, 40.0f, 0.0f, 0.0f, 60.0f);
        assertTrue(VolumetricCloudPass.cameraCut(base, zoomed, settings),
                "a projection/FOV change is a cut");

        assertTrue(VolumetricCloudPass.cameraCut(null, base, settings));
        assertTrue(VolumetricCloudPass.cameraCut(base, null, settings));
    }

    @Test
    void exposureChangesAreRejectedByTheRelativeThreshold() {
        assertTrue(VolumetricCloudPass.exposureAccepted(1.0f, 1.05f, 0.1f));
        assertTrue(VolumetricCloudPass.exposureAccepted(1.0f, 0.95f, 0.1f));
        assertFalse(VolumetricCloudPass.exposureAccepted(1.0f, 1.2f, 0.1f),
                "a relative exposure change above the threshold rejects the history");
        assertTrue(VolumetricCloudPass.exposureAccepted(2.0f, 3.9f, 1.0f));
        assertFalse(VolumetricCloudPass.exposureAccepted(2.0f, 4.1f, 1.0f));
        assertFalse(VolumetricCloudPass.exposureAccepted(0.0f, 1.0f, 1.0f),
                "an unusable exposure rejects the history");
        assertFalse(VolumetricCloudPass.exposureAccepted(1.0f, Float.NaN, 1.0f));
        assertFalse(VolumetricCloudPass.exposureAccepted(Float.POSITIVE_INFINITY, 1.0f, 1.0f));
    }
}
