package com.kaleblangley.haikalat.subsystems.postprocess.vrs;

import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingCapability;
import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingRate;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure request validation and capability-decision contract for feature #42. */
class VariableRateShadingSettingsTest {
    private static VariableRateShadingCapability supported() {
        return VariableRateShadingCapability.evaluate(true, true, true, false, false, false,
                16, 16, 8, 16);
    }

    private static VariableRateShadingCapability unavailable() {
        return VariableRateShadingCapability.evaluate(true, false, false, false, false, false,
                0, 0, 0, 0);
    }

    @Test
    void disabledIsAnExactNoOpAndNeverReportsActive() {
        VariableRateShadingSettings disabled = VariableRateShadingSettings.disabled();
        assertFalse(disabled.active());
        assertEquals("disabled", disabled.summary());
        assertEquals(VariableRateShadingSettings.Decision.DISABLED, disabled.decide(supported()));
        assertEquals(VariableRateShadingSettings.Decision.DISABLED, disabled.decide(unavailable()));
        assertEquals(VariableRateShadingSettings.Decision.DISABLED, disabled.decide(null));
    }

    @Test
    void builderDefaultsToASelectiveFineCentreAndCoarsePeriphery() {
        VariableRateShadingSettings settings = VariableRateShadingSettings.builder().build();
        assertTrue(settings.active());
        assertEquals(VariableRateShadingRate.ONE_BY_ONE, settings.fineRate());
        assertEquals(VariableRateShadingRate.TWO_BY_TWO, settings.coarseRate());
        assertEquals(0.5f, settings.fineRegionFraction());
        assertEquals("fine=1x1 coarse=2x2 centre=50%", settings.summary());
        assertEquals(VariableRateShadingSettings.Decision.ACTIVE_NV_IMAGE,
                settings.decide(supported()));
    }

    @Test
    void unavailableExtensionFallsBackAndIsNeverReportedAsActive() {
        VariableRateShadingSettings settings = VariableRateShadingSettings.builder().build();
        assertEquals(VariableRateShadingSettings.Decision.FALLBACK_UNSUPPORTED,
                settings.decide(unavailable()));
        assertEquals(VariableRateShadingSettings.Decision.FALLBACK_UNSUPPORTED,
                settings.decide(null));
        assertEquals(VariableRateShadingSettings.Decision.FALLBACK_UNSUPPORTED,
                settings.decide(VariableRateShadingCapability.evaluate(true, true, false,
                        false, true, true, 0, 0, 0, 0)));
        assertFalse(VariableRateShadingCapability.evaluate(true, true, true, false, false, false,
                16, 16, 1, 16).nvImagePathAvailable());
    }

    @Test
    void validationRejectsNonSelectiveAndNonFiniteRequests() {
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingSettings.builder()
                        .fineRate(VariableRateShadingRate.TWO_BY_TWO).build());
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingSettings.builder()
                        .coarseRate(VariableRateShadingRate.ONE_BY_ONE).build());
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingSettings.builder().fineRegionFraction(0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingSettings.builder().fineRegionFraction(1.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingSettings.builder().fineRegionFraction(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingSettings.builder().fineRegionFraction(0.99f).build());
        assertThrows(NullPointerException.class,
                () -> VariableRateShadingSettings.builder().fineRate(null));
        assertThrows(NullPointerException.class,
                () -> VariableRateShadingSettings.builder().coarseRate(null));
        assertThrows(IllegalArgumentException.class,
                () -> new VariableRateShadingSettings(true,
                        VariableRateShadingRate.ONE_BY_ONE, VariableRateShadingRate.FOUR_BY_FOUR,
                        Float.POSITIVE_INFINITY));
    }

    @Test
    void postProcessSettingsCarriesTheRequestWithoutLosingOtherEffects() {
        VariableRateShadingSettings request = VariableRateShadingSettings.builder()
                .coarseRate(VariableRateShadingRate.FOUR_BY_FOUR).build();
        GtaoSettings gtao = GtaoSettings.defaults().withEnabled(true);
        PostProcessSettings settings = PostProcessSettings.defaults()
                .withVariableRateShading(request)
                .withGtao(gtao);
        assertSame(request, settings.variableRateShading());
        assertEquals(gtao, settings.gtao());
        assertEquals(PostProcessSettings.defaults().subsurfaceScattering(),
                settings.subsurfaceScattering());
        assertEquals(request, PostProcessSettings.builder()
                .variableRateShading(request).build().variableRateShading());
        assertFalse(PostProcessSettings.defaults().variableRateShading().active());
        assertNotSame(PostProcessSettings.defaults(),
                PostProcessSettings.defaults().withVariableRateShading(request));
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.defaults().withVariableRateShading(null));
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.builder().variableRateShading(null));
    }
}
