package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VignetteSettingsTest {
    @Test
    void disabledAndZeroIntensityAreExactNoOps() {
        assertFalse(VignetteSettings.disabled().active());
        assertFalse(VignetteSettings.of(0.0f, 0.5f, 0.5f).active());
        assertTrue(VignetteSettings.of(0.25f, 0.5f, 0.5f).active());
        assertEquals(0.0f, VignetteSettings.disabled().intensity());
    }

    @Test
    void validatesBoundedParameters() {
        assertThrows(IllegalArgumentException.class, () -> new VignetteSettings(true, -0.1f, 0.5f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> new VignetteSettings(true, 1.1f, 0.5f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> new VignetteSettings(true, 0.2f, -0.1f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> new VignetteSettings(true, 0.2f, 0.5f, 1.1f));
        assertThrows(IllegalArgumentException.class,
                () -> VignetteSettings.builder().intensity(Float.NaN).build());
    }

    @Test
    void settingsEmbedInPostProcessSettingsAndPreserveCompatibility() {
        VignetteSettings vignette = VignetteSettings.builder().enabled(true)
                .intensity(0.4f).smoothness(0.6f).roundness(0.25f).build();
        PostProcessSettings settings = PostProcessSettings.builder().vignette(vignette).build();
        assertEquals(vignette, settings.vignette());
        assertFalse(PostProcessSettings.defaults().vignette().active());
        assertEquals(PostProcessSettings.defaults().vignette(),
                new PostProcessSettings(ColorGradingSettings.disabled(), FogSettings.disabled())
                        .vignette());
    }
}
