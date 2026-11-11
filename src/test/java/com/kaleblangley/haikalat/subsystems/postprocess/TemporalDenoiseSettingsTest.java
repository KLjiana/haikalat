package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validated bounds and exact no-op semantics of {@link TemporalDenoiseSettings}.
 *
 * <p>The first version is opt-in and binds one existing signal.  Invalid
 * values must fail before a candidate generation is prepared, and the disabled
 * value must not create a pass, shader or history attachment.</p>
 */
class TemporalDenoiseSettingsTest {
    @Test
    void disabledValueIsAnExactNoOpWithBoundedDefaults() {
        TemporalDenoiseSettings settings = TemporalDenoiseSettings.disabled();
        assertFalse(settings.active());
        assertFalse(settings.enabled());
        assertTrue(settings.historyLengthFrames() >= TemporalDenoiseSettings.MINIMUM_HISTORY_LENGTH);
        assertTrue(settings.historyLengthFrames() <= TemporalDenoiseSettings.MAXIMUM_HISTORY_LENGTH);
        assertTrue(settings.neighborhoodClampRadius() >= 1);
        assertTrue(settings.neighborhoodClampRadius()
                <= TemporalDenoiseSettings.MAXIMUM_NEIGHBORHOOD_RADIUS);
        assertTrue(settings.maxReprojectionPixels() >= 0.0f);
        assertFalse(settings.withEnabled(false).active());
    }

    @Test
    void enabledDefaultsExposeTheHistoryBlendAndDepthWindow() {
        TemporalDenoiseSettings settings = TemporalDenoiseSettings.defaults();
        assertTrue(settings.active());
        assertEquals(10, settings.historyLengthFrames());
        assertEquals(0.9f, settings.historyWeight(), 1.0e-6f);
        assertEquals(0.1f, settings.currentWeight(), 1.0e-6f);
        assertEquals(0.2f, settings.depthTolerance(10.0f), 1.0e-6f,
                "the relative window must not shrink below one-metre scaling");
        assertEquals(0.02f, settings.depthTolerance(0.0f), 1.0e-6f,
                "the one-metre floor keeps the window at the default relative value");
    }

    @Test
    void historyLengthAndNeighbourhoodAreValidatedAgainstShaderBounds() {
        assertEquals(4, TemporalDenoiseSettings.MAXIMUM_NEIGHBORHOOD_RADIUS);
        assertEquals(2, TemporalDenoiseSettings.MINIMUM_HISTORY_LENGTH);
        assertEquals(64, TemporalDenoiseSettings.MAXIMUM_HISTORY_LENGTH);
        assertEquals(2, TemporalDenoiseSettings.builder().historyLengthFrames(2)
                .build().historyLengthFrames());
        assertEquals(64, TemporalDenoiseSettings.builder().historyLengthFrames(64)
                .build().historyLengthFrames());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder().historyLengthFrames(1).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder().historyLengthFrames(65).build());
        assertEquals(1, TemporalDenoiseSettings.builder().neighborhoodClampRadius(1)
                .build().neighborhoodClampRadius());
        assertEquals(4, TemporalDenoiseSettings.builder().neighborhoodClampRadius(4)
                .build().neighborhoodClampRadius());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder().neighborhoodClampRadius(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder().neighborhoodClampRadius(5).build());
    }

    @Test
    void rejectionWindowsAndPixelBudgetAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder().depthRejectionThreshold(0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder().depthRejectionThreshold(0.51f).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder()
                        .depthRejectionThreshold(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder()
                        .normalRejectionThreshold(-0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder()
                        .normalRejectionThreshold(1.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder()
                        .normalRejectionThreshold(Float.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder().reactiveThreshold(-0.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder()
                        .reactiveThreshold(Float.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder().maxReprojectionPixels(-1.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalDenoiseSettings.builder()
                        .maxReprojectionPixels(TemporalDenoiseSettings.MAXIMUM_REPROJECTION_PIXELS
                                + 1.0f).build());
        assertEquals(0.0f, TemporalDenoiseSettings.builder().maxReprojectionPixels(0.0f)
                .build().maxReprojectionPixels(), 0.0f);
    }

    @Test
    void copyHelpersPreserveTheOtherBounds() {
        TemporalDenoiseSettings base = TemporalDenoiseSettings.builder()
                .enabled(true)
                .historyLengthFrames(8)
                .neighborhoodClampRadius(3)
                .depthRejectionThreshold(0.05f)
                .normalRejectionThreshold(0.7f)
                .reactiveThreshold(0.25f)
                .maxReprojectionPixels(32.0f)
                .build();
        TemporalDenoiseSettings tuned = base.withHistoryLengthFrames(16)
                .withNeighborhoodClampRadius(1)
                .withDepthRejectionThreshold(0.03f)
                .withNormalRejectionThreshold(0.95f)
                .withReactiveThreshold(0.6f)
                .withMaxReprojectionPixels(128.0f);
        assertEquals(16, tuned.historyLengthFrames());
        assertEquals(0.9375f, tuned.historyWeight(), 1.0e-6f);
        assertEquals(1, tuned.neighborhoodClampRadius());
        assertEquals(0.03f, tuned.depthRejectionThreshold(), 0.0f);
        assertEquals(0.95f, tuned.normalRejectionThreshold(), 0.0f);
        assertEquals(0.6f, tuned.reactiveThreshold(), 0.0f);
        assertEquals(128.0f, tuned.maxReprojectionPixels(), 0.0f);
        assertTrue(tuned.active());
        assertEquals(base, base.withEnabled(true));
    }

    @Test
    void postProcessSettingsCarriesTemporalDenoiseWithoutDroppingOtherEffects() {
        TemporalDenoiseSettings denoise = TemporalDenoiseSettings.builder()
                .enabled(true).historyLengthFrames(12).build();
        SsrSettings ssr = SsrSettings.defaults();
        PostProcessSettings settings = PostProcessSettings.defaults()
                .withSsr(ssr)
                .withTemporalDenoise(denoise);
        assertEquals(denoise, settings.temporalDenoise());
        assertEquals(ssr, settings.ssr());
        assertEquals(PostProcessSettings.defaults().gtao(), settings.gtao());
        assertFalse(PostProcessSettings.defaults().temporalDenoise().active());
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.defaults().withTemporalDenoise(null));
        assertEquals(denoise, PostProcessSettings.builder()
                .temporalDenoise(denoise).build().temporalDenoise());
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.builder().temporalDenoise(null));
    }
}
