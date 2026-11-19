package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for {@link TemporalUpscalingSettings}: construction-time
 * rejection, the exact disabled no-op value, the single input-extent rounding
 * rule and the rejection formulas mirrored by the shader.
 */
class TemporalUpscalingSettingsTest {
    @Test
    void disabledIsAnExactNoOpAndDefaultsOnlyEnableIt() {
        TemporalUpscalingSettings disabled = TemporalUpscalingSettings.disabled();
        assertFalse(disabled.active());
        assertEquals(0.5f, disabled.renderScale());
        assertEquals(0.9f, disabled.historyBlend());
        assertEquals(0.25f, disabled.sharpness());
        assertEquals(0.5f, disabled.reactiveThreshold());
        assertEquals(0.1f, disabled.exposureThreshold());
        assertEquals(64.0f, disabled.maxReprojectionPixels());
        assertEquals(TemporalUpscalingSettings.Quality.MEDIUM, disabled.quality());

        TemporalUpscalingSettings enabled = TemporalUpscalingSettings.defaults();
        assertTrue(enabled.active());
        assertEquals(disabled.withEnabled(true), enabled);

        assertFalse(PostProcessSettings.defaults().temporalUpscaling().active());
        assertEquals(TemporalUpscalingSettings.disabled(),
                PostProcessSettings.defaults().temporalUpscaling());
        assertEquals(TemporalUpscalingSettings.disabled(),
                new PostProcessSettings(ColorGradingSettings.disabled(), FogSettings.disabled())
                        .temporalUpscaling());
        assertEquals(TemporalUpscalingSettings.disabled(),
                PostProcessSettings.builder().build().temporalUpscaling());
    }

