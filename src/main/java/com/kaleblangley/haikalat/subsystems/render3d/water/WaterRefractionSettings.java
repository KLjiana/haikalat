package com.kaleblangley.haikalat.subsystems.render3d.water;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Bounded configuration of the screen-space refraction sampled by the finite
 * water surface.
 *
 * <p>This is an independent value object instead of an extension of
 * {@link WaterSurfaceSettings}; the established water API stays unchanged and
 * {@link #disabled()} reproduces the exact pre-refraction water behaviour.
 * Every distance is in world units (metres).  The refraction is a screen-space
 * approximation: the wave normal bends the camera-to-surface ray, the shared
 * opaque depth supplies the vertical water thickness, and the shifted lookup is
 * sampled from the single-sample background copy owned by the water pass.  It
 * is not a volumetric underwater light-transport solution.</p>
 *
 * <p>Deliberately excluded: real underwater volume rendering, chromatic
 * dispersion, multi-layer water bodies, caustics and refraction of other
 * transparent surfaces.  Glass/shield refraction ({@code RefractionSettings})
 * remains a separate single-layer material path and is not reused here.</p>
 *
 * @param foregroundRejectionBias wet-path distance in metres over which a
 *        geometrically valid tap fades in; zero uses a hard rejection boundary
 */
public record WaterRefractionSettings(
        boolean enabled,
        float ior,
        float strength,
        float maximumScreenOffset,
        float thicknessScale,
        float foregroundRejectionBias,
        OffscreenFallback offscreenFallback,
        Vector3f offscreenFallbackColor) {
    /** Smallest accepted refractive index (vacuum/air). */
    public static final float MINIMUM_IOR = 1.0f;
    /** Largest accepted refractive index (same bounded range as the glass path). */
    public static final float MAXIMUM_IOR = 2.5f;
    /** Largest accepted dimensionless offset multiplier. */
    public static final float MAXIMUM_STRENGTH = 2.0f;
    /** Largest accepted screen-space offset length in UV units. */
    public static final float MAXIMUM_SCREEN_OFFSET = 0.5f;
    /** Largest accepted vertical-thickness multiplier. */
    public static final float MAXIMUM_THICKNESS_SCALE = 8.0f;
    /** Largest accepted wet-path confidence transition distance in metres. */
    public static final float MAXIMUM_FOREGROUND_REJECTION_BIAS = 10.0f;
    /** Largest accepted linear component of the off-screen fallback colour. */
    public static final float MAXIMUM_COLOR_COMPONENT = 16.0f;

    /**
     * Behaviour when the refracted lookup is unusable (off-screen, no opaque
     * background, foreground occlusion or a non-finite offset).
     */
    public enum OffscreenFallback {
        /** Keep the background already under this pixel and only shade the water over it. */
        UNREFRACTED(0),
        /** Replace the refracted lookup with {@link #offscreenFallbackColor()}. */
        TINT(1),
        /** Ease outward displacement at viewport edges; use tint if no wet receiver is visible. */
        EDGE_FADE_TINT(2);

        private final int shaderValue;

        OffscreenFallback(int shaderValue) {
            this.shaderValue = shaderValue;
        }

        /** @return the integer written to {@code uRefractionFallbackMode} */
        int shaderValue() {
            return shaderValue;
        }
    }

    public WaterRefractionSettings {
        if (!finite(ior) || ior < MINIMUM_IOR || ior > MAXIMUM_IOR) {
            throw new IllegalArgumentException("ior must be finite and in ["
                    + MINIMUM_IOR + ", " + MAXIMUM_IOR + "]");
        }
        if (!finite(strength) || strength < 0.0f || strength > MAXIMUM_STRENGTH) {
            throw new IllegalArgumentException("strength must be finite and in [0, "
                    + MAXIMUM_STRENGTH + "]");
        }
        if (!finite(maximumScreenOffset) || maximumScreenOffset < 0.0f
                || maximumScreenOffset > MAXIMUM_SCREEN_OFFSET) {
            throw new IllegalArgumentException("maximumScreenOffset must be finite and in [0, "
                    + MAXIMUM_SCREEN_OFFSET + "]");
        }
        if (!finite(thicknessScale) || thicknessScale < 0.0f
                || thicknessScale > MAXIMUM_THICKNESS_SCALE) {
            throw new IllegalArgumentException("thicknessScale must be finite and in [0, "
                    + MAXIMUM_THICKNESS_SCALE + "]");
        }
        if (!finite(foregroundRejectionBias) || foregroundRejectionBias < 0.0f
                || foregroundRejectionBias > MAXIMUM_FOREGROUND_REJECTION_BIAS) {
            throw new IllegalArgumentException("foregroundRejectionBias must be finite and in [0, "
                    + MAXIMUM_FOREGROUND_REJECTION_BIAS + "]");
        }
        Objects.requireNonNull(offscreenFallback, "offscreenFallback");
        offscreenFallbackColor = color(offscreenFallbackColor);
    }

    /** Exact no-op value; the water pass keeps its pre-refraction shading path. */
    public static WaterRefractionSettings disabled() {
        return new WaterRefractionSettings(false, 1.333f, 1.0f, 0.02f, 1.0f, 0.05f,
                OffscreenFallback.UNREFRACTED, new Vector3f(0.02f, 0.12f, 0.16f));
    }

    /** @return true when screen-space refraction replaces the direct background lookup */
    public boolean active() {
        return enabled;
    }

    /** Linear fallback colour used by the tint policies; defensive copy. */
    @Override
    public Vector3f offscreenFallbackColor() {
        return new Vector3f(offscreenFallbackColor);
    }

    /** Zero-allocation fallback colour getter for render recording. */
    public void offscreenFallbackColorTo(Vector3f destination) {
        Objects.requireNonNull(destination, "destination").set(offscreenFallbackColor);
    }

    /** Bounded copy of this value with only the enabled flag changed. */
    public WaterRefractionSettings withEnabled(boolean value) {
        return new WaterRefractionSettings(value, ior, strength, maximumScreenOffset,
                thicknessScale, foregroundRejectionBias, offscreenFallback,
                new Vector3f(offscreenFallbackColor));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder mirroring the record's validated ranges. */
    public static final class Builder {
        private boolean enabled;
        private float ior = 1.333f;
        private float strength = 1.0f;
        private float maximumScreenOffset = 0.02f;
        private float thicknessScale = 1.0f;
        private float foregroundRejectionBias = 0.05f;
        private OffscreenFallback offscreenFallback = OffscreenFallback.UNREFRACTED;
        private Vector3f offscreenFallbackColor = new Vector3f(0.02f, 0.12f, 0.16f);

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder ior(float value) {
            ior = value;
            return this;
        }

        public Builder strength(float value) {
            strength = value;
            return this;
        }

        public Builder maximumScreenOffset(float value) {
            maximumScreenOffset = value;
            return this;
        }

        public Builder thicknessScale(float value) {
            thicknessScale = value;
            return this;
        }

        public Builder foregroundRejectionBias(float value) {
            foregroundRejectionBias = value;
            return this;
        }

        public Builder offscreenFallback(OffscreenFallback value) {
            offscreenFallback = Objects.requireNonNull(value, "offscreenFallback");
            return this;
        }

        public Builder offscreenFallbackColor(float red, float green, float blue) {
            offscreenFallbackColor = new Vector3f(red, green, blue);
            return this;
        }

        public WaterRefractionSettings build() {
            return new WaterRefractionSettings(enabled, ior, strength, maximumScreenOffset,
                    thicknessScale, foregroundRejectionBias, offscreenFallback,
                    new Vector3f(offscreenFallbackColor));
        }
    }

    private static Vector3f color(Vector3f value) {
        Objects.requireNonNull(value, "offscreenFallbackColor");
        if (!finite(value.x) || !finite(value.y) || !finite(value.z)
                || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.x > MAXIMUM_COLOR_COMPONENT || value.y > MAXIMUM_COLOR_COMPONENT
                || value.z > MAXIMUM_COLOR_COMPONENT) {
            throw new IllegalArgumentException("offscreenFallbackColor must be finite and in [0, "
                    + MAXIMUM_COLOR_COMPONENT + "]");
        }
        return new Vector3f(value);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
