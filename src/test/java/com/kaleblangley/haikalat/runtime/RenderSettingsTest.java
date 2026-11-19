package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 HDR 配置默认值和 exposure / renderScale 输入边界。 */
class RenderSettingsTest {
    @Test
    void defaultsPreserveLdrRendering() {
        RenderSettings settings = RenderSettings.builder().build();

        assertEquals(ToneMappingMode.NONE, settings.toneMappingMode());
        assertEquals(1.0f, settings.exposure());
        assertEquals(ExposureMode.MANUAL, settings.exposureMode());
        assertFalse(settings.hdrEnabled());
        assertFalse(settings.bloomSettings().enabled());
        assertTrue(settings.sceneVisibility());
    }

    @Test
    void sceneVisibilityCanBeDisabledForSamePathABComparison() {
        assertFalse(RenderSettings.builder().sceneVisibility(false).build().sceneVisibility());
    }

    @Test
    void automaticExposureRequiresHdrAndKeepsManualExposureAsInitialValue() {
        AutoExposureSettings automatic = AutoExposureSettings.builder()
                .minExposure(0.5f)
                .maxExposure(3.0f)
                .build();
        RenderSettings settings = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .exposure(1.25f)
                .exposureMode(ExposureMode.AUTO)
                .autoExposureSettings(automatic)
                .build();

        assertEquals(ExposureMode.AUTO, settings.exposureMode());
        assertEquals(1.25f, settings.exposure());
        assertEquals(automatic, settings.autoExposureSettings());
        assertThrows(IllegalStateException.class, () -> RenderSettings.builder()
                .exposureMode(ExposureMode.AUTO)
                .build());
    }

    @Test
    void autoExposureSettingsRejectInvalidRangesAndSpeeds() {
        assertThrows(IllegalArgumentException.class, () -> AutoExposureSettings.builder()
                .minExposure(2.0f).maxExposure(1.0f).build());
        assertThrows(IllegalArgumentException.class, () -> AutoExposureSettings.builder()
                .keyValue(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> AutoExposureSettings.builder()
                .brightenSpeed(0.0f).build());
        assertThrows(IllegalArgumentException.class, () -> AutoExposureSettings.builder()
                .darkenSpeed(Float.POSITIVE_INFINITY).build());
    }

    @Test
    void acesEnablesHdrRendering() {
        RenderSettings settings = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .exposure(1.25f)
                .build();

        assertTrue(settings.hdrEnabled());
        assertEquals(1.25f, settings.exposure());
    }

    @Test
    void exposureRejectsNonPositiveAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().exposure(0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().exposure(-1.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().exposure(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().exposure(Float.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().exposure(Float.NEGATIVE_INFINITY).build());
    }

    @Test
    void bloomIsImmutableValidatedAndRequiresHdr() {
        BloomSettings bloom = BloomSettings.builder()
                .enabled(true)
                .threshold(1.5f)
                .softKnee(0.25f)
                .intensity(0.1f)
                .maxLevels(4)
                .build();
        RenderSettings settings = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .bloomSettings(bloom)
                .build();

        assertEquals(bloom, settings.bloomSettings());
        assertThrows(IllegalStateException.class, () -> RenderSettings.builder()
                .bloomSettings(bloom)
                .build());
        assertThrows(IllegalArgumentException.class,
                () -> BloomSettings.builder().softKnee(1.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> BloomSettings.builder().maxLevels(0).build());
    }

    @Test
    void renderScaleDefaultsToOneAndTheBuilderRoundTrips() {
        RenderSettings defaults = RenderSettings.builder().build();

        assertEquals(RenderSettings.MAX_RENDER_SCALE, defaults.renderScale());
        assertEquals(1.0f, defaults.renderScale());
        assertEquals(0.25f, RenderSettings.MIN_RENDER_SCALE);
        assertEquals(1.0f, RenderSettings.builder().renderScale(1.0f).build().renderScale());
        assertEquals(0.75f, RenderSettings.builder().renderScale(0.75f).build().renderScale());
        assertEquals(0.25f, RenderSettings.builder().renderScale(0.25f).build().renderScale());
    }

    @Test
    void renderScaleRejectsNonFiniteAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().renderScale(0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().renderScale(0.24f).build());
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().renderScale(1.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().renderScale(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().renderScale(Float.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class,
                () -> RenderSettings.builder().renderScale(Float.NEGATIVE_INFINITY).build());
    }

    @Test
    void withRenderScalePreservesEveryOtherComponentAndValidates() {
        AutoExposureSettings automatic = AutoExposureSettings.builder()
                .minExposure(0.5f)
                .maxExposure(3.0f)
                .build();
        BloomSettings bloom = BloomSettings.builder().enabled(true).maxLevels(4).build();
        RenderSettings base = RenderSettings.builder()
                .vsync(false)
                .antiAliasingMode(AntiAliasingMode.MSAA)
                .msaaSamples(8)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposure(1.5f)
                .exposureMode(ExposureMode.MANUAL)
                .autoExposureSettings(automatic)
                .bloomSettings(bloom)
                .sceneVisibility(false)
                .build();

        RenderSettings scaled = base.withRenderScale(0.5f);

        assertEquals(0.5f, scaled.renderScale());
        assertFalse(scaled.vsync());
        assertEquals(AntiAliasingMode.MSAA, scaled.antiAliasingMode());
        assertEquals(8, scaled.msaaSamples());
        assertEquals(ToneMappingMode.ACES, scaled.toneMappingMode());
        assertEquals(1.5f, scaled.exposure());
        assertEquals(ExposureMode.MANUAL, scaled.exposureMode());
        assertEquals(automatic, scaled.autoExposureSettings());
        assertEquals(bloom, scaled.bloomSettings());
        assertFalse(scaled.sceneVisibility());
        assertEquals(1.0f, base.renderScale(), "withRenderScale must not mutate the receiver");
        assertThrows(IllegalArgumentException.class, () -> base.withRenderScale(0.1f));
        assertThrows(IllegalArgumentException.class, () -> base.withRenderScale(Float.NaN));
    }

    @Test
    void visualProfileOverridesPreserveRenderScaleForExistingCallers() {
        RenderSettings scaled = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .renderScale(0.5f)
                .build();

        RenderSettings visual = scaled.withVisualSettings(2.0f, BloomSettings.disabled(),
                AntiAliasingMode.NONE);

        assertEquals(0.5f, visual.renderScale(),
                "the existing visual-profile entry must not reset the scene resolution");
        assertEquals(2.0f, visual.exposure());
        assertEquals(AntiAliasingMode.NONE, visual.antiAliasingMode());
    }

}
