package com.kaleblangley.haikalat.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 HDR 配置默认值和 exposure 输入边界。 */
class RenderSettingsTest {
    @Test
    void defaultsPreserveLdrRendering() {
        RenderSettings settings = RenderSettings.builder().build();

        assertEquals(ToneMappingMode.NONE, settings.toneMappingMode());
        assertEquals(1.0f, settings.exposure());
        assertEquals(ExposureMode.MANUAL, settings.exposureMode());
        assertFalse(settings.hdrEnabled());
        assertFalse(settings.bloomSettings().enabled());
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
}
