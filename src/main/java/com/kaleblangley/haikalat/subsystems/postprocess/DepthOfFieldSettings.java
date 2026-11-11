package com.kaleblangley.haikalat.subsystems.postprocess;

/**
 * Bounded depth-of-field settings for the HDR colour chain.
 *
 * <p>The effect is disabled by default and contributes no pass, shader or
 * attachment when inactive.  Focus distance is expressed in world units
 * (metres), aperture is the conventional f-number of a fixed 50 mm
 * full-frame-equivalent lens model, and maximum CoC is the output-pixel radius
 * clamp.  The model is an artistic approximation: it does not derive a focal
 * length or sensor width from the camera projection.</p>
 */
public record DepthOfFieldSettings(boolean enabled, float focusDistance,
                                   float aperture, float maxCocPixels) {
    /** Smallest accepted focus distance in world units. */
    public static final float MINIMUM_FOCUS_DISTANCE = 0.1f;
    /** Largest accepted focus distance in world units. */
    public static final float MAXIMUM_FOCUS_DISTANCE = 1000.0f;
    /** Smallest accepted f-number. */
    public static final float MINIMUM_APERTURE = 1.0f;
    /** Largest accepted f-number. */
    public static final float MAXIMUM_APERTURE = 22.0f;
    /** Largest accepted circle-of-confusion radius in output pixels. */
    public static final float MAXIMUM_COC_PIXELS = 16.0f;

    public DepthOfFieldSettings {
        if (!Float.isFinite(focusDistance)
                || focusDistance < MINIMUM_FOCUS_DISTANCE
                || focusDistance > MAXIMUM_FOCUS_DISTANCE) {
            throw new IllegalArgumentException("focusDistance must be finite and in ["
                    + MINIMUM_FOCUS_DISTANCE + ", " + MAXIMUM_FOCUS_DISTANCE + "]");
        }
        if (!Float.isFinite(aperture)
                || aperture < MINIMUM_APERTURE || aperture > MAXIMUM_APERTURE) {
            throw new IllegalArgumentException("aperture must be finite and in ["
                    + MINIMUM_APERTURE + ", " + MAXIMUM_APERTURE + "]");
        }
        if (!Float.isFinite(maxCocPixels)
                || maxCocPixels < 0.0f || maxCocPixels > MAXIMUM_COC_PIXELS) {
            throw new IllegalArgumentException("maxCocPixels must be finite and in [0, "
                    + MAXIMUM_COC_PIXELS + "]");
        }
    }

    public static DepthOfFieldSettings disabled() {
        return new DepthOfFieldSettings(false, 4.0f, 2.8f, 8.0f);
    }

    /** Enables the effect with the supplied lens parameters. */
    public static DepthOfFieldSettings of(float focusDistance, float aperture,
                                          float maxCocPixels) {
        return new DepthOfFieldSettings(true, focusDistance, aperture, maxCocPixels);
    }

    /** A zero maximum CoC is an exact no-op: no pass, shader or attachment is created. */
    public boolean active() {
        return enabled && maxCocPixels > 0.0f;
    }

    public DepthOfFieldSettings withEnabled(boolean value) {
        return new DepthOfFieldSettings(value, focusDistance, aperture, maxCocPixels);
    }

    public DepthOfFieldSettings withFocusDistance(float value) {
        return new DepthOfFieldSettings(enabled, value, aperture, maxCocPixels);
    }

    public DepthOfFieldSettings withAperture(float value) {
        return new DepthOfFieldSettings(enabled, focusDistance, value, maxCocPixels);
    }

    public DepthOfFieldSettings withMaxCocPixels(float value) {
        return new DepthOfFieldSettings(enabled, focusDistance, aperture, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled;
        private float focusDistance = 4.0f;
        private float aperture = 2.8f;
        private float maxCocPixels = 8.0f;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder focusDistance(float value) {
            focusDistance = value;
            return this;
        }

        public Builder aperture(float value) {
            aperture = value;
            return this;
        }

        public Builder maxCocPixels(float value) {
            maxCocPixels = value;
            return this;
        }

        public DepthOfFieldSettings build() {
            return new DepthOfFieldSettings(enabled, focusDistance, aperture, maxCocPixels);
        }
    }
}
