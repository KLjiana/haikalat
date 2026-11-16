package com.kaleblangley.haikalat.subsystems.render3d.water;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Bounded configuration of the first underwater volume pass.
 *
 * <p>The pass is a screen-space Beer-Lambert approximation, not participating
 * media: for a camera strictly below the configured water plane it attenuates
 * the current scene colour by {@code exp(-absorptionDensity * path)} and adds
 * {@code scatterColor * (1 - attenuation)}, where {@code path} is the
 * world-space distance from the camera to the reconstructed scene surface,
 * clamped to {@link #maximumDistanceMetres()}.  Distances are world units
 * interpreted as metres, matching the rest of Render3D.  There is no
 * dispersion, no multi-layer water and no wave displacement of the entry
 * plane; the plane height comes from the active {@link WaterSurfaceSettings}.</p>
 *
 * <p>A camera at or above the water plane keeps the scene colour unchanged, so
 * the pass can stay in the graph with a stable topology instead of rebuilding
 * the frame when the camera crosses the plane.  {@link #disabled()} is the
 * exact no-op value: no pass, attachment, shader or draw exists while the
 * settings are disabled.</p>
 */
public record UnderwaterVolumeSettings(
        boolean enabled,
        float absorptionDensity,
        Vector3f scatterColor,
        float maximumDistanceMetres) {
    /** Largest accepted scalar absorption density in 1/metre. */
    public static final float MAXIMUM_ABSORPTION_DENSITY = 8.0f;
    /** Largest accepted linear colour component for the scatter tint. */
    public static final float MAXIMUM_COLOR_COMPONENT = 16.0f;
    /** Smallest accepted path-length clamp in world metres. */
    public static final float MINIMUM_DISTANCE_METRES = 0.1f;
    /** Largest accepted path-length clamp in world metres. */
    public static final float MAXIMUM_DISTANCE_METRES = 500.0f;
    /** Default path-length clamp in world metres. */
    public static final float DEFAULT_DISTANCE_METRES = 60.0f;

    public UnderwaterVolumeSettings {
        if (!finite(absorptionDensity) || absorptionDensity < 0.0f
                || absorptionDensity > MAXIMUM_ABSORPTION_DENSITY) {
            throw new IllegalArgumentException("absorptionDensity must be finite and in [0, "
                    + MAXIMUM_ABSORPTION_DENSITY + "]");
        }
        if (!finite(maximumDistanceMetres) || maximumDistanceMetres < MINIMUM_DISTANCE_METRES
                || maximumDistanceMetres > MAXIMUM_DISTANCE_METRES) {
            throw new IllegalArgumentException("maximumDistanceMetres must be finite and in ["
                    + MINIMUM_DISTANCE_METRES + ", " + MAXIMUM_DISTANCE_METRES + "]");
        }
        scatterColor = color(scatterColor, "scatterColor");
    }

    /** Exact no-op value used when no underwater volume was requested. */
    public static UnderwaterVolumeSettings disabled() {
        return new UnderwaterVolumeSettings(false, 0.0f,
                new Vector3f(0.02f, 0.12f, 0.16f), DEFAULT_DISTANCE_METRES);
    }

    /** @return true when the underwater volume pass and its draw should exist */
    public boolean active() {
        return enabled;
    }

    /** Linear scatter tint; returned as a defensive copy. */
    @Override
    public Vector3f scatterColor() {
        return new Vector3f(scatterColor);
    }

    /** Zero-allocation scatter colour getter for render recording. */
    public void scatterColorTo(Vector3f destination) {
        Objects.requireNonNull(destination, "destination").set(scatterColor);
    }

    /** Bounded copy of this value with only the enabled flag changed. */
    public UnderwaterVolumeSettings withEnabled(boolean value) {
        return new UnderwaterVolumeSettings(value, absorptionDensity,
                new Vector3f(scatterColor), maximumDistanceMetres);
    }

    /** Bounded copy of this value with only the absorption density changed. */
    public UnderwaterVolumeSettings withAbsorptionDensity(float value) {
        return new UnderwaterVolumeSettings(enabled, value,
                new Vector3f(scatterColor), maximumDistanceMetres);
    }

    /** Bounded copy of this value with only the scatter tint changed. */
    public UnderwaterVolumeSettings withScatterColor(Vector3f value) {
        return new UnderwaterVolumeSettings(enabled, absorptionDensity,
                new Vector3f(Objects.requireNonNull(value, "scatterColor")),
                maximumDistanceMetres);
    }

    /** Bounded copy of this value with only the scatter tint changed. */
    public UnderwaterVolumeSettings withScatterColor(float red, float green, float blue) {
        return withScatterColor(new Vector3f(red, green, blue));
    }

    /** Bounded copy of this value with only the path-length clamp changed. */
    public UnderwaterVolumeSettings withMaximumDistanceMetres(float value) {
        return new UnderwaterVolumeSettings(enabled, absorptionDensity,
                new Vector3f(scatterColor), value);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder mirroring the record's validated ranges. */
    public static final class Builder {
        private boolean enabled;
        private float absorptionDensity = 0.25f;
        private Vector3f scatterColor = new Vector3f(0.02f, 0.12f, 0.16f);
        private float maximumDistanceMetres = DEFAULT_DISTANCE_METRES;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder absorptionDensity(float value) {
            absorptionDensity = value;
            return this;
        }

        public Builder scatterColor(float red, float green, float blue) {
            scatterColor = new Vector3f(red, green, blue);
            return this;
        }

        public Builder scatterColor(Vector3f value) {
            scatterColor = new Vector3f(Objects.requireNonNull(value, "scatterColor"));
            return this;
        }

        public Builder maximumDistanceMetres(float value) {
            maximumDistanceMetres = value;
            return this;
        }

        public UnderwaterVolumeSettings build() {
            return new UnderwaterVolumeSettings(enabled, absorptionDensity,
                    new Vector3f(scatterColor), maximumDistanceMetres);
        }
    }

    private static Vector3f color(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!finite(value.x) || !finite(value.y) || !finite(value.z)
                || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.x > MAXIMUM_COLOR_COMPONENT || value.y > MAXIMUM_COLOR_COMPONENT
                || value.z > MAXIMUM_COLOR_COMPONENT) {
            throw new IllegalArgumentException(name + " must be finite and in [0, "
                    + MAXIMUM_COLOR_COMPONENT + "]");
        }
        return new Vector3f(value);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
