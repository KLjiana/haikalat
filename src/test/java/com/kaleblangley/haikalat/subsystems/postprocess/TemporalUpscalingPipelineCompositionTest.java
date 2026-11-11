package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
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
        assertDoesNotThrow(() -> PostProcessPipeline.validateTemporalUpscalingComposition(
                hdr(AntiAliasingMode.NONE), ACTIVE, 64, 64, false, SOURCE));
        assertDoesNotThrow(() -> PostProcessPipeline.validateTemporalUpscalingComposition(
                hdr(AntiAliasingMode.FXAA), ACTIVE, 64, 64, false, SOURCE));
        assertDoesNotThrow(() -> PostProcessPipeline.validateTemporalUpscalingComposition(
                hdr(AntiAliasingMode.SMAA), ACTIVE, 64, 64, false, SOURCE));
    }

    @Test
    void disabledUpscalingKeepsTheBaselineAndRejectsAStraySource() {
        assertDoesNotThrow(() -> PostProcessPipeline.validateTemporalUpscalingComposition(
                hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 64, true,
                null));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 64,
                        true, SOURCE));
    }

    @Test
    void activeUpscalingRequiresTheSourceAndAnHdrChain() {
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.NONE), ACTIVE, 64, 64, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                                .toneMappingMode(ToneMappingMode.NONE).vsync(false).build(),
                        ACTIVE, 64, 64, false, SOURCE));
    }

    @Test
    void fullResolutionTemporalResolvesAndMsaaAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.TAA), ACTIVE, 64, 64, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.MSAA), ACTIVE, 64, 64, false, SOURCE));
    }

    @Test
    void fullResolutionSurfaceConsumersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.NONE), ACTIVE, 64, 64, true, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.NONE), withFog(), 64, 64, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.NONE), withDepthOfField(), 64, 64, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.NONE), withMotionBlur(), 64, 64, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.NONE), withSsr(), 64, 64, false, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.NONE), withTemporalDenoise(), 64, 64, false,
                        SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.NONE), withScreenDistortion(), 64, 64, false,
                        SOURCE));
    }

    @Test
    void sourceExtentMustFitInsideTheOutputExtent() {
        TemporalUpscalingSource tooLarge = new TemporalUpscalingSource(1, 2, 80, 80);
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalUpscalingComposition(
                        hdr(AntiAliasingMode.NONE), ACTIVE, 64, 64, false, tooLarge));
    }

    private static RenderSettings hdr(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
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
