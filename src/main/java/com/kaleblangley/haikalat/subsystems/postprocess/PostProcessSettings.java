package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.Objects;

/** Optional subsystem-owned effects layered on the runtime's core HDR/AA settings. */
public record PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                                  GtaoSettings gtao) {
    /** Source-compatible constructor retained for existing callers. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog) {
        this(colorGrading, fog, GtaoSettings.disabled());
    }

    public PostProcessSettings {
        Objects.requireNonNull(colorGrading, "colorGrading");
        Objects.requireNonNull(fog, "fog");
        Objects.requireNonNull(gtao, "gtao");
    }

    public static PostProcessSettings defaults() {
        return new PostProcessSettings(ColorGradingSettings.disabled(), FogSettings.disabled(),
                GtaoSettings.disabled());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ColorGradingSettings colorGrading = ColorGradingSettings.disabled();
        private FogSettings fog = FogSettings.disabled();
        private GtaoSettings gtao = GtaoSettings.disabled();

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

        public Builder gtao(GtaoSettings value) {
            gtao = Objects.requireNonNull(value, "gtao");
            return this;
        }

        public PostProcessSettings build() {
            return new PostProcessSettings(colorGrading, fog, gtao);
        }
    }
}
