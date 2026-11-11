package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void defaultsKeepDepthOfFieldDisabledAndSevenArgumentConstructorIsCompatible() {
        assertFalse(PostProcessSettings.defaults().depthOfField().active());
        ScreenDistortionSettings distortion = ScreenDistortionSettings.disabled();
        PostProcessSettings legacy = new PostProcessSettings(
                ColorGradingSettings.disabled(), FogSettings.disabled(),
                GtaoSettings.disabled(), VignetteSettings.disabled(),
                FilmGrainSettings.disabled(), ChromaticAberrationSettings.disabled(),
                distortion);
        assertEquals(distortion, legacy.screenDistortion());
        assertFalse(legacy.depthOfField().active());
    }

    @Test
    void replacingGtaoAndDepthOfFieldPreservesEveryOtherEffect() {
        DepthOfFieldSettings depthOfField = DepthOfFieldSettings.of(5.0f, 4.0f, 10.0f);
        PostProcessSettings original = PostProcessSettings.builder()
                .vignette(VignetteSettings.of(0.4f, 0.6f, 0.3f))
                .depthOfField(depthOfField).build();
        PostProcessSettings updated = original.withGtao(GtaoSettings.defaults().withEnabled(true))
                .withDepthOfField(DepthOfFieldSettings.of(7.0f, 5.6f, 6.0f));
        assertEquals(original.vignette(), updated.vignette());
        assertEquals(7.0f, updated.depthOfField().focusDistance());
        assertEquals(5.6f, updated.depthOfField().aperture());
        assertEquals(6.0f, updated.depthOfField().maxCocPixels());
        assertTrue(updated.gtao().enabled());
    }

    @Test
    void defaultsKeepMotionBlurDisabledAndLegacyDepthOfFieldConstructorIsCompatible() {
        assertFalse(PostProcessSettings.defaults().motionBlur().active());
        DepthOfFieldSettings depthOfField = DepthOfFieldSettings.of(5.0f, 4.0f, 10.0f);
        PostProcessSettings legacy = new PostProcessSettings(
                ColorGradingSettings.disabled(), FogSettings.disabled(),
                GtaoSettings.disabled(), VignetteSettings.disabled(),
                FilmGrainSettings.disabled(), ChromaticAberrationSettings.disabled(),
                ScreenDistortionSettings.disabled(), depthOfField);
        assertEquals(depthOfField, legacy.depthOfField());
        assertFalse(legacy.motionBlur().active());
    }

    @Test
    void replacingMotionBlurPreservesEveryOtherEffectAndRejectsNull() {
        MotionBlurSettings motionBlur = MotionBlurSettings.of(0.75f, 24.0f);
        DepthOfFieldSettings depthOfField = DepthOfFieldSettings.of(5.0f, 4.0f, 10.0f);
        PostProcessSettings original = PostProcessSettings.builder()
                .vignette(VignetteSettings.of(0.4f, 0.6f, 0.3f))
                .depthOfField(depthOfField).build();
        PostProcessSettings updated = original.withMotionBlur(motionBlur);
        assertEquals(motionBlur, updated.motionBlur());
        assertEquals(depthOfField, updated.depthOfField());
        assertEquals(original.vignette(), updated.vignette());
        assertEquals(motionBlur, PostProcessSettings.builder()
                .motionBlur(motionBlur).build().motionBlur());
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.defaults().withMotionBlur(null));
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.builder().motionBlur(null));
    }

    @Test
    void withGtaoRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.defaults().withGtao(null));
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.defaults().withDepthOfField(null));
    }
}
