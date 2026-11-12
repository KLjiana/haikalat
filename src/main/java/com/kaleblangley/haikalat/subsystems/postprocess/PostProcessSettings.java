package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.vrs.VariableRateShadingSettings;

import java.util.Objects;

/** Optional subsystem-owned effects layered on the runtime's core HDR/AA settings. */
public record PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                                  GtaoSettings gtao, VignetteSettings vignette,
                                  FilmGrainSettings filmGrain,
                                  ChromaticAberrationSettings chromaticAberration,
                                  ScreenDistortionSettings screenDistortion,
                                  DepthOfFieldSettings depthOfField,
                                  MotionBlurSettings motionBlur,
                                  SsrSettings ssr,
                                  TemporalDenoiseSettings temporalDenoise,
                                  TemporalUpscalingSettings temporalUpscaling,
                                  SubsurfaceScatteringSettings subsurfaceScattering,
                                  VariableRateShadingSettings variableRateShading) {
    /** Source-compatible constructor retained for existing callers. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog) {
        this(colorGrading, fog, GtaoSettings.disabled(), VignetteSettings.disabled(),
                FilmGrainSettings.disabled(), ChromaticAberrationSettings.disabled(),
                ScreenDistortionSettings.disabled(), DepthOfFieldSettings.disabled(),
                MotionBlurSettings.disabled(), SsrSettings.disabled(),
                TemporalDenoiseSettings.disabled(), TemporalUpscalingSettings.disabled(),
                SubsurfaceScatteringSettings.disabled(), VariableRateShadingSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before v0.28.1. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao) {
        this(colorGrading, fog, gtao, VignetteSettings.disabled(),
                FilmGrainSettings.disabled(), ChromaticAberrationSettings.disabled(),
                ScreenDistortionSettings.disabled(), DepthOfFieldSettings.disabled(),
                MotionBlurSettings.disabled(), SsrSettings.disabled(),
                TemporalDenoiseSettings.disabled(), TemporalUpscalingSettings.disabled(),
                SubsurfaceScatteringSettings.disabled(), VariableRateShadingSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before grain/aberration. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao, VignetteSettings vignette) {
        this(colorGrading, fog, gtao, vignette, FilmGrainSettings.disabled(),
                ChromaticAberrationSettings.disabled(), ScreenDistortionSettings.disabled(),
                DepthOfFieldSettings.disabled(), MotionBlurSettings.disabled(),
                SsrSettings.disabled(), TemporalDenoiseSettings.disabled(),
                TemporalUpscalingSettings.disabled(), SubsurfaceScatteringSettings.disabled(),
                VariableRateShadingSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before screen distortion. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao, VignetteSettings vignette,
                               FilmGrainSettings filmGrain,
                               ChromaticAberrationSettings chromaticAberration) {
        this(colorGrading, fog, gtao, vignette, filmGrain, chromaticAberration,
                ScreenDistortionSettings.disabled(), DepthOfFieldSettings.disabled(),
                MotionBlurSettings.disabled(), SsrSettings.disabled(),
                TemporalDenoiseSettings.disabled(), TemporalUpscalingSettings.disabled(),
                SubsurfaceScatteringSettings.disabled(), VariableRateShadingSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before depth of field. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao, VignetteSettings vignette,
                               FilmGrainSettings filmGrain,
                               ChromaticAberrationSettings chromaticAberration,
                               ScreenDistortionSettings screenDistortion) {
        this(colorGrading, fog, gtao, vignette, filmGrain, chromaticAberration,
                screenDistortion, DepthOfFieldSettings.disabled(), MotionBlurSettings.disabled(),
                SsrSettings.disabled(), TemporalDenoiseSettings.disabled(),
                TemporalUpscalingSettings.disabled(), SubsurfaceScatteringSettings.disabled(),
                VariableRateShadingSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before motion blur. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao, VignetteSettings vignette,
                               FilmGrainSettings filmGrain,
                               ChromaticAberrationSettings chromaticAberration,
                               ScreenDistortionSettings screenDistortion,
                               DepthOfFieldSettings depthOfField) {
        this(colorGrading, fog, gtao, vignette, filmGrain, chromaticAberration,
                screenDistortion, depthOfField, MotionBlurSettings.disabled(),
                SsrSettings.disabled(), TemporalDenoiseSettings.disabled(),
                TemporalUpscalingSettings.disabled(), SubsurfaceScatteringSettings.disabled(),
                VariableRateShadingSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before screen-space reflections. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao, VignetteSettings vignette,
                               FilmGrainSettings filmGrain,
                               ChromaticAberrationSettings chromaticAberration,
                               ScreenDistortionSettings screenDistortion,
                               DepthOfFieldSettings depthOfField,
                               MotionBlurSettings motionBlur) {
        this(colorGrading, fog, gtao, vignette, filmGrain, chromaticAberration,
                screenDistortion, depthOfField, motionBlur, SsrSettings.disabled(),
                TemporalDenoiseSettings.disabled(), TemporalUpscalingSettings.disabled(),
                SubsurfaceScatteringSettings.disabled(), VariableRateShadingSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before temporal denoise. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao, VignetteSettings vignette,
                               FilmGrainSettings filmGrain,
                               ChromaticAberrationSettings chromaticAberration,
                               ScreenDistortionSettings screenDistortion,
                               DepthOfFieldSettings depthOfField,
                               MotionBlurSettings motionBlur,
                               SsrSettings ssr) {
        this(colorGrading, fog, gtao, vignette, filmGrain, chromaticAberration,
                screenDistortion, depthOfField, motionBlur, ssr,
                TemporalDenoiseSettings.disabled(), TemporalUpscalingSettings.disabled(),
                SubsurfaceScatteringSettings.disabled(), VariableRateShadingSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before temporal upscaling. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao, VignetteSettings vignette,
                               FilmGrainSettings filmGrain,
                               ChromaticAberrationSettings chromaticAberration,
                               ScreenDistortionSettings screenDistortion,
                               DepthOfFieldSettings depthOfField,
                               MotionBlurSettings motionBlur,
                               SsrSettings ssr,
                               TemporalDenoiseSettings temporalDenoise) {
        this(colorGrading, fog, gtao, vignette, filmGrain, chromaticAberration,
                screenDistortion, depthOfField, motionBlur, ssr, temporalDenoise,
                TemporalUpscalingSettings.disabled(), SubsurfaceScatteringSettings.disabled(), VariableRateShadingSettings.disabled());
    }

    /** Source-compatible constructor retained for callers written before subsurface scattering. */
    public PostProcessSettings(ColorGradingSettings colorGrading, FogSettings fog,
                               GtaoSettings gtao, VignetteSettings vignette,
                               FilmGrainSettings filmGrain,
                               ChromaticAberrationSettings chromaticAberration,
                               ScreenDistortionSettings screenDistortion,
                               DepthOfFieldSettings depthOfField,
                               MotionBlurSettings motionBlur,
                               SsrSettings ssr,
                               TemporalDenoiseSettings temporalDenoise,
                               TemporalUpscalingSettings temporalUpscaling) {
        this(colorGrading, fog, gtao, vignette, filmGrain, chromaticAberration,
                screenDistortion, depthOfField, motionBlur, ssr, temporalDenoise,
                temporalUpscaling, SubsurfaceScatteringSettings.disabled(), VariableRateShadingSettings.disabled());
    }

    public PostProcessSettings {
        Objects.requireNonNull(colorGrading, "colorGrading");
        Objects.requireNonNull(fog, "fog");
        Objects.requireNonNull(gtao, "gtao");
        Objects.requireNonNull(vignette, "vignette");
        Objects.requireNonNull(filmGrain, "filmGrain");
        Objects.requireNonNull(chromaticAberration, "chromaticAberration");
        Objects.requireNonNull(screenDistortion, "screenDistortion");
        Objects.requireNonNull(depthOfField, "depthOfField");
        Objects.requireNonNull(motionBlur, "motionBlur");
        Objects.requireNonNull(ssr, "ssr");
        Objects.requireNonNull(temporalDenoise, "temporalDenoise");
        Objects.requireNonNull(temporalUpscaling, "temporalUpscaling");
        Objects.requireNonNull(subsurfaceScattering, "subsurfaceScattering");
        Objects.requireNonNull(variableRateShading, "variableRateShading");
    }

    public static PostProcessSettings defaults() {
        return new PostProcessSettings(ColorGradingSettings.disabled(), FogSettings.disabled(),
                GtaoSettings.disabled(), VignetteSettings.disabled(),
                FilmGrainSettings.disabled(), ChromaticAberrationSettings.disabled(),
                ScreenDistortionSettings.disabled(), DepthOfFieldSettings.disabled(),
                MotionBlurSettings.disabled(), SsrSettings.disabled(),
                TemporalDenoiseSettings.disabled(), TemporalUpscalingSettings.disabled(),
                SubsurfaceScatteringSettings.disabled(), VariableRateShadingSettings.disabled());
    }

    /** Returns a copy with GTAO replaced and every other effect preserved. */
    public PostProcessSettings withGtao(GtaoSettings value) {
        return new PostProcessSettings(colorGrading, fog, Objects.requireNonNull(value, "gtao"),
                vignette, filmGrain, chromaticAberration, screenDistortion, depthOfField,
                motionBlur, ssr, temporalDenoise, temporalUpscaling,
                subsurfaceScattering, variableRateShading);
    }

    /** Returns a copy with depth of field replaced and every other effect preserved. */
    public PostProcessSettings withDepthOfField(DepthOfFieldSettings value) {
        return new PostProcessSettings(colorGrading, fog, gtao, vignette, filmGrain,
                chromaticAberration, screenDistortion,
                Objects.requireNonNull(value, "depthOfField"), motionBlur, ssr,
                temporalDenoise, temporalUpscaling, subsurfaceScattering, variableRateShading);
    }

    /** Returns a copy with motion blur replaced and every other effect preserved. */
    public PostProcessSettings withMotionBlur(MotionBlurSettings value) {
        return new PostProcessSettings(colorGrading, fog, gtao, vignette, filmGrain,
                chromaticAberration, screenDistortion, depthOfField,
                Objects.requireNonNull(value, "motionBlur"), ssr, temporalDenoise,
                temporalUpscaling, subsurfaceScattering, variableRateShading);
    }

    /** Returns a copy with screen-space reflections replaced and every other effect preserved. */
    public PostProcessSettings withSsr(SsrSettings value) {
        return new PostProcessSettings(colorGrading, fog, gtao, vignette, filmGrain,
                chromaticAberration, screenDistortion, depthOfField, motionBlur,
                Objects.requireNonNull(value, "ssr"), temporalDenoise, temporalUpscaling,
                subsurfaceScattering, variableRateShading);
    }

    /** Returns a copy with temporal denoising replaced and every other effect preserved. */
    public PostProcessSettings withTemporalDenoise(TemporalDenoiseSettings value) {
        return new PostProcessSettings(colorGrading, fog, gtao, vignette, filmGrain,
                chromaticAberration, screenDistortion, depthOfField, motionBlur, ssr,
                Objects.requireNonNull(value, "temporalDenoise"), temporalUpscaling,
                subsurfaceScattering, variableRateShading);
    }

    /** Returns a copy with temporal upscaling replaced and every other effect preserved. */
    public PostProcessSettings withTemporalUpscaling(TemporalUpscalingSettings value) {
        return new PostProcessSettings(colorGrading, fog, gtao, vignette, filmGrain,
                chromaticAberration, screenDistortion, depthOfField, motionBlur, ssr,
                temporalDenoise, Objects.requireNonNull(value, "temporalUpscaling"),
                subsurfaceScattering, variableRateShading);
    }

    /**
     * Returns a copy with the screen-space subsurface-scattering request
     * replaced and every other effect preserved. Only the activation, radius
     * cap and maximum gather distance are global; per-pixel strength, radius
     * and thickness come from the shared {@code SUBSURFACE} surface mask.
     */
    public PostProcessSettings withSubsurfaceScattering(SubsurfaceScatteringSettings value) {
        return new PostProcessSettings(colorGrading, fog, gtao, vignette, filmGrain,
                chromaticAberration, screenDistortion, depthOfField, motionBlur, ssr,
                temporalDenoise, temporalUpscaling,
                Objects.requireNonNull(value, "subsurfaceScattering"), variableRateShading);
    }

    /**
     * Returns a copy with the selective shading-rate request replaced and every
     * other effect preserved. The request only becomes active when the declared
     * subsurface consumer is active and the runtime capability supports it.
     */
    public PostProcessSettings withVariableRateShading(VariableRateShadingSettings value) {
        return new PostProcessSettings(colorGrading, fog, gtao, vignette, filmGrain,
                chromaticAberration, screenDistortion, depthOfField, motionBlur, ssr,
                temporalDenoise, temporalUpscaling, subsurfaceScattering,
                Objects.requireNonNull(value, "variableRateShading"));
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
        private ScreenDistortionSettings screenDistortion = ScreenDistortionSettings.disabled();
        private DepthOfFieldSettings depthOfField = DepthOfFieldSettings.disabled();
        private MotionBlurSettings motionBlur = MotionBlurSettings.disabled();
        private SsrSettings ssr = SsrSettings.disabled();
        private TemporalDenoiseSettings temporalDenoise = TemporalDenoiseSettings.disabled();
        private TemporalUpscalingSettings temporalUpscaling = TemporalUpscalingSettings.disabled();
        private SubsurfaceScatteringSettings subsurfaceScattering =
                SubsurfaceScatteringSettings.disabled();
        private VariableRateShadingSettings variableRateShading =
                VariableRateShadingSettings.disabled();

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

        public Builder screenDistortion(ScreenDistortionSettings value) {
            screenDistortion = Objects.requireNonNull(value, "screenDistortion");
            return this;
        }

        public Builder depthOfField(DepthOfFieldSettings value) {
            depthOfField = Objects.requireNonNull(value, "depthOfField");
            return this;
        }

        public Builder motionBlur(MotionBlurSettings value) {
            motionBlur = Objects.requireNonNull(value, "motionBlur");
            return this;
        }

        public Builder ssr(SsrSettings value) {
            ssr = Objects.requireNonNull(value, "ssr");
            return this;
        }

        public Builder temporalDenoise(TemporalDenoiseSettings value) {
            temporalDenoise = Objects.requireNonNull(value, "temporalDenoise");
            return this;
        }

        public Builder temporalUpscaling(TemporalUpscalingSettings value) {
            temporalUpscaling = Objects.requireNonNull(value, "temporalUpscaling");
            return this;
        }

        public Builder subsurfaceScattering(SubsurfaceScatteringSettings value) {
            subsurfaceScattering = Objects.requireNonNull(value, "subsurfaceScattering");
            return this;
        }

        public Builder variableRateShading(VariableRateShadingSettings value) {
            variableRateShading = Objects.requireNonNull(value, "variableRateShading");
            return this;
        }

        public PostProcessSettings build() {
            return new PostProcessSettings(colorGrading, fog, gtao, vignette, filmGrain,
                    chromaticAberration, screenDistortion, depthOfField, motionBlur, ssr,
                    temporalDenoise, temporalUpscaling, subsurfaceScattering, variableRateShading);
        }
    }
}
