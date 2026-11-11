package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilmGrainSettingsTest {
    @Test
    void disabledAndZeroIntensityAreNoOps() {
        assertFalse(FilmGrainSettings.disabled().active());
        assertFalse(FilmGrainSettings.of(0.0f, 0.5f, 7).active());
        assertTrue(FilmGrainSettings.of(0.05f, 0.5f, 7).active());
    }

    @Test
    void validatesRanges() {
        assertThrows(IllegalArgumentException.class, () -> new FilmGrainSettings(true, -0.1f, 0.5f, 0));
        assertThrows(IllegalArgumentException.class, () -> new FilmGrainSettings(true, 0.4f, 0.5f, 0));
        assertThrows(IllegalArgumentException.class, () -> new FilmGrainSettings(true, 0.1f, 1.1f, 0));
        assertThrows(IllegalArgumentException.class,
                () -> FilmGrainSettings.builder().response(Float.NaN).build());
    }

    @Test
    void embedsInPostProcessSettings() {
        FilmGrainSettings grain = FilmGrainSettings.builder().enabled(true)
                .intensity(0.05f).response(0.25f).seed(11).build();
        PostProcessSettings settings = PostProcessSettings.builder().filmGrain(grain).build();
        assertTrue(settings.filmGrain().active());
        assertFalse(PostProcessSettings.defaults().filmGrain().active());
    }
}
