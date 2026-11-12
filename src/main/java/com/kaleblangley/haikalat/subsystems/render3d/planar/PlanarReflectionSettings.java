package com.kaleblangley.haikalat.subsystems.render3d.planar;

import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/**
 * Bounded configuration of the first production planar reflection.
 *
 * <p>The first version supports exactly one plane, expressed as a world-space
 * point plus a unit normal pointing toward the retained scene side.  A common
 * horizontal water plane can be written with {@link Builder#planeY(float)}.
 * The plane itself is not finite: consumers such as a water surface keep their
 * own geometric bounds, while this pass only decides which half-space is
 * reflected and where the mirror camera is placed.</p>
 *
 * <p>Every distance is in world units (metres), matching the rest of Render3D.
 * The reflection target is an independent RGBA16F colour plus depth pair whose
 * extent is {@code sceneExtent * resolutionScale}, clamped so its longest edge
 * never exceeds {@link #maxResolution()} pixels.  An update is recorded at most
 * once every {@link #updateIntervalFrames()} calls; skipped calls must not bind
 * or clear the target.</p>
 *
 * <p>{@link #disabled()} is the exact no-op value: no pass, framebuffer,
 * texture, shader or draw is created while planar reflections are disabled.
 * Invalid values are rejected in the canonical constructor instead of being
 * silently clamped.</p>
 */
