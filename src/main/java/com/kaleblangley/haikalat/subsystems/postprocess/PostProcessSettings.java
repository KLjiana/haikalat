package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.Objects;

/** Optional subsystem-owned effects layered on the runtime's core HDR/AA settings. */
public record PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog) {
    public PostProcessSettings {
        Objects.requireNonNull(colorGrading, "colorGrading");
        Objects.requireNonNull(fog, "fog");
    }

    public static PostProcessSettings defaults() {
        return new PostProcessSettings(ColorGradingSettings.disabled(), FogSettings.disabled());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ColorGradingSettings colorGrading = ColorGradingSettings.disabled();
        private FogSettings fog = FogSettings.disabled();

        private Builder() {
        }

        public Builder colorGrading(ColorGradingSettings value) {
            colorGrading = value;
            return this;
        }

        public Builder fog(FogSettings value) {
            fog = value;
            return this;
        }

        public PostProcessSettings build() {
            return new PostProcessSettings(colorGrading, fog);
        }
    }
}
