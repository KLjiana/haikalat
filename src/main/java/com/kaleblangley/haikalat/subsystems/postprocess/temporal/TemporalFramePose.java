package com.kaleblangley.haikalat.subsystems.postprocess.temporal;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * Camera facts for one frame of the post-process colour chain.
 *
 * <p>Render3D captures the temporal camera state before the frame starts and
 * adapts it to this post-process-owned value; the colour chain never reads a
 * mutable camera or the Render3D frame context.</p>
 */
public record TemporalFramePose(
        float deltaSeconds,
        int width,
        int height,
        float jitterUvX,
        float jitterUvY,
        Matrix4f inverseJitteredProjection,
        Matrix4f inverseView,
        Matrix4f stableViewProjection,
        Matrix4f fogInverseViewProjection,
        Vector3f fogCameraPosition) {
    public TemporalFramePose {
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("temporal pose extent must be positive");
        }
        if (!Float.isFinite(jitterUvX) || !Float.isFinite(jitterUvY)) {
            throw new IllegalArgumentException("temporal jitter must be finite");
        }
        inverseJitteredProjection = new Matrix4f(
                Objects.requireNonNull(inverseJitteredProjection, "inverseJitteredProjection"));
        inverseView = new Matrix4f(Objects.requireNonNull(inverseView, "inverseView"));
        stableViewProjection = new Matrix4f(
                Objects.requireNonNull(stableViewProjection, "stavableViewProjection"));
        if ((fogInverseViewProjection == null) != (fogCameraPosition == null)) {
            throw new IllegalArgumentException("fog pose requires both the inverse view-projection and camera position");
        }
        if (fogInverseViewProjection != null) {
            fogInverseViewProjection = new Matrix4f(fogInverseViewProjection);
            fogCameraPosition = new Vector3f(fogCameraPosition);
        }
    }

    public boolean fogAvailable() {
        return fogInverseViewProjection != null;
    }

    /** The same pose with the per-frame delta clamped to the post-process maximum. */
    public TemporalFramePose withDeltaSeconds(float value) {
        return new TemporalFramePose(value, width, height, jitterUvX, jitterUvY,
                inverseJitteredProjection, inverseView, stableViewProjection,
                fogInverseViewProjection, fogCameraPosition);
    }
}
