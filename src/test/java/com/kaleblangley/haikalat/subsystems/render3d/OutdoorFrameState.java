package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

/** CPU-only candidate/last-success state for the outdoor temporal chain. */
final class OutdoorFrameState {
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final Matrix4f pendingViewProjection = new Matrix4f();
    private final Matrix4f previousViewProjection = new Matrix4f();
    private final Vector3f previousPosition = new Vector3f();
    private final Vector3f pendingPosition = new Vector3f();
    private final Vector3f previousForward = new Vector3f();
    private final Vector3f pendingForward = new Vector3f();
    private boolean previousCameraValid;

    /** @return true when a camera cut invalidated the previous successful frame. */
    boolean prepare(Camera camera, int width, int height, AntiAliasingMode antiAliasing,
                    int frameIndex) {
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        Camera requiredCamera = Objects.requireNonNull(camera, "camera");
        CameraProjection.stable(requiredCamera, safeWidth, safeHeight, projection);
        CameraUniforms.applyTemporalJitter(projection, safeWidth, safeHeight,
                Objects.requireNonNull(antiAliasing, "antiAliasing"), frameIndex);
        requiredCamera.getViewMatrix(view);
        pendingPosition.set(requiredCamera.positionInternal());
        pendingForward.set(view.m02(), view.m12(), view.m22()).normalize();
        boolean cameraCut = previousCameraValid
                && (pendingPosition.distanceSquared(previousPosition) > 16.0f
                || pendingForward.dot(previousForward) < 0.8f);
        if (cameraCut) previousCameraValid = false;
        viewProjection.set(projection).mul(view);
        if (!viewProjection.isFinite() || Math.abs(viewProjection.determinant()) <= 1.0e-8f) {
            throw new IllegalArgumentException("outdoor camera view-projection must be invertible");
        }
        inverseViewProjection.set(viewProjection).invert();
        if (!inverseViewProjection.isFinite()) {
            throw new IllegalArgumentException("outdoor inverse camera matrix must be finite");
        }
        pendingViewProjection.set(viewProjection);
        return cameraCut;
    }

    void commitSuccessfulFrame() {
        previousViewProjection.set(pendingViewProjection);
        previousPosition.set(pendingPosition);
        previousForward.set(pendingForward);
        previousCameraValid = true;
    }

    void invalidate() {
        previousCameraValid = false;
    }

    Matrix4f inverseViewProjection() {
        return inverseViewProjection;
    }

    Matrix4f previousViewProjection() {
        return previousViewProjection;
    }

    boolean previousCameraValid() {
        return previousCameraValid;
    }
}
