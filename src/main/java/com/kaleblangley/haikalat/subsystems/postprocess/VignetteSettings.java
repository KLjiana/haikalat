package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.Objects;

/**
 * Immutable vignette settings for the display-referred post-process chain.
 *
 * <p>The effect is disabled by default.  A zero intensity or disabled instance
 * contributes no pass and is an exact image no-op; intensity is bounded so the
 * author cannot produce a fully black frame by accident.</p>
 */
public record VignetteSettings(boolean enabled, float intensity,
                               float smoothness, float roundness) {
    public VignetteSettings {
        if (!Float.isFinite(intensity) || intensity < 0.0f || intensity > 1.0f) {
            throw new IllegalArgumentException("vignette intensity must be finite and in [0, 1]");
        }
        if (!Float.isFinite(smoothness) || smoothness < 0.0f || smoothness > 1.0f) {
            throw new IllegalArgumentException("vignette smoothness must be finite and in [0, 1]");
        }
        if (!Float.isFinite(roundness) || roundness < 0.0f || roundness > 1.0f) {
            throw new IllegalArgumentException("vignette roundness must be finite and in [0, 1]");
        }
    }

    public static VignetteSettings disabled() {
        return new VignetteSettings(false, 0.0f, 0.5f, 0.5f);
    }

    public static VignetteSettings of(float intensity, float smoothness, float roundness) {
        return new VignetteSettings(intensity > 0.0f, intensity, smoothness, roundness);
    }

    public boolean active() {
        return enabled && intensity > 0.0f;
    }

    public VignetteSettings withEnabled(boolean value) {
        return new VignetteSettings(value, intensity, smoothness, roundness);
    }

    public VignetteSettings withIntensity(float value) {
        return new VignetteSettings(enabled, value, smoothness, roundness);
    }

    public VignetteSettings withSmoothness(float value) {
        return new VignetteSettings(enabled, intensity, value, roundness);
    }

    public VignetteSettings withRoundness(float value) {
        return new VignetteSettings(enabled, intensity, smoothness, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled;
        private float intensity = 0.35f;
        private float smoothness = 0.5f;
        private float roundness = 0.5f;

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

        public Builder smoothness(float value) {
            smoothness = value;
            return this;
        }

        public Builder roundness(float value) {
            roundness = value;
            return this;
        }

        public VignetteSettings build() {
            return new VignetteSettings(enabled, intensity, smoothness, roundness);
        }
    }
}
