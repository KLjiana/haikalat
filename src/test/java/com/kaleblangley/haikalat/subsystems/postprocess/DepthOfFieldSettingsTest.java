package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepthOfFieldSettingsTest {
    @Test
    void disabledAndZeroMaximumCoCAreExactNoOps() {
        assertFalse(DepthOfFieldSettings.disabled().active());
        assertFalse(DepthOfFieldSettings.of(4.0f, 2.8f, 0.0f).active());
        assertFalse(DepthOfFieldSettings.builder().enabled(true)
                .focusDistance(4.0f).aperture(2.8f).maxCocPixels(0.0f).build().active());
        assertTrue(DepthOfFieldSettings.of(4.0f, 2.8f, 8.0f).active());
        assertFalse(DepthOfFieldSettings.of(4.0f, 2.8f, 8.0f)
                .withEnabled(false).active());
        assertEquals(DepthOfFieldSettings.DEFAULT_FOCAL_LENGTH_MM,
                DepthOfFieldSettings.of(4.0f, 2.8f, 8.0f).focalLengthMm());
        assertTrue(DepthOfFieldSettings.of(4.0f, 85.0f, 2.8f, 8.0f).active());
    }

    @Test
    void validatesBoundedLensParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, 0.0f, 2.8f, 8.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, 1001.0f, 2.8f, 8.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, Float.NaN, 2.8f, 8.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, 4.0f, 2.8f, 8.0f, 9.9f));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, 4.0f, 2.8f, 8.0f, 200.1f));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, 4.0f, 2.8f, 8.0f, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, 4.0f, 0.9f, 8.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, 4.0f, 22.1f, 8.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, 4.0f, Float.NaN, 8.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, 4.0f, 2.8f, -0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> new DepthOfFieldSettings(true, 4.0f, 2.8f, 16.1f));
        assertThrows(IllegalArgumentException.class,
                () -> DepthOfFieldSettings.builder().maxCocPixels(Float.NaN).build());
    }

    @Test
    void withersPreserveTheOtherParameters() {
        DepthOfFieldSettings settings = DepthOfFieldSettings.of(4.0f, 2.8f, 8.0f);
        assertEquals(new DepthOfFieldSettings(true, 6.0f, 2.8f, 8.0f),
                settings.withFocusDistance(6.0f));
        assertEquals(new DepthOfFieldSettings(true, 4.0f, 4.0f, 8.0f),
                settings.withAperture(4.0f));
        assertEquals(new DepthOfFieldSettings(true, 4.0f, 2.8f, 12.0f),
                settings.withMaxCocPixels(12.0f));
        assertEquals(new DepthOfFieldSettings(true, 4.0f, 2.8f, 8.0f, 85.0f),
                settings.withFocalLengthMm(85.0f));
        assertEquals(85.0f, DepthOfFieldSettings.builder().enabled(true)
                .focalLengthMm(85.0f).build().focalLengthMm(), 0.0f);
    }

    @Test
    void settingsEmbedInPostProcessSettingsWithCompatibleConstructors() {
        DepthOfFieldSettings depthOfField = DepthOfFieldSettings.of(5.0f, 4.0f, 10.0f);
        PostProcessSettings settings = PostProcessSettings.builder()
                .depthOfField(depthOfField).build();
        assertEquals(depthOfField, settings.depthOfField());
        assertTrue(settings.withGtao(GtaoSettings.defaults().withEnabled(true))
                .depthOfField().active());
        assertFalse(PostProcessSettings.defaults().depthOfField().active());
        assertFalse(new PostProcessSettings(ColorGradingSettings.disabled(),
                FogSettings.disabled()).depthOfField().active());
        assertFalse(new PostProcessSettings(ColorGradingSettings.disabled(),
                FogSettings.disabled(), GtaoSettings.disabled()).depthOfField().active());
        assertFalse(new PostProcessSettings(ColorGradingSettings.disabled(),
                FogSettings.disabled(), GtaoSettings.disabled(),
                VignetteSettings.disabled()).depthOfField().active());
        assertFalse(new PostProcessSettings(ColorGradingSettings.disabled(),
                FogSettings.disabled(), GtaoSettings.disabled(),
                VignetteSettings.disabled(), FilmGrainSettings.disabled(),
                ChromaticAberrationSettings.disabled()).depthOfField().active());
        assertFalse(new PostProcessSettings(ColorGradingSettings.disabled(),
                FogSettings.disabled(), GtaoSettings.disabled(),
                VignetteSettings.disabled(), FilmGrainSettings.disabled(),
                ChromaticAberrationSettings.disabled(),
                ScreenDistortionSettings.disabled()).depthOfField().active());
    }
}