public record PlanarReflectionSettings(
        boolean enabled,
        Vector3f planePoint,
        Vector3f planeNormal,
        float resolutionScale,
        int maxResolution,
        int updateIntervalFrames,
        float nearPlaneOffset) {
    /** The first version deliberately exposes a single reflection plane only. */
    public static final int MAXIMUM_PLANES = 1;
    /** Largest accepted per-axis resolution cap in pixels. */
    public static final int MAXIMUM_RESOLUTION = 16384;
    /** Largest accepted update interval in frames. */
    public static final int MAXIMUM_UPDATE_INTERVAL_FRAMES = 1000;
    /** Largest accepted absolute plane point coordinate in world metres. */
    public static final float MAXIMUM_ABSOLUTE_PLANE_COORDINATE = 1.0e6f;
    /** Largest accepted near-plane offset in world metres. */
    public static final float MAXIMUM_NEAR_PLANE_OFFSET = 1000.0f;

    private static final float MINIMUM_NORMAL_LENGTH_SQUARED = 1.0e-12f;
    private static final PlanarReflectionSettings DISABLED = new PlanarReflectionSettings(
            false,
            new Vector3f(0.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            1.0f,
            2048,
            1,
            0.0f);

    public PlanarReflectionSettings {
        Vector3f point = new Vector3f(Objects.requireNonNull(planePoint, "planePoint"));
        Vector3f normal = new Vector3f(Objects.requireNonNull(planeNormal, "planeNormal"));
        if (!finite(point.x) || !finite(point.y) || !finite(point.z)
                || Math.abs(point.x) > MAXIMUM_ABSOLUTE_PLANE_COORDINATE
                || Math.abs(point.y) > MAXIMUM_ABSOLUTE_PLANE_COORDINATE
                || Math.abs(point.z) > MAXIMUM_ABSOLUTE_PLANE_COORDINATE) {
            throw new IllegalArgumentException(
                    "planePoint must be finite and within +/-" + MAXIMUM_ABSOLUTE_PLANE_COORDINATE);
        }
        if (!finite(normal.x) || !finite(normal.y) || !finite(normal.z)) {
            throw new IllegalArgumentException("planeNormal must be finite");
        }
        if (normal.lengthSquared() < MINIMUM_NORMAL_LENGTH_SQUARED) {
            throw new IllegalArgumentException("planeNormal must have a non-zero length");
        }
        normal.normalize();
        if (!finite(resolutionScale) || resolutionScale <= 0.0f || resolutionScale > 1.0f) {
            throw new IllegalArgumentException("resolutionScale must be finite and in (0, 1]");
        }
        if (maxResolution < 1 || maxResolution > MAXIMUM_RESOLUTION) {
            throw new IllegalArgumentException("maxResolution must be in [1, "
                    + MAXIMUM_RESOLUTION + "]");
        }
        if (updateIntervalFrames < 1 || updateIntervalFrames > MAXIMUM_UPDATE_INTERVAL_FRAMES) {
            throw new IllegalArgumentException("updateIntervalFrames must be in [1, "
                    + MAXIMUM_UPDATE_INTERVAL_FRAMES + "]");
        }
        if (!finite(nearPlaneOffset) || nearPlaneOffset < 0.0f
                || nearPlaneOffset > MAXIMUM_NEAR_PLANE_OFFSET) {
            throw new IllegalArgumentException("nearPlaneOffset must be finite and in [0, "
                    + MAXIMUM_NEAR_PLANE_OFFSET + "]");
        }
        planePoint = point;
        planeNormal = normal;
    }

    /** Exact no-op value used when no planar reflection was requested. */
    public static PlanarReflectionSettings disabled() {
        return DISABLED;
    }

    /** @return true when the reflection pass and its attachments should exist */
    public boolean active() {
        return enabled;
    }

    /**
     * @return true when the canonical constructor accepted a finite, non-zero
     *         plane normal.  The constructor rejects degenerate planes, so an
     *         active value always has a usable plane; this predicate is the
     *         explicit policy hook used by pipeline wiring.
     */
    public boolean hasPlane() {
        return planeNormal.lengthSquared() > 0.0f;
    }

    /** @return a defensive copy of the world-space plane point */
    @Override
    public Vector3f planePoint() {
        return new Vector3f(planePoint);
    }

    /** @return a defensive copy of the normalized world-space plane normal */
    @Override
    public Vector3f planeNormal() {
        return new Vector3f(planeNormal);
    }

    /** Zero-allocation plane point getter for render recording. */
    public void planePointTo(Vector3f destination) {
        Objects.requireNonNull(destination, "destination").set(planePoint);
    }

    /** Zero-allocation normalized plane normal getter for render recording. */
    public void planeNormalTo(Vector3f destination) {
        Objects.requireNonNull(destination, "destination").set(planeNormal);
    }

    /**
     * Writes the normalized world-space plane equation
     * {@code normal . p + w == 0}.  The normal points toward the retained
     * (scene) side.
     */
    public Vector4f planeEquation(Vector4f destination) {
        Objects.requireNonNull(destination, "destination");
        float nx = planeNormal.x;
        float ny = planeNormal.y;
        float nz = planeNormal.z;
        return destination.set(nx, ny, nz,
                -(nx * planePoint.x + ny * planePoint.y + nz * planePoint.z));
    }

    /**
     * Writes the plane equation used as the oblique near plane: the geometric
     * plane shifted by {@link #nearPlaneOffset()} metres along the normal
     * toward the mirrored camera.  A positive offset therefore clips slightly
     * more than the exact plane and avoids geometry that touches the plane.
     */
    public Vector4f clipPlane(Vector4f destination) {
        planeEquation(destination);
        destination.w += nearPlaneOffset;
        return destination;
    }

    /** Reflection target width in pixels for the given scene extent. */
    public int renderWidth(int sceneWidth, int sceneHeight) {
        validateSceneExtent(sceneWidth, sceneHeight);
        return capped(scaled(sceneWidth), scaled(sceneHeight), true);
    }

    /** Reflection target height in pixels for the given scene extent. */
    public int renderHeight(int sceneWidth, int sceneHeight) {
        validateSceneExtent(sceneWidth, sceneHeight);
        return capped(scaled(sceneWidth), scaled(sceneHeight), false);
    }

    /** Bounded copy of this value with only the enabled flag changed. */
    public PlanarReflectionSettings withEnabled(boolean value) {
        return new PlanarReflectionSettings(value, new Vector3f(planePoint),
                new Vector3f(planeNormal), resolutionScale, maxResolution,
                updateIntervalFrames, nearPlaneOffset);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder mirroring the record's validated ranges. */
    public static final class Builder {
        private boolean enabled;
        private Vector3f planePoint = new Vector3f(0.0f, 0.0f, 0.0f);
        private Vector3f planeNormal = new Vector3f(0.0f, 1.0f, 0.0f);
        private float resolutionScale = 1.0f;
        private int maxResolution = 2048;
        private int updateIntervalFrames = 1;
        private float nearPlaneOffset;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        /** Sets a horizontal plane {@code y == level} with an upward normal. */
        public Builder planeY(float level) {
            planePoint = new Vector3f(0.0f, level, 0.0f);
            planeNormal = new Vector3f(0.0f, 1.0f, 0.0f);
            return this;
        }

        /**
         * Sets a general world-space plane.
         *
         * @param pointX  point on the plane
         * @param pointY  point on the plane
         * @param pointZ  point on the plane
         * @param normalX normal component; the vector may have any non-zero length
         * @param normalY normal component
         * @param normalZ normal component
         */
        public Builder plane(float pointX, float pointY, float pointZ,
                             float normalX, float normalY, float normalZ) {
            planePoint = new Vector3f(pointX, pointY, pointZ);
            planeNormal = new Vector3f(normalX, normalY, normalZ);
            return this;
        }

        public Builder resolutionScale(float value) {
            resolutionScale = value;
            return this;
        }

        public Builder maxResolution(int value) {
            maxResolution = value;
            return this;
        }

        public Builder updateIntervalFrames(int value) {
            updateIntervalFrames = value;
            return this;
        }

        public Builder nearPlaneOffset(float value) {
            nearPlaneOffset = value;
            return this;
        }

        public PlanarReflectionSettings build() {
            return new PlanarReflectionSettings(enabled, planePoint, planeNormal,
                    resolutionScale, maxResolution, updateIntervalFrames, nearPlaneOffset);
        }
    }

    private int scaled(int value) {
        long result = Math.round(value * (double) resolutionScale);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, result));
    }

    private int capped(int width, int height, boolean wantWidth) {
        int longest = Math.max(width, height);
        if (longest <= maxResolution) {
            return wantWidth ? width : height;
        }
        int value = wantWidth ? width : height;
        long scaled = Math.round(value * (double) maxResolution / longest);
        return (int) Math.max(1L, Math.min(maxResolution, scaled));
    }

    private static void validateSceneExtent(int sceneWidth, int sceneHeight) {
        if (sceneWidth <= 0 || sceneHeight <= 0) {
            throw new IllegalArgumentException("scene extent must be positive");
        }
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
