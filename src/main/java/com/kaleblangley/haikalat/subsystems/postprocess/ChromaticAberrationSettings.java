package com.kaleblangley.haikalat.subsystems.postprocess;

/**
 * Bounded radial chromatic-aberration settings.
 *
 * <p>Offsets are expressed as a fraction of the viewport radius, so the same
 * value behaves consistently at different resolutions.  Disabled by default;
 * zero intensity contributes no pass.</p>
 */
public record ChromaticAberrationSettings(boolean enabled, float intensity,
                                          float radial, int samples) {
    public ChromaticAberrationSettings {
        if (!Float.isFinite(intensity) || intensity < 0.0f || intensity > 0.02f) {
            throw new IllegalArgumentException("aberration intensity must be finite and in [0, 0.02]");
        }
        if (!Float.isFinite(radial) || radial < 0.0f || radial > 1.0f) {
            throw new IllegalArgumentException("aberration radial must be finite and in [0, 1]");
        }
        if (samples < 1 || samples > 8) {
            throw new IllegalArgumentException("aberration samples must be in [1, 8]");
        }
    }

    public static ChromaticAberrationSettings disabled() {
        return new ChromaticAberrationSettings(false, 0.0f, 1.0f, 3);
    }

    public static ChromaticAberrationSettings of(float intensity, float radial, int samples) {
        return new ChromaticAberrationSettings(intensity > 0.0f, intensity, radial, samples);
    }

    public boolean active() {
        return enabled && intensity > 0.0f;
    }

    public ChromaticAberrationSettings withEnabled(boolean value) {
        return new ChromaticAberrationSettings(value, intensity, radial, samples);
    }

    public ChromaticAberrationSettings withIntensity(float value) {
        return new ChromaticAberrationSettings(enabled, value, radial, samples);
    }

    public ChromaticAberrationSettings withRadial(float value) {
        return new ChromaticAberrationSettings(enabled, intensity, value, samples);
    }

    public ChromaticAberrationSettings withSamples(int value) {
        return new ChromaticAberrationSettings(enabled, intensity, radial, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled;
        private float intensity = 0.004f;
        private float radial = 1.0f;
        private int samples = 3;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder intensity(float value) {
            intensity = value;
            return this;
        }

        public Builder radial(float value) {
            radial = value;
            return this;
        }

        public Builder samples(int value) {
            samples = value;
            return this;
        }

        public ChromaticAberrationSettings build() {
            return new ChromaticAberrationSettings(enabled, intensity, radial, samples);
        }
    }
}
