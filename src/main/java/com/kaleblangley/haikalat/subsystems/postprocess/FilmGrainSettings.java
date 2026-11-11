package com.kaleblangley.haikalat.subsystems.postprocess;

/**
 * Display-referred film-grain settings.
 *
 * <p>Disabled by default and a no-op at zero intensity.  {@code response}
 * controls how much the grain is modulated by scene luminance (0 = uniform,
 * 1 = strongest in midtones).  {@code seed} makes the pattern reproducible.</p>
 */
public record FilmGrainSettings(boolean enabled, float intensity,
                                float response, int seed) {
    public FilmGrainSettings {
        if (!Float.isFinite(intensity) || intensity < 0.0f || intensity > 0.35f) {
            throw new IllegalArgumentException("grain intensity must be finite and in [0, 0.35]");
        }
        if (!Float.isFinite(response) || response < 0.0f || response > 1.0f) {
            throw new IllegalArgumentException("grain response must be finite and in [0, 1]");
        }
    }

    public static FilmGrainSettings disabled() {
        return new FilmGrainSettings(false, 0.0f, 0.5f, 0);
    }

    public static FilmGrainSettings of(float intensity, float response, int seed) {
        return new FilmGrainSettings(intensity > 0.0f, intensity, response, seed);
    }

    public boolean active() {
        return enabled && intensity > 0.0f;
    }

    public FilmGrainSettings withEnabled(boolean value) {
        return new FilmGrainSettings(value, intensity, response, seed);
    }

    public FilmGrainSettings withIntensity(float value) {
        return new FilmGrainSettings(enabled, value, response, seed);
    }

    public FilmGrainSettings withResponse(float value) {
        return new FilmGrainSettings(enabled, intensity, value, seed);
    }

    public FilmGrainSettings withSeed(int value) {
        return new FilmGrainSettings(enabled, intensity, response, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled;
        private float intensity = 0.06f;
        private float response = 0.5f;
        private int seed;

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

        public Builder response(float value) {
            response = value;
            return this;
        }

        public Builder seed(int value) {
            seed = value;
            return this;
        }

        public FilmGrainSettings build() {
            return new FilmGrainSettings(enabled, intensity, response, seed);
        }
    }
}
