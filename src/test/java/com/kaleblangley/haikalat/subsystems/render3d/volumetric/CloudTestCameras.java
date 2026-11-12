package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Shared cloud-test cameras. Kept outside the GL test so a plain JVM test can
 * prove the fixture projections are invertible: {@link ExternalCamera} rejects
 * a projection whose determinant is at or below {@code 1e-8}, and an
 * orthographic projection over the fixture's 2 km area has a determinant of
 * about {@code 2e-10}. The first serialized GL run failed exactly there, before
 * any pixel comparison.
 */
final class CloudTestCameras {
    static final float TOP_DOWN_FOV_DEGREES = 30.0f;
    static final float TOP_DOWN_NEAR = 0.125f;
    static final float TOP_DOWN_FAR = 10_000.0f;
    static final float CLOUD_CENTER_X = 1_500.0f;
    static final float CLOUD_CENTER_Z = 0.0f;
    static final float CLOUD_HEIGHT = 5_000.0f;

    private CloudTestCameras() {
    }

    /** @return the orthographic projection used by the pre-fix fixture (rejected by the guard) */
    static Matrix4f rejectedOrthographicProjection() {
        return new Matrix4f().ortho(-1_000.0f, 1_000.0f, -1_000.0f, 1_000.0f, 1.0f, 10_000.0f);
    }

    /** @return the perspective bird's-eye projection used by the cloud fixture */
    static Matrix4f topDownProjection() {
        return new Matrix4f().perspective((float) Math.toRadians(TOP_DOWN_FOV_DEGREES),
                1.0f, TOP_DOWN_NEAR, TOP_DOWN_FAR);
    }

    static ExternalCamera topDown() {
        return topDown(CLOUD_CENTER_X, CLOUD_CENTER_Z);
    }

    static ExternalCamera topDown(float centerX, float centerZ) {
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(centerX, CLOUD_HEIGHT, centerZ),
                new Vector3f(centerX, 0.0f, centerZ), new Vector3f(0.0f, 0.0f, -1.0f));
        return ExternalCamera.of(view, topDownProjection(),
                new Vector3f(centerX, CLOUD_HEIGHT, centerZ), 0.0f);
    }

    static ExternalCamera horizon() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(50.0),
                1.0f, 0.125f, 5_000.0f);
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(0.0f, 400.0f, 0.0f),
                new Vector3f(0.0f, 400.0f, -1.0f), new Vector3f(0.0f, 1.0f, 0.0f));
        return ExternalCamera.of(view, projection, new Vector3f(0.0f, 400.0f, 0.0f), 0.0f);
    }
}
