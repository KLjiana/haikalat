package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fail-closed composition contract for the #34 temporal denoiser, which is also
 * an external-target producer.  Bloom cannot size its extract source from the
 * denoise target and automatic exposure would read an undefined luminance
 * source, so both are rejected during assembly instead of failing or silently
 * misbehaving on the first frame.  The check shares
 * {@code PostProcessPipeline.validateTemporalComposition} with the upscaler.
 */
class TemporalDenoiseCompositionTest {
    private static final PostProcessSettings DENOISE = PostProcessSettings.builder()
            .temporalDenoise(TemporalDenoiseSettings.defaults())
            .build();

    @Test
    void denoiseAcceptsAHdrChainWithManualExposureAndNoBloom() {
        assertDoesNotThrow(() -> PostProcessPipeline.validateTemporalComposition(
                hdr(), DENOISE, 64, 64, false, false, null));
    }

    @Test
    void denoiseCannotFeedBloom() {
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(BloomSettings.builder().enabled(true).build(), ExposureMode.MANUAL),
                        DENOISE, 64, 64, false, false, null));
    }

    @Test
    void denoiseCannotFeedAutomaticExposure() {
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateTemporalComposition(
                        hdr(BloomSettings.disabled(), ExposureMode.AUTO), DENOISE, 64, 64,
                        false, false, null));
    }

    private static RenderSettings hdr() {
        return hdr(BloomSettings.disabled(), ExposureMode.MANUAL);
    }

    private static RenderSettings hdr(BloomSettings bloom, ExposureMode exposureMode) {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .bloomSettings(bloom)
                .exposureMode(exposureMode)
                .build();
    }
}
