package com.kaleblangley.haikalat.subsystems.postprocess.ssr;

import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validated bounds and exact no-op semantics of {@link SsrSettings}.
 *
 * <p>The first version is opt-in, so the disabled value must create no pass and
 * every bounded control must fail closed before a candidate generation is
 * prepared.  The march budget is shared with the production shader
 * ({@code SSR_MAX_STEPS}), so the upper bound is asserted explicitly.</p>
 */
class SsrSettingsTest {
    @Test
    void disabledValueIsAnExactNoOpWithBoundedDefaults() {
        SsrSettings settings = SsrSettings.disabled();
        assertFalse(settings.active());
        assertFalse(settings.enabled());
        assertTrue(settings.maxDistanceMetres() > 0.0f);
        assertEquals(SsrSettings.MAXIMUM_STEPS >= settings.maxSteps(), true);
        assertTrue(settings.thicknessMetres() <= settings.maxDistanceMetres());
        assertTrue(settings.minimumConfidence() <= SsrSettings.MAXIMUM_MINIMUM_CONFIDENCE);
        assertFalse(settings.withEnabled(false).active());
        assertEquals(12.0f / 32.0f, SsrSettings.defaults().worldStepMetres(), 1.0e-6f);
    }

    @Test
    void enabledDefaultsStayInsideEveryDocumentedBound() {
        SsrSettings settings = SsrSettings.defaults();
        assertTrue(settings.active());
        assertTrue(settings.roughnessCutoff() >= 0.0f && settings.roughnessCutoff() <= 1.0f);
        assertTrue(settings.minimumConfidence() >= 0.0f);
        assertTrue(settings.strength() > 0.0f && settings.strength() <= 1.0f);
        assertTrue(settings.edgeFadePixels() >= 0.0f);
        assertTrue(settings.resolvesStepsWithinThickness(),
                "the shared default step must resolve the default thickness");
    }

    @Test
    void marchBudgetCannotExceedTheShaderLoopBound() {
        assertEquals(64, SsrSettings.MAXIMUM_STEPS);
        assertEquals(1, SsrSettings.builder().maxSteps(1).build().maxSteps());
        assertEquals(64, SsrSettings.builder().maxSteps(64).build().maxSteps());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().maxSteps(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().maxSteps(65).build());
    }

    @Test
    void worldDistanceThicknessRoughnessAndConfidenceAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().maxDistanceMetres(0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().maxDistanceMetres(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().maxDistanceMetres(1001.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().thicknessMetres(0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> new SsrSettings(true, 2.0f, 32, 3.0f, 0.4f, 0.15f, 0.9f, 24.0f),
                "a thickness larger than the ray cannot be hit");
        assertEquals(2.0f, SsrSettings.builder().maxDistanceMetres(2.0f)
                .thicknessMetres(3.0f).build().thicknessMetres(), 0.0f,
                "the builder keeps thickness inside the ray instead of failing");
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().roughnessCutoff(-0.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().roughnessCutoff(1.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().minimumConfidence(0.96f).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().minimumConfidence(-0.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().strength(0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().strength(1.5f).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().edgeFadePixels(-1.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().edgeFadePixels(257.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> SsrSettings.builder().edgeFadePixels(Float.POSITIVE_INFINITY).build());
    }

    @Test
    void shrinkingTheDistanceKeepsTheThicknessInsideTheNewRay() {
        SsrSettings settings = SsrSettings.builder()
                .enabled(true)
                .maxDistanceMetres(10.0f)
                .thicknessMetres(8.0f)
                .build();
        assertEquals(8.0f, settings.thicknessMetres(), 0.0f);

        SsrSettings shorter = settings.withMaxDistanceMetres(2.0f);
        assertEquals(2.0f, shorter.thicknessMetres(), 0.0f);
        assertEquals(2.0f, shorter.maxDistanceMetres(), 0.0f);
        assertEquals(2.0f / settings.maxSteps(), shorter.worldStepMetres(), 1.0e-6f);
    }

    @Test
    void stepResolutionAndCopyHelpersPreserveTheOtherBounds() {
        SsrSettings coarse = SsrSettings.builder()
                .maxDistanceMetres(10.0f)
                .maxSteps(10)
                .thicknessMetres(0.5f)
                .build();
        assertEquals(1.0f, coarse.worldStepMetres(), 1.0e-6f);
        assertFalse(coarse.resolvesStepsWithinThickness());

        SsrSettings tuned = coarse.withMaxSteps(40).withRoughnessCutoff(0.25f)
                .withMinimumConfidence(0.4f).withStrength(0.5f).withEdgeFadePixels(8.0f);
        assertEquals(coarse.maxDistanceMetres(), tuned.maxDistanceMetres(), 0.0f);
        assertEquals(0.25f, tuned.worldStepMetres(), 1.0e-6f);
        assertTrue(tuned.resolvesStepsWithinThickness());
        assertEquals(0.25f, tuned.roughnessCutoff(), 0.0f);
        assertEquals(0.4f, tuned.minimumConfidence(), 0.0f);
        assertEquals(0.5f, tuned.strength(), 0.0f);
        assertEquals(8.0f, tuned.edgeFadePixels(), 0.0f);
    }

    @Test
    void postProcessSettingsCarriesSsrWithoutDroppingOtherEffects() {
        SsrSettings ssr = SsrSettings.builder().enabled(true).maxSteps(24).build();
        PostProcessSettings settings = PostProcessSettings.defaults().withSsr(ssr);
        assertEquals(ssr, settings.ssr());
        assertEquals(PostProcessSettings.defaults().gtao(), settings.gtao());
        assertFalse(PostProcessSettings.defaults().ssr().active());
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.defaults().withSsr(null));
        assertEquals(ssr, PostProcessSettings.builder().ssr(ssr).build().ssr());
    }
}
