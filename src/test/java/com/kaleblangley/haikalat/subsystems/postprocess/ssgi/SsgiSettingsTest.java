package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounds and no-op contract for the screen-space indirect-diffuse request.
 *
 * <p>These cases pin the finite ranges that keep the shader's loops, march and
 * offscreen budget bounded, and they pin that {@link SsgiSettings#disabled()}
 * is inert and that every with-setter keeps the other channels unchanged.</p>
 */
class SsgiSettingsTest {
    @Test
    void disabledRequestIsInertAndDefaultsEnableTheSharedTuning() {
        SsgiSettings disabled = SsgiSettings.disabled();
        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        assertEquals(8, disabled.sampleCount());
        assertEquals(8, disabled.stepCount());
        assertEquals(64, disabled.tapBudget());
        assertEquals(0.5f, disabled.thicknessMetres());
        assertFalse(disabled.resolvesStepsWithinThickness(),
                "the default 1 m step is coarser than the 0.5 m thickness window");

        SsgiSettings enabled = SsgiSettings.defaults();
        assertTrue(enabled.active());
        assertEquals(disabled.withEnabled(true), enabled,
                "defaults() must be exactly disabled() with the flag flipped");
    }

    @Test
    void sampleAndStepBudgetsRejectOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withSampleCount(0));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withSampleCount(SsgiSettings.MAXIMUM_SAMPLES + 1));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withStepCount(0));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withStepCount(SsgiSettings.MAXIMUM_STEPS + 1));
        assertEquals(SsgiSettings.MAXIMUM_SAMPLES,
                SsgiSettings.disabled().withSampleCount(SsgiSettings.MAXIMUM_SAMPLES)
                        .sampleCount());
        assertEquals(SsgiSettings.MAXIMUM_STEPS,
                SsgiSettings.disabled().withStepCount(SsgiSettings.MAXIMUM_STEPS).stepCount());
    }

    @Test
    void distanceAndThicknessStayCoupledAndFinite() {
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withMaxDistanceMetres(0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withMaxDistanceMetres(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.builder().enabled(true).maxDistanceMetres(4.0f)
                        .thicknessMetres(5.0f).build().withThicknessMetres(5.0f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withThicknessMetres(0.0f));
        assertEquals(4.0f, SsgiSettings.builder().enabled(true).maxDistanceMetres(4.0f)
                        .thicknessMetres(5.0f).build().thicknessMetres(),
                "the builder clamps thickness to the ray length instead of rejecting silently");

        SsgiSettings shrunk = SsgiSettings.builder().enabled(true)
                .maxDistanceMetres(12.0f).thicknessMetres(2.0f).build()
                .withMaxDistanceMetres(1.0f);
        assertEquals(1.0f, shrunk.thicknessMetres(),
                "a shorter ray must clamp thickness so the value stays valid");
    }

    @Test
    void strengthRoughnessOffscreenAndEdgeBoundsAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withStrength(0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withStrength(1.5f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withMinimumSampleRoughness(-0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withMinimumSampleRoughness(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withMaximumOffscreenFraction(-0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withMaximumOffscreenFraction(
                        SsgiSettings.MAXIMUM_OFFSCREEN_FRACTION + 0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withEdgeFadePixels(-1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withEdgeFadePixels(
                        SsgiSettings.MAXIMUM_EDGE_FADE_PIXELS + 1.0f));
        assertEquals(0.0f, SsgiSettings.disabled().withEdgeFadePixels(0.0f).edgeFadePixels());
    }

    @Test
    void ambientFallbackRejectsNegativeAndNonFiniteChannels() {
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withAmbientFallback(-0.01f, 0.1f, 0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withAmbientFallback(0.1f, Float.NaN, 0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withAmbientFallback(0.1f, 0.1f,
                        SsgiSettings.MAXIMUM_AMBIENT_RADIANCE + 0.1f));
        SsgiSettings ambient = SsgiSettings.disabled().withAmbientFallback(0.2f, 0.3f, 0.4f);
        assertEquals(0.2f, ambient.ambientFallbackRed());
        assertEquals(0.3f, ambient.ambientFallbackGreen());
        assertEquals(0.4f, ambient.ambientFallbackBlue());
    }

    @Test
    void withSettersPreserveTheOtherChannelsAndTheBuilderCopiesEveryValue() {
        SsgiSettings tuned = SsgiSettings.builder()
                .enabled(true)
                .sampleCount(4)
                .stepCount(6)
                .maxDistanceMetres(5.0f)
                .thicknessMetres(0.25f)
                .strength(0.5f)
                .minimumSampleRoughness(0.2f)
                .maximumOffscreenFraction(0.25f)
                .edgeFadePixels(4.0f)
                .ambientFallback(0.1f, 0.2f, 0.3f)
                .build();
        assertEquals(24, tuned.tapBudget());
        assertEquals(5.0f / 6.0f, tuned.worldStepMetres(), 1.0e-6f);
        assertFalse(tuned.resolvesStepsWithinThickness(),
                "a 0.83 m step cannot resolve a 0.25 m thickness window");

        assertEquals(4, tuned.withStrength(0.9f).sampleCount());
        assertEquals(0.25f, tuned.withEdgeFadePixels(1.0f).thicknessMetres());
        assertEquals(0.2f, tuned.withMaximumOffscreenFraction(0.1f)
                .minimumSampleRoughness());
        assertNotEquals(tuned, tuned.withStrength(0.9f));
    }

    @Test
    void postProcessSettingsCarriesTheRequestWithoutChangingOtherEffects() {
        SsgiSettings request = SsgiSettings.defaults();
        PostProcessSettings settings = PostProcessSettings.defaults().withSsgi(request);
        assertEquals(request, settings.ssgi());
        assertEquals(PostProcessSettings.defaults().gtao(), settings.gtao());
        assertEquals(PostProcessSettings.defaults().ssr(), settings.ssr());
        assertEquals(PostProcessSettings.defaults().subsurfaceScattering(),
                settings.subsurfaceScattering());
        assertFalse(PostProcessSettings.defaults().ssgi().active(),
                "the default colour chain must not enable SSGI");
        assertThrows(NullPointerException.class, () -> settings.withSsgi(null));

        PostProcessSettings built = PostProcessSettings.builder().ssgi(request).build();
        assertEquals(request, built.ssgi());
    }
}