    @Test
    void constructionRejectsOutOfRangeAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class,
                () -> settings(-0.1f, 0.9f, 0.25f, 0.5f, 0.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.0f, 0.9f, 0.25f, 0.5f, 0.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(1.01f, 0.9f, 0.25f, 0.5f, 0.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(Float.NaN, 0.9f, 0.25f, 0.5f, 0.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(Float.POSITIVE_INFINITY, 0.9f, 0.25f, 0.5f, 0.1f, 64.0f));

        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, -0.01f, 0.25f, 0.5f, 0.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 1.0f, 0.25f, 0.5f, 0.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, Float.NaN, 0.25f, 0.5f, 0.1f, 64.0f));

        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, -0.01f, 0.5f, 0.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, 1.01f, 0.5f, 0.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, Float.NaN, 0.5f, 0.1f, 64.0f));

        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, 0.25f, -0.1f, 0.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, 0.25f, Float.NaN, 0.1f, 64.0f));

        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, 0.25f, 0.5f, -0.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, 0.25f, 0.5f, 1.1f, 64.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, 0.25f, 0.5f, Float.NaN, 64.0f));

        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, 0.25f, 0.5f, 0.1f, -1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, 0.25f, 0.5f, 0.1f, 4097.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.5f, 0.9f, 0.25f, 0.5f, 0.1f, Float.NaN));

        assertThrows(NullPointerException.class, () -> new TemporalUpscalingSettings(
                false, 0.5f, 0.9f, 0.25f, 0.5f, 0.1f, 64.0f, null));
    }

    @Test
    void internalLowResolutionKeepsTheConservativeDefaultsExceptSharpening() {
        TemporalUpscalingSettings internal = TemporalUpscalingSettings.internalLowResolution(0.5f);
        TemporalUpscalingSettings defaults = TemporalUpscalingSettings.defaults();
        assertTrue(internal.active());
        assertEquals(0.5f, internal.renderScale());
        assertEquals(0.0f, internal.sharpness(),
                "the internal reconstruction must not add a sharpen pass");
        assertEquals(defaults.historyBlend(), internal.historyBlend());
        assertEquals(defaults.reactiveThreshold(), internal.reactiveThreshold());
        assertEquals(defaults.exposureThreshold(), internal.exposureThreshold());
        assertEquals(defaults.maxReprojectionPixels(), internal.maxReprojectionPixels());
        assertEquals(defaults.quality(), internal.quality());
        assertEquals(76, internal.inputWidth(152));
        assertEquals(36, internal.inputHeight(72));
        assertEquals(internal.inputWidth(152),
                TemporalUpscalingSettings.scaledExtent(152, 0.5f),
                "the internal input extent must use the single rounding rule");
        for (float rejected : new float[]{1.0f, 0.05f, 0.0f, -0.5f, Float.NaN,
                Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TemporalUpscalingSettings.internalLowResolution(rejected),
                    "internal temporal upscaling is a reduced-scene contract: " + rejected);
        }
    }

    @Test
    void acceptedBoundariesBuildAndPreserveTheDocumentedWeights() {
        TemporalUpscalingSettings boundary = settings(0.1f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        assertEquals(1.0f, boundary.withRenderScale(1.0f).renderScale());
        assertEquals(0.0f, boundary.historyWeight());
        assertEquals(1.0f, boundary.currentWeight());
        assertEquals(0.99f, boundary.withHistoryBlend(0.99f).historyWeight());
        assertEquals(1.0f, boundary.withSharpness(1.0f).sharpness());
        assertEquals(4096.0f, boundary.withMaxReprojectionPixels(4096.0f)
                .maxReprojectionPixels());
        assertEquals(1.0f, boundary.withExposureThreshold(1.0f).exposureThreshold());
    }

    @Test
    void inputExtentUsesOneRoundedRuleAndNeverReachesZero() {
        TemporalUpscalingSettings half = TemporalUpscalingSettings.disabled();
        assertEquals(960, half.inputWidth(1920));
        assertEquals(540, half.inputHeight(1080));
        assertEquals(32, half.inputWidth(64));
        assertEquals(32, half.inputHeight(64));
        assertEquals(639, half.withRenderScale(0.333f).inputWidth(1920));
        assertEquals(1, half.withRenderScale(0.1f).inputWidth(3));
        assertEquals(1, half.withRenderScale(0.1f).inputWidth(1));
        TemporalUpscalingSettings full = half.withRenderScale(1.0f);
        assertEquals(1920, full.inputWidth(1920));
        assertEquals(1080, full.inputHeight(1080));
        assertThrows(IllegalArgumentException.class, () -> half.inputWidth(0));
        assertThrows(IllegalArgumentException.class, () -> half.inputHeight(-4));
    }

    @Test
    void scaledExtentIsThePublicSingleRuleUsedByOtherExtents() {
        // Odd target extents round half up: 153 * 0.5 = 76.5 -> 77.
        assertEquals(77, TemporalUpscalingSettings.scaledExtent(153, 0.5f));
        assertEquals(33, TemporalUpscalingSettings.scaledExtent(65, 0.5f));
        assertEquals(76, TemporalUpscalingSettings.scaledExtent(152, 0.5f));
        assertEquals(32, TemporalUpscalingSettings.scaledExtent(64, 0.5f));
        assertEquals(151, TemporalUpscalingSettings.scaledExtent(151, 1.0f));
        assertEquals(1, TemporalUpscalingSettings.scaledExtent(1, 0.25f));
        assertEquals(TemporalUpscalingSettings.disabled().inputWidth(153),
                TemporalUpscalingSettings.scaledExtent(153, 0.5f),
                "the accessor and the shared helper must be one rule");
        assertThrows(IllegalArgumentException.class,
                () -> TemporalUpscalingSettings.scaledExtent(0, 0.5f));
    }

    @Test
    void depthExposureAndReactiveFormulasMatchTheirDocumentedWindows() {
        TemporalUpscalingSettings settings = TemporalUpscalingSettings.disabled();
        assertEquals(0.02f, settings.depthTolerance(0.5f), 1.0e-7f);
        assertEquals(0.04f, settings.depthTolerance(2.0f), 1.0e-7f);
        assertEquals(2.0f, settings.depthTolerance(100.0f), 1.0e-7f);
        assertEquals(0.02f, settings.depthTolerance(0.0f), 1.0e-7f);

        assertEquals(0.5f, settings.exposureRelativeChange(2.0f, 1.0f), 1.0e-6f);
        assertEquals(0.05f, settings.exposureRelativeChange(4.0f, 3.8f), 1.0e-6f);
        assertTrue(settings.exposureAccepted(1.05f, 1.0f));
        assertFalse(settings.exposureAccepted(1.2f, 1.0f));
        assertTrue(settings.withExposureThreshold(1.0f).exposureAccepted(1.2f, 1.0f));
        assertFalse(settings.withExposureThreshold(0.0f).exposureAccepted(1.0001f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings.exposureRelativeChange(0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings.exposureRelativeChange(1.0f, -1.0f));

        assertFalse(settings.reactiveReset(2.0f, 1.0f));
        assertTrue(settings.reactiveReset(2.2f, 1.0f));
        assertTrue(settings.withReactiveThreshold(0.0f).reactiveReset(1.0f, 1.0001f));
        assertTrue(settings.reactiveReset(Float.NaN, 1.0f));
    }

    @Test
    void withMethodsAndBuilderPreserveUnrelatedComponents() {
        TemporalUpscalingSettings base = TemporalUpscalingSettings.disabled();
        TemporalUpscalingSettings tweaked = base.withRenderScale(0.75f)
                .withHistoryBlend(0.5f)
                .withSharpness(0.4f)
                .withReactiveThreshold(0.2f)
                .withExposureThreshold(0.3f)
                .withMaxReprojectionPixels(12.0f)
                .withQuality(TemporalUpscalingSettings.Quality.HIGH)
                .withEnabled(true);
        assertEquals(0.75f, tweaked.renderScale());
        assertEquals(0.5f, tweaked.historyBlend());
        assertEquals(0.4f, tweaked.sharpness());
        assertEquals(0.2f, tweaked.reactiveThreshold());
        assertEquals(0.3f, tweaked.exposureThreshold());
        assertEquals(12.0f, tweaked.maxReprojectionPixels());
        assertEquals(TemporalUpscalingSettings.Quality.HIGH, tweaked.quality());
        assertTrue(tweaked.active());

        TemporalUpscalingSettings built = TemporalUpscalingSettings.builder()
                .enabled(true).renderScale(0.75f).historyBlend(0.5f).sharpness(0.4f)
                .reactiveThreshold(0.2f).exposureThreshold(0.3f)
                .maxReprojectionPixels(12.0f)
                .quality(TemporalUpscalingSettings.Quality.HIGH).build();
        assertEquals(tweaked, built);
    }

    @Test
    void qualitySelectsMonotonicBoundedNeighbourhoodRadii() {
        assertEquals(1, TemporalUpscalingSettings.Quality.LOW.neighborhoodRadius());
        assertEquals(2, TemporalUpscalingSettings.Quality.MEDIUM.neighborhoodRadius());
        assertEquals(3, TemporalUpscalingSettings.Quality.HIGH.neighborhoodRadius());
        for (TemporalUpscalingSettings.Quality quality
                : TemporalUpscalingSettings.Quality.values()) {
            assertTrue(quality.neighborhoodRadius() >= 1
                            && quality.neighborhoodRadius() <= 3,
                    "quality radius must stay inside the shader compile-time bound");
        }
    }

    private static TemporalUpscalingSettings settings(float renderScale, float historyBlend,
                                                     float sharpness, float reactiveThreshold,
                                                     float exposureThreshold,
                                                     float maxReprojectionPixels) {
        return new TemporalUpscalingSettings(false, renderScale, historyBlend, sharpness,
                reactiveThreshold, exposureThreshold, maxReprojectionPixels,
                TemporalUpscalingSettings.Quality.MEDIUM);
    }
}
