package com.kaleblangley.haikalat.subsystems.postprocess;

/** Distance and world-height exponential fog settings. */
public record FogSettings(
        boolean enabled,
        float red,
        float green,
        float blue,
        float distanceDensity,
        float heightDensity,
        float heightFalloff,
        float baseHeight,
        float maximumOpacity
) {
    public FogSettings {
        requireUnit(red, "red");
        requireUnit(green, "green");
        requireUnit(blue, "blue");
        requireNonNegative(distanceDensity, "distanceDensity");
        requireNonNegative(heightDensity, "heightDensity");
        requireNonNegative(heightFalloff, "heightFalloff");
        if (!Float.isFinite(baseHeight)) {
            throw new IllegalArgumentException("baseHeight must be finite");
        }
        requireUnit(maximumOpacity, "maximumOpacity");
    }

    public static FogSettings disabled() {
        return builder().enabled(false).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled = true;
        private float red = 0.55f;
        private float green = 0.62f;
        private float blue = 0.70f;
        private float distanceDensity = 0.018f;
        private float heightDensity = 0.035f;
        private float heightFalloff = 0.22f;
        private float baseHeight;
        private float maximumOpacity = 0.92f;

        private Builder() {
        }

        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder color(float r, float g, float b) {
            red = r; green = g; blue = b; return this;
        }
        public Builder distanceDensity(float value) { distanceDensity = value; return this; }
        public Builder heightDensity(float value) { heightDensity = value; return this; }
        public Builder heightFalloff(float value) { heightFalloff = value; return this; }
        public Builder baseHeight(float value) { baseHeight = value; return this; }
        public Builder maximumOpacity(float value) { maximumOpacity = value; return this; }

        public FogSettings build() {
            return new FogSettings(enabled, red, green, blue, distanceDensity, heightDensity,
                    heightFalloff, baseHeight, maximumOpacity);
        }
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and within [0, 1]");
        }
    }

    private static void requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
