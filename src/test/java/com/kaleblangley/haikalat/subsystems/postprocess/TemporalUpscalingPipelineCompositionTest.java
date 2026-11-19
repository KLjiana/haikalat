package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure composition contract for temporal upscaling: an active request replaces
 * the scene colour input, so full-resolution surface consumers and a second
 * temporal resolve must be rejected at assembly time instead of being silently
 * ignored.  The same validation runs inside {@code PostProcessPipeline.assemble}
 * before any GPU owner is created, which keeps this JVM test meaningful.
 */
class TemporalUpscalingPipelineCompositionTest {
    private static final TemporalUpscalingSource SOURCE =
            new TemporalUpscalingSource(1, 2, 32, 32);
    private static final PostProcessSettings ACTIVE = PostProcessSettings.builder()
            .temporalUpscaling(TemporalUpscalingSettings.defaults())
            .build();

    @Test
    void activeUpscalingAcceptsTheReducedSourceOnTheHdrChain() {
        assertAccepted(hdr(AntiAliasingMode.NONE), ACTIVE);
        assertAccepted(hdr(AntiAliasingMode.FXAA), ACTIVE);
        assertAccepted(hdr(AntiAliasingMode.SMAA), ACTIVE);
    }

    @Test
    void disabledUpscalingKeepsTheBaselineAndRejectsAStraySource() {
        assertDoesNotThrow(() -> PostProcessPipeline.validateTemporalComposition(
                hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 64, true,
                false, null));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 64,
                        true, false, SOURCE));
    }

    @Test
    void activeUpscalingRequiresTheSourceAndAnHdrChain() {
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), ACTIVE, 64, 64, false, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                                .toneMappingMode(ToneMappingMode.NONE).vsync(false).build(),
                        ACTIVE, 64, 64, false, false, SOURCE));
    }

    @Test
    void fullResolutionTemporalResolvesAndMsaaAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.TAA), ACTIVE, 64, 64, false, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.MSAA), ACTIVE, 64, 64, false, false, SOURCE));
    }

    @Test
    void fullResolutionSurfaceConsumersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), ACTIVE, 64, 64, true, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), withFog(), 64, 64, false, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), withDepthOfField(), 64, 64, false, false,
                        SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), withMotionBlur(), 64, 64, false, false,
                        SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), withSsr(), 64, 64, false, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), withTemporalDenoise(), 64, 64, false, false,
                        SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), withSubsurfaceScattering(), 64, 64, false,
                        false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), withScreenDistortion(), 64, 64, false, false,
                        SOURCE));
    }

    @Test
    void externalTargetConsumersAndLateReactiveProducerAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdrWithBloom(), ACTIVE, 64, 64, false, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdrWithAutoExposure(), ACTIVE, 64, 64, false, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), ACTIVE, 64, 64, false, true, SOURCE));
    }

    @Test
    void sourceExtentMustFitInsideTheOutputExtent() {
        TemporalUpscalingSource tooLarge = new TemporalUpscalingSource(1, 2, 80, 80);
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(AntiAliasingMode.NONE), ACTIVE, 64, 64, false, false, tooLarge));
    }

    @Test
    void reducedScaleWithoutCallerSourceUsesTheInternalContract() {
        RenderSettings reduced = hdr(AntiAliasingMode.NONE).withRenderScale(0.5f);
        assertDoesNotThrow(() -> PostProcessPipeline.validateTemporalComposition(
                reduced, PostProcessSettings.defaults(), 64, 64, false, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                                .vsync(false).renderScale(0.5f).build(),
                        PostProcessSettings.defaults(), 64, 64, false, false, null),
                "the internal source needs the linear RGBA16F HDR raster");
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        reduced, ACTIVE, 64, 64, false, false, SOURCE),
                "a caller source must not compose with a reduced scene extent");
    }

    @Test
    void internalReducedSourceRejectsFullResolutionConsumers() {
        RenderSettings reduced = hdr(AntiAliasingMode.NONE).withRenderScale(0.5f);
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        reduced.withVisualSettings(1.0f,
                                BloomSettings.builder().enabled(true).build(),
                                AntiAliasingMode.NONE),
                        PostProcessSettings.defaults(), 64, 64, false, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        reduced, PostProcessSettings.builder()
                                .depthOfField(DepthOfFieldSettings.of(0.2f, 1.0f, 4.0f)).build(),
                        64, 64, false, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        reduced, PostProcessSettings.builder()
                                .temporalUpscaling(TemporalUpscalingSettings.defaults()).build(),
                        64, 64, false, false, null),
                "an active caller request still needs its caller source");
    }

    private static void assertAccepted(RenderSettings settings, PostProcessSettings effects) {
        assertDoesNotThrow(() -> PostProcessPipeline.validateTemporalComposition(
                settings, effects, 64, 64, false, false, SOURCE));
    }

    private static RenderSettings hdr(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static RenderSettings hdrWithBloom() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .bloomSettings(BloomSettings.builder().enabled(true).build())
                .build();
    }

    private static RenderSettings hdrWithAutoExposure() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .exposureMode(ExposureMode.AUTO)
                .build();
    }

    private static PostProcessSettings withFog() {
        return PostProcessSettings.builder()
                .temporalUpscaling(TemporalUpscalingSettings.defaults())
                .fog(FogSettings.builder().enabled(true).build())
                .build();
    }

    private static PostProcessSettings withDepthOfField() {
        return PostProcessSettings.builder()
                .temporalUpscaling(TemporalUpscalingSettings.defaults())
                .depthOfField(DepthOfFieldSettings.of(0.2f, 1.0f, 4.0f))
                .build();
    }

    private static PostProcessSettings withMotionBlur() {
        return PostProcessSettings.builder()
                .temporalUpscaling(TemporalUpscalingSettings.defaults())
                .motionBlur(MotionBlurSettings.of(1.0f, 8.0f))
                .build();
    }

    private static PostProcessSettings withSsr() {
        return PostProcessSettings.builder()
                .temporalUpscaling(TemporalUpscalingSettings.defaults())
                .ssr(SsrSettings.defaults())
                .build();
    }

    private static PostProcessSettings withSubsurfaceScattering() {
        return PostProcessSettings.builder()
                .temporalUpscaling(TemporalUpscalingSettings.defaults())
                .subsurfaceScattering(SubsurfaceScatteringSettings.builder()
                        .profile(SubsurfaceScatteringSettings.Profile.UNIFORM)
                        .scatterColor(1.0f, 0.2f, 0.1f)
                        .intensity(2.0f)
                        .profileScaleMetres(0.5f)
                        .thicknessSource(SubsurfaceScatteringSettings.ThicknessSource.CONSTANT)
                        .thicknessMetres(0.1f)
                        .diffusionRadiusMetres(0.5f)
                        .screenSpaceEnabled(true)
                        .maxDistanceMetres(5.0f)
                        .build())
                .build();
    }

    private static PostProcessSettings withTemporalDenoise() {
        return PostProcessSettings.builder()
                .temporalUpscaling(TemporalUpscalingSettings.defaults())
                .temporalDenoise(TemporalDenoiseSettings.defaults())
                .build();
    }

    private static PostProcessSettings withScreenDistortion() {
        return PostProcessSettings.builder()
                .temporalUpscaling(TemporalUpscalingSettings.defaults())
                .screenDistortion(ScreenDistortionSettings.of(java.util.List::of))
                .build();
    }
}
