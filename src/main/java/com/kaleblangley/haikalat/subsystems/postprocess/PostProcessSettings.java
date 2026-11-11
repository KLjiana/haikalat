package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.Objects;

/** Optional subsystem-owned effects layered on the runtime's core HDR/AA settings. */
public record PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                                  GtaoSettings gtao, VignetteSettings vignette) {
    /** Source-compatible constructor retained for existing callers. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog) {
        this(colorGrading, fog, GtaoSettings.disabled(), VignetteSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before v0.28.1. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao) {
        this(colorGrading, fog, gtao, VignetteSettings.disabled());
    }

    public PostProcessSettings {
        Objects.requireNonNull(colorGrading, "colorGrading");
        Objects.requireNonNull(fog, "fog");
        Objects.requireNonNull(gtao, "gtao");
        Objects.requireNonNull(vignette, "vignette");
    }

    public static PostProcessSettings defaults() {
        return new PostProcessSettings(ColorGradingSettings.disabled(), FogSettings.disabled(),
                GtaoSettings.disabled(), VignetteSettings.disabled());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ColorGradingSettings colorGrading = ColorGradingSettings.disabled();
        private FogSettings fog = FogSettings.disabled();
        private GtaoSettings gtao = GtaoSettings.disabled();
        private VignetteSettings vignette = VignetteSettings.disabled();

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

        public Builder vignette(VignetteSettings value) {
            vignette = Objects.requireNonNull(value, "vignette");
            return this;
        }

        public PostProcessSettings build() {
            return new PostProcessSettings(colorGrading, fog, gtao, vignette);
        }
    }
}
