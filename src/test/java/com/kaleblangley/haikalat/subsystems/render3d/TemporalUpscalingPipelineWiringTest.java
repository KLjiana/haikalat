package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSource;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production wiring contract for #35 temporal upscaling in the main pipeline.
 *
 * <p>The unified feature control reports a missing low-resolution source as an
 * unavailable feature and strips it from the effective snapshot; the direct
 * compatibility path fails before any generation is prepared.  Source
 * staging/clear semantics are frame-boundary operations.  The real-GL companion
 * {@code TemporalUpscalingPipelineGlTest} proves the imported source reaches the
 * upscale pass and matches the standalone pass reference.</p>
 */
class TemporalUpscalingPipelineWiringTest {
    private static final RenderFeaturePolicy.Facts NO_PBR_NO_SOURCE =
            new RenderFeaturePolicy.Facts(false, true, true, true, false, false, false,
                    false, false, true);

    @Test
    void missingSourceIsUnavailableAndStrippedFromTheEffectiveSnapshot() {
        RenderFeatureSettings request = hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .temporalUpscaling(TemporalUpscalingSettings.defaults())
                        .build())
                .build();
        RenderFeaturePolicy.Resolved resolved =
                RenderFeaturePolicy.resolve(request, NO_PBR_NO_SOURCE);

        assertFalse(resolved.temporalUpscaling());
        RenderFeatureActivity activity = activity(resolved,
                RenderFeature.TEMPORAL_UPSCALING);
        assertEquals(RenderFeatureReason.UNAVAILABLE, activity.reason());
        assertTrue(activity.detail().contains("TemporalUpscalingSource"),
                activity.detail());
        assertFalse(resolved.effectivePostProcess().temporalUpscaling().active(),
                "an unavailable request must not reach the candidate generation");
        assertTrue(request.postProcess().temporalUpscaling().active(),
                "the requested snapshot keeps the caller intent");
    }

    @Test
    void suppliedSourceResolvesTheFeatureActive() {
        RenderFeaturePolicy.Facts withSource = new RenderFeaturePolicy.Facts(
                false, true, true, true, false, false, false, false, false, true,
                false, false, false, false, true);
        RenderFeatureSettings request = hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .temporalUpscaling(TemporalUpscalingSettings.defaults())
                        .build())
                .build();

        RenderFeaturePolicy.Resolved resolved =
                RenderFeaturePolicy.resolve(request, withSource);
        assertTrue(resolved.temporalUpscaling());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(resolved, RenderFeature.TEMPORAL_UPSCALING).reason());
        assertTrue(resolved.effectivePostProcess().temporalUpscaling().active());
    }

    @Test
    void directUpscalingWithoutSourceFailsBeforeCandidatePreparation() {
        RenderPipeline pipeline = new RenderPipeline(window(), new Scene(new Camera()), null,
                hdr(AntiAliasingMode.NONE))
                .postProcessSettings(PostProcessSettings.builder()
                        .temporalUpscaling(TemporalUpscalingSettings.defaults())
                        .build());
        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    pipeline::build);
            assertTrue(failure.getMessage().contains("low-resolution colour/depth source"),
                    failure.getMessage());
            assertNull(pipeline.graph(),
                    "the dependency check must run before the candidate graph is built");
        } finally {
            pipeline.close();
        }
    }

    @Test
    void sourceStagingClearAndApplySemanticsAreFrameBoundaryOperations() {
        TemporalUpscalingSource source = new TemporalUpscalingSource(11, 12, 32, 32);
        RenderPipeline pipeline = new RenderPipeline(window(), new Scene(new Camera()), null,
                hdr(AntiAliasingMode.NONE));
        try {
            pipeline.temporalUpscalingSource(source);
            assertSame(source, pipeline.temporalUpscalingSource());

            pipeline.applyTemporalUpscalingSource(source);
            assertSame(source, pipeline.temporalUpscalingSource(),
                    "applying the same source must be an idempotent frame-boundary no-op");

            pipeline.clearTemporalUpscalingSource();
            assertNull(pipeline.temporalUpscalingSource(),
                    "clearing while the feature is inactive must drop the staged source");

            pipeline.postProcessSettings(PostProcessSettings.builder()
                    .temporalUpscaling(TemporalUpscalingSettings.defaults())
                    .build());
            pipeline.temporalUpscalingSource(source);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    pipeline::clearTemporalUpscalingSource);
            assertTrue(failure.getMessage().contains("disable temporal upscaling"),
                    failure.getMessage());
            assertSame(source, pipeline.temporalUpscalingSource());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void sourceStagingBeforeEnableIsAllowedButAStraySourceIsNotImported() {
        TemporalUpscalingSource source = new TemporalUpscalingSource(21, 22, 32, 32);
        RenderPipeline pipeline = new RenderPipeline(window(), new Scene(new Camera()), null,
                hdr(AntiAliasingMode.NONE));
        try {
            pipeline.applyTemporalUpscalingSource(source);
            assertSame(source, pipeline.temporalUpscalingSource(),
                    "a source may be staged while the feature is inactive");
            pipeline.postProcessSettings(PostProcessSettings.builder()
                    .temporalUpscaling(TemporalUpscalingSettings.defaults())
                    .build());
            assertSame(source, pipeline.temporalUpscalingSource());
        } finally {
            pipeline.close();
        }
    }

    private static RenderSettings hdr(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static RenderFeatureSettings.Builder hdrBuilder() {
        return RenderFeatureSettings.builder().render(hdr(AntiAliasingMode.NONE));
    }

    private static RenderFeatureActivity activity(RenderFeaturePolicy.Resolved resolved,
                                                  RenderFeature feature) {
        return resolved.activities().stream()
                .filter(activity -> activity.feature() == feature)
                .findFirst().orElseThrow();
    }

    private static RenderWindow window() {
        return new RenderWindow() {
            @Override
            public int width() {
                return 64;
            }

            @Override
            public int height() {
                return 48;
            }
        };
    }
}
