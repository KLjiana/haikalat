package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.ssr.SsrPass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumer wiring for adaptive sampling: the request only modifies the
 * screen-space reflection step budget, so it must fail closed without an
 * active SSR sampler, without HDR tone mapping, or above the configured SSR
 * hard bound.  The disabled value must pass every check and add no pass.
 */
class AdaptiveSamplingWiringTest {
    @Test
    void disabledRequestPassesWithoutAnyConsumer() {
        RenderSettings ldr = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.NONE)
                .vsync(false)
                .build();
        assertDoesNotThrow(() -> PostProcessPipeline.validateAdaptiveSampling(ldr,
                PostProcessSettings.defaults()));
        assertFalse(PostProcessSettings.defaults().adaptiveSampling().active());
    }

    @Test
    void activeRequestRequiresTheRealSsrConsumer() {
        RenderSettings hdr = hdr();
        PostProcessSettings withoutSsr = PostProcessSettings.defaults()
                .withAdaptiveSampling(AdaptiveSamplingSettings.defaults());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateAdaptiveSampling(hdr, withoutSsr));
        assertTrue(failure.getMessage().contains("screen-space reflection"),
                "the rejection must name the missing consumer: " + failure.getMessage());

        PostProcessSettings withSsr = withoutSsr.withSsr(SsrSettings.defaults());
        assertDoesNotThrow(() -> PostProcessPipeline.validateAdaptiveSampling(hdr, withSsr));
    }

    @Test
    void activeRequestRequiresHdrToneMapping() {
        RenderSettings ldr = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.NONE)
                .vsync(false)
                .build();
        PostProcessSettings effects = PostProcessSettings.defaults()
                .withSsr(SsrSettings.defaults())
                .withAdaptiveSampling(AdaptiveSamplingSettings.defaults());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateAdaptiveSampling(ldr, effects));
        assertTrue(failure.getMessage().contains("HDR"));
    }

    @Test
    void adaptiveCeilingCannotExceedTheConfiguredSsrHardBound() {
        RenderSettings hdr = hdr();
        SsrSettings bounded = SsrSettings.builder().enabled(true).maxSteps(24).build();
        PostProcessSettings effects = PostProcessSettings.defaults()
                .withSsr(bounded)
                .withAdaptiveSampling(AdaptiveSamplingSettings.defaults());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.validateAdaptiveSampling(hdr, effects));
        assertTrue(failure.getMessage().contains("maxSteps"),
                "the rejection must explain the ceiling coupling: " + failure.getMessage());

        PostProcessSettings inside = effects.withAdaptiveSampling(
                AdaptiveSamplingSettings.builder().enabled(true).minimumSteps(8)
                        .maximumSteps(24).build());
        assertDoesNotThrow(() -> PostProcessPipeline.validateAdaptiveSampling(hdr, inside));
    }

    @Test
    void reflectionPassStillRequiresAnActiveSsrRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> new SsrPass(SsrSettings.disabled(),
                        AdaptiveSamplingSettings.defaults()),
                "the adaptive request must not create a reflection pass on its own");
    }

    @Test
    void adaptiveFeatureAddsNoManagedColourChainPass() {
        // Adaptive sampling is an in-shader budget of the existing SsrPass; the
        // static baseline colour chain must not grow a pass.
        for (AntiAliasingMode mode : AntiAliasingMode.values()) {
            for (ToneMappingMode toneMapping : ToneMappingMode.values()) {
                if (mode == AntiAliasingMode.SMAA && toneMapping == ToneMappingMode.NONE) {
                    continue;
                }
                var passes = PostProcessPipeline.passNamesFor(mode, toneMapping);
                assertFalse(passes.stream().anyMatch(name -> name.toLowerCase()
                                .contains("adaptive")),
                        "adaptive sampling must not add a colour-chain pass");
            }
        }
    }

    private static RenderSettings hdr() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }
}
