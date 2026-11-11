package com.kaleblangley.haikalat.subsystems.postprocess;

/**
 * Bounded motion-blur settings for the HDR colour chain.
 *
 * <p>The effect is disabled by default and contributes no pass, shader or
 * attachment when inactive.  Motion is reconstructed from the shared
 * screen-space velocity buffer; the shutter fraction scales the per-frame
 * displacement to the exposure interval (0.5 approximates a 180 degree
 * shutter), and the maximum radius clamps the reconstruction to a bounded
 * output-pixel footprint.  No temporal history is owned or sampled, so a
 * camera cut cannot smear the previous shot into this frame.</p>
 */
public record MotionBlurSettings(boolean enabled, float shutterFraction,
                                 float maxRadiusPixels) {
    /** A closed shutter contributes no motion, matching {@link #active()} false. */
    public static final float MINIMUM_SHUTTER_FRACTION = 0.0f;
    /** The exposure interval cannot exceed one frame interval in this model. */
    public static final float MAXIMUM_SHUTTER_FRACTION = 1.0f;
    /** Largest accepted reconstruction radius in output pixels. */
    public static final float MAXIMUM_RADIUS_PIXELS = 64.0f;

    public MotionBlurSettings {
        if (!Float.isFinite(shutterFraction)
                || shutterFraction < MINIMUM_SHUTTER_FRACTION
                || shutterFraction > MAXIMUM_SHUTTER_FRACTION) {
            throw new IllegalArgumentException("shutterFraction must be finite and in ["
                    + MINIMUM_SHUTTER_FRACTION + ", " + MAXIMUM_SHUTTER_FRACTION + "]");
        }
        if (!Float.isFinite(maxRadiusPixels)
                || maxRadiusPixels < 0.0f || maxRadiusPixels > MAXIMUM_RADIUS_PIXELS) {
            throw new IllegalArgumentException("maxRadiusPixels must be finite and in [0, "
                    + MAXIMUM_RADIUS_PIXELS + "]");
        }
    }

    public static MotionBlurSettings disabled() {
        return new MotionBlurSettings(false, 0.5f, 16.0f);
    }

    /** Enables the effect with the supplied exposure and radius bounds. */
    public static MotionBlurSettings of(float shutterFraction, float maxRadiusPixels) {
        return new MotionBlurSettings(true, shutterFraction, maxRadiusPixels);
    }

    /**
     * A closed shutter or a zero maximum radius is an exact no-op: no pass,
     * shader or attachment is created.
     */
    public boolean active() {
        return enabled && shutterFraction > 0.0f && maxRadiusPixels > 0.0f;
    }

    public MotionBlurSettings withEnabled(boolean value) {
        return new MotionBlurSettings(value, shutterFraction, maxRadiusPixels);
    }

    public MotionBlurSettings withShutterFraction(float value) {
        return new MotionBlurSettings(enabled, value, maxRadiusPixels);
    }

    public MotionBlurSettings withMaxRadiusPixels(float value) {
        return new MotionBlurSettings(enabled, shutterFraction, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled;
        private float shutterFraction = 0.5f;
        private float maxRadiusPixels = 16.0f;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder shutterFraction(float value) {
            shutterFraction = value;
            return this;
        }

        public Builder maxRadiusPixels(float value) {
            maxRadiusPixels = value;
            return this;
        }

        public MotionBlurSettings build() {
            return new MotionBlurSettings(enabled, shutterFraction, maxRadiusPixels);
        }
    }
}
