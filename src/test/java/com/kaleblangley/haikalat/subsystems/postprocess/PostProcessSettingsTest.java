package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostProcessSettingsTest {
    @Test
    void replacingGtaoPreservesEveryScreenEffect() {
        VignetteSettings vignette = VignetteSettings.of(0.4f, 0.6f, 0.3f);
        FilmGrainSettings grain = FilmGrainSettings.of(0.05f, 0.4f, 9);
        ChromaticAberrationSettings aberration = ChromaticAberrationSettings.of(0.003f, 0.8f, 5);
        ScreenDistortionSettings distortion = ScreenDistortionSettings.builder()
                .enabled(true).maximumOffsetUv(0.04f).source(List::of).build();
        PostProcessSettings original = PostProcessSettings.builder()
                .vignette(vignette).filmGrain(grain).chromaticAberration(aberration)
                .screenDistortion(distortion).build();
        GtaoSettings gtao = GtaoSettings.defaults().withEnabled(true);
        PostProcessSettings updated = original.withGtao(gtao);
        assertEquals(gtao, updated.gtao());
        assertEquals(vignette, updated.vignette());
        assertEquals(grain, updated.filmGrain());
        assertEquals(aberration, updated.chromaticAberration());
        assertEquals(distortion, updated.screenDistortion());
        assertNotEquals(original.gtao(), updated.gtao());
    }

    @Test
    void defaultsKeepScreenDistortionDisabled() {
        assertFalse(PostProcessSettings.defaults().screenDistortion().active());
    }

    @Test
    void withGtaoRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.defaults().withGtao(null));
    }
}
