package com.kaleblangley.haikalat.subsystems.render3d.scene;

import org.joml.Matrix4f;
import org.joml.Vector2f;

import java.util.Objects;

/**
 * Immutable current/previous successful frame camera facts.  Previous values
 * only advance through {@link #commitSuccessfulFrame()}; recording commands or
 * executing a failed frame never publishes them.
 */
public final class TemporalFrameState {
    static final float CAMERA_CUT_POSITION_DISTANCE_SQUARED = 16.0f;
    static final float CAMERA_CUT_FORWARD_DOT = 0.8f;

    private FrameParameters current;
    private FrameParameters previous;
    private boolean pending;

    public void prepare(FrameParameters candidate) {
        current = Objects.requireNonNull(candidate, "candidate");
        pending = true;
    }

    public void commitSuccessfulFrame() {
        if (!pending) return;
        previous = current;
        pending = false;
    }

    public void discardFrame() {
        pending = false;
        current = previous;
    }

    public void invalidate() {
        previous = null;
        pending = false;
    }

    boolean pending() {
        return pending;
    }

    public FrameParameters current() {
        return current;
    }

    public FrameParameters previous() {
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
    public static boolean looksLikeCameraCut(FrameParameters previous, FrameParameters current) {
        if (previous == null || current == null) return false;
        return looksLikeCameraCut(previous, current.view,
                current.cameraPositionX, current.cameraPositionY, current.cameraPositionZ,
                current.forwardX, current.forwardY, current.forwardZ);
    }

    /**
     * Pose-only camera-cut check that can run before the current frame's jitter
     * is captured.
     *
     * <p>The frame setup needs to decide on a cut before it reads the reduced
     * jitter phase, otherwise the cut frame would be rasterised with the old
     * phase and the sequence could not restart at zero for that same frame.
     * This overload takes the already-frozen view/position/forward facts; the
     * criteria are identical to {@link #looksLikeCameraCut(FrameParameters,
     * FrameParameters)}.</p>
     *
     * @param previous previous successful frame parameters (may be {@code null})
     * @param view     current frozen view matrix
     * @param positionX current camera position x
     * @param positionY current camera position y
     * @param positionZ current camera position z
     * @param forwardX current camera forward x
     * @param forwardY current camera forward y
     * @param forwardZ current camera forward z
     * @return true when the previous successful pose must not be trusted
     */
    public static boolean looksLikeCameraCut(FrameParameters previous, Matrix4f view,
                                             float positionX, float positionY, float positionZ,
                                             float forwardX, float forwardY, float forwardZ) {
        if (previous == null || view == null) return false;
        if (!previous.view.equals(view, 1.0e-6f)) {
            float dx = previous.cameraPositionX - positionX;
            float dy = previous.cameraPositionY - positionY;
            float dz = previous.cameraPositionZ - positionZ;
            if (dx * dx + dy * dy + dz * dz
                    > CAMERA_CUT_POSITION_DISTANCE_SQUARED) {
                return true;
            }
            float dot = previous.forwardX * forwardX
                    + previous.forwardY * forwardY
                    + previous.forwardZ * forwardZ;
            return dot < CAMERA_CUT_FORWARD_DOT;
        }
        return false;
    }

    /** Immutable per-frame camera parameters shared by surface and resolve passes. */
    public record FrameParameters(
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
        public FrameParameters {
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
