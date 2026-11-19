package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromaticAberrationSettingsTest {
    @Test
    void disabledAndZeroIntensityAreNoOps() {
        assertFalse(ChromaticAberrationSettings.disabled().active());
        assertFalse(ChromaticAberrationSettings.of(0.0f, 1.0f, 3).active());
        assertTrue(ChromaticAberrationSettings.of(0.004f, 1.0f, 3).active());
    }

    @Test
    void validatesRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChromaticAberrationSettings(true, -0.1f, 1.0f, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ChromaticAberrationSettings(true, 0.03f, 1.0f, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ChromaticAberrationSettings(true, 0.004f, 1.1f, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ChromaticAberrationSettings(true, 0.004f, 1.0f, 9));
    }

    @Test
    void embedsInPostProcessSettings() {
        ChromaticAberrationSettings aberration = ChromaticAberrationSettings.builder()
                .enabled(true).intensity(0.003f).radial(0.8f).samples(5).build();
        PostProcessSettings settings = PostProcessSettings.builder()
                .chromaticAberration(aberration).build();
        assertTrue(settings.chromaticAberration().active());
        assertFalse(PostProcessSettings.defaults().chromaticAberration().active());
    }
}
