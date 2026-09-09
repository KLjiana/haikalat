package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector2f;

import java.util.Objects;

/**
 * Immutable current/previous successful frame camera facts.  Previous values
 * only advance through {@link #commitSuccessfulFrame()}; recording commands or
 * executing a failed frame never publishes them.
 */
final class TemporalFrameState {
    static final float CAMERA_CUT_POSITION_DISTANCE_SQUARED = 16.0f;
    static final float CAMERA_CUT_FORWARD_DOT = 0.8f;

    private FrameParameters current;
    private FrameParameters previous;
    private boolean pending;

    void prepare(FrameParameters candidate) {
        current = Objects.requireNonNull(candidate, "candidate");
        pending = true;
    }

    void commitSuccessfulFrame() {
        if (!pending) return;
        previous = current;
        pending = false;
    }

    void discardFrame() {
        pending = false;
        current = previous;
    }

    void invalidate() {
        previous = null;
        pending = false;
    }

    boolean pending() {
        return pending;
    }

    FrameParameters current() {
        return current;
    }

    FrameParameters previous() {
        return previous;
    }

    boolean hasPrevious() {
        return previous != null;
    }

    /**
     * True when the camera moved or rotated so far that reprojecting the
     * previous frame would sample unrelated history.  The explicit reset API
     * remains the primary contract; this is only a conservative fallback.
     */
    static boolean looksLikeCameraCut(FrameParameters previous, FrameParameters current) {
        if (previous == null || current == null) return false;
        if (!previous.view.equals(current.view, 1.0e-6f)) {
            float dx = previous.cameraPositionX - current.cameraPositionX;
            float dy = previous.cameraPositionY - current.cameraPositionY;
            float dz = previous.cameraPositionZ - current.cameraPositionZ;
            if (dx * dx + dy * dy + dz * dz
                    > CAMERA_CUT_POSITION_DISTANCE_SQUARED) {
                return true;
            }
            float dot = previous.forwardX * current.forwardX
                    + previous.forwardY * current.forwardY
                    + previous.forwardZ * current.forwardZ;
            return dot < CAMERA_CUT_FORWARD_DOT;
        }
        return false;
    }

    /** Immutable per-frame camera parameters shared by surface and resolve passes. */
    record FrameParameters(
            long frameSequence,
            int width,
            int height,
            float nearPlane,
            float farPlane,
            float cameraPositionX,
            float cameraPositionY,
            float cameraPositionZ,
            float forwardX,
            float forwardY,
            float forwardZ,
            float jitterUvX,
            float jitterUvY,
            Matrix4f stableProjection,
            Matrix4f jitteredProjection,
            Matrix4f inverseJitteredProjection,
            Matrix4f view,
            Matrix4f stableViewProjection,
            Matrix4f inverseStableViewProjection,
            long cameraRevision) {
        FrameParameters {
            if (frameSequence < 0L) {
                throw new IllegalArgumentException("frame sequence must be non-negative");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("frame extent must be positive");
            }
            if (!Float.isFinite(nearPlane) || nearPlane <= 0.0f
                    || !Float.isFinite(farPlane) || farPlane <= nearPlane) {
                throw new IllegalArgumentException("invalid temporal near/far planes");
            }
            stableProjection = new Matrix4f(Objects.requireNonNull(stableProjection, "stableProjection"));
            jitteredProjection = new Matrix4f(Objects.requireNonNull(jitteredProjection, "jitteredProjection"));
            inverseJitteredProjection = new Matrix4f(
                    Objects.requireNonNull(inverseJitteredProjection, "inverseJitteredProjection"));
            view = new Matrix4f(Objects.requireNonNull(view, "view"));
            stableViewProjection = new Matrix4f(
                    Objects.requireNonNull(stableViewProjection, "stableViewProjection"));
            inverseStableViewProjection = new Matrix4f(
                    Objects.requireNonNull(inverseStableViewProjection, "inverseStableViewProjection"));
        }

        Vector2f jitterUv(Vector2f destination) {
            return (destination == null ? new Vector2f() : destination).set(jitterUvX, jitterUvY);
        }
    }
}
