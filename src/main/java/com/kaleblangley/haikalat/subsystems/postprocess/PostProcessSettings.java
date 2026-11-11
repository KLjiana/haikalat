package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.Objects;

/** Optional subsystem-owned effects layered on the runtime's core HDR/AA settings. */
public record PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                                  GtaoSettings gtao, VignetteSettings vignette,
                                  FilmGrainSettings filmGrain,
                                  ChromaticAberrationSettings chromaticAberration) {
    /** Source-compatible constructor retained for existing callers. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog) {
        this(colorGrading, fog, GtaoSettings.disabled(), VignetteSettings.disabled(),
                FilmGrainSettings.disabled(), ChromaticAberrationSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before v0.28.1. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao) {
        this(colorGrading, fog, gtao, VignetteSettings.disabled(),
                FilmGrainSettings.disabled(), ChromaticAberrationSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before grain/aberration. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao, VignetteSettings vignette) {
        this(colorGrading, fog, gtao, vignette, FilmGrainSettings.disabled(),
                ChromaticAberrationSettings.disabled());
    }

    public PostProcessSettings {
        Objects.requireNonNull(colorGrading, "colorGrading");
        Objects.requireNonNull(fog, "fog");
        Objects.requireNonNull(gtao, "gtao");
        Objects.requireNonNull(vignette, "vignette");
        Objects.requireNonNull(filmGrain, "filmGrain");
        Objects.requireNonNull(chromaticAberration, "chromaticAberration");
    }

    public static PostProcessSettings defaults() {
        return new PostProcessSettings(ColorGradingSettings.disabled(), FogSettings.disabled(),
                GtaoSettings.disabled(), VignetteSettings.disabled(),
                FilmGrainSettings.disabled(), ChromaticAberrationSettings.disabled());
    }

    /** Returns a copy with GTAO replaced and every other effect preserved. */
    public PostProcessSettings withGtao(GtaoSettings value) {
        return new PostProcessSettings(colorGrading, fog, Objects.requireNonNull(value, "gtao"),
                vignette, filmGrain, chromaticAberration);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ColorGradingSettings colorGrading = ColorGradingSettings.disabled();
        private FogSettings fog = FogSettings.disabled();
        private GtaoSettings gtao = GtaoSettings.disabled();
        private VignetteSettings vignette = VignetteSettings.disabled();
        private FilmGrainSettings filmGrain = FilmGrainSettings.disabled();
        private ChromaticAberrationSettings chromaticAberration =
                ChromaticAberrationSettings.disabled();

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

        public Builder filmGrain(FilmGrainSettings value) {
            filmGrain = Objects.requireNonNull(value, "filmGrain");
            return this;
        }

        public Builder chromaticAberration(ChromaticAberrationSettings value) {
            chromaticAberration = Objects.requireNonNull(value, "chromaticAberration");
            return this;
        }

        public PostProcessSettings build() {
            return new PostProcessSettings(colorGrading, fog, gtao, vignette, filmGrain,
                    chromaticAberration);
        }
    }
}
