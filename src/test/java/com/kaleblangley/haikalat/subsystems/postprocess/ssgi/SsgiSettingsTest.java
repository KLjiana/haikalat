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
        assertFalse(enabled.temporalActive(),
                "the shared default tuning must stay on the exact spatial path");
        assertEquals(0.0f, enabled.temporalBlend());
    }

    @Test
    void compatibilityConstructorLeavesTemporalAccumulationDisabled() {
        SsgiSettings previousCallSite = new SsgiSettings(true, 4, 6, 5.0f, 0.25f, 0.5f,
                0.2f, 0.25f, 4.0f, 0.1f, 0.2f, 0.3f);
        assertTrue(previousCallSite.active());
        assertEquals(0.0f, previousCallSite.temporalBlend(),
                "the pre-temporal canonical constructor must select the exact spatial path");
        assertFalse(previousCallSite.temporalActive());
        assertEquals(8, previousCallSite.historyFrames());
        assertEquals(0.02f, previousCallSite.temporalDepthRejection());
        assertEquals(0.9f, previousCallSite.temporalNormalRejection());
        assertEquals(64.0f, previousCallSite.temporalVelocityRejection());
        assertEquals(4, previousCallSite.sampleCount());
    }

    @Test
    void halfResolutionIsOptInAndDoesNotSilentlyDisableTemporalAccumulation() {
        assertFalse(SsgiSettings.defaults().halfResolution());
        SsgiSettings half = SsgiSettings.defaults().withHalfResolution(true);
        assertTrue(half.withStrength(0.5f).withEnabled(false).halfResolution());
        assertThrows(IllegalArgumentException.class, () -> half.withTemporalBlend(0.9f));
        assertThrows(IllegalArgumentException.class, () -> SsgiSettings.builder()
                .temporalBlend(0.9f).halfResolution(true).build());
    }

    @Test
    void temporalTimingAndRejectionBoundsAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withTemporalBlend(-0.01f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withTemporalBlend(
                        SsgiSettings.MAXIMUM_TEMPORAL_BLEND + 0.01f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withTemporalBlend(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withHistoryFrames(0));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withHistoryFrames(
                        SsgiSettings.MAXIMUM_HISTORY_FRAMES + 1));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withTemporalDepthRejection(0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withTemporalDepthRejection(
                        SsgiSettings.MAXIMUM_TEMPORAL_DEPTH_REJECTION + 0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withTemporalNormalRejection(-0.01f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withTemporalNormalRejection(1.01f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withTemporalVelocityRejection(-1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> SsgiSettings.disabled().withTemporalVelocityRejection(
                        SsgiSettings.MAXIMUM_TEMPORAL_REPROJECTION_PIXELS + 1.0f));

        SsgiSettings temporal = SsgiSettings.disabled()
                .withTemporalBlend(0.9f)
                .withHistoryFrames(16)
                .withTemporalDepthRejection(0.05f)
                .withTemporalNormalRejection(0.75f)
                .withTemporalVelocityRejection(24.0f);
        assertTrue(temporal.temporalActive());
        assertEquals(0.9f, temporal.temporalBlend());
        assertEquals(16, temporal.historyFrames());
        assertEquals(0.05f, temporal.temporalDepthRejection());
        assertEquals(0.75f, temporal.temporalNormalRejection());
        assertEquals(24.0f, temporal.temporalVelocityRejection());
    }

    @Test
    void historyWeightRampsFromTheFirstSampleAndIsBoundedByTheHorizon() {
        SsgiSettings temporal = SsgiSettings.disabled()
                .withTemporalBlend(0.9f).withHistoryFrames(8)
                .withTemporalDepthRejection(0.05f);
        assertEquals(0.0f, temporal.historyWeightFor(0), 0.0f,
                "no retained sample means no history contribution");
        assertEquals(0.0f, temporal.historyWeightFor(1), 0.0f,
                "the first sample must be current-only");
        assertEquals(0.5f, temporal.historyWeightFor(2), 1.0e-6f);
        assertEquals(2.0f / 3.0f, temporal.historyWeightFor(3), 1.0e-6f);
        assertEquals(0.875f, temporal.historyWeightFor(8), 1.0e-6f);
        assertEquals(0.875f, temporal.historyWeightFor(64), 1.0e-6f,
                "the retained-sample horizon caps the weight");
        assertEquals(0.0f, SsgiSettings.disabled().historyWeightFor(32), 0.0f,
                "a zero blend must never contribute history");
        assertEquals(0.02f, SsgiSettings.disabled().depthTolerance(0.0f), 0.0f,
                "the default depth window lands on its metre floor");
        assertEquals(0.05f, temporal.depthTolerance(0.0f), 1.0e-6f);
        assertEquals(0.1f, temporal.depthTolerance(2.0f), 1.0e-6f);
    }

    @Test
    void temporalSettersPreserveTheOtherChannelsAndTheBuilderRoundTrips() {
        SsgiSettings base = SsgiSettings.builder().enabled(true).sampleCount(4)
                .stepCount(4).strength(0.6f).temporalBlend(0.8f).historyFrames(12)
                .temporalDepthRejection(0.04f).temporalNormalRejection(0.85f)
                .temporalVelocityRejection(48.0f).build();
        SsgiSettings changed = base.withTemporalBlend(0.5f);
        assertEquals(4, changed.sampleCount());
        assertEquals(12, changed.historyFrames());
        assertEquals(0.04f, changed.temporalDepthRejection());
        assertEquals(0.85f, changed.temporalNormalRejection());
        assertEquals(48.0f, changed.temporalVelocityRejection());
        assertEquals(0.8f, base.temporalBlend(), "withTemporalBlend must not mutate the value");
        assertEquals(0.5f, changed.temporalBlend());

        SsgiSettings depth = base.withTemporalDepthRejection(0.06f);
        assertEquals(0.06f, depth.temporalDepthRejection());
        assertEquals(0.8f, depth.temporalBlend());
        SsgiSettings normal = base.withTemporalNormalRejection(0.7f);
        assertEquals(0.7f, normal.temporalNormalRejection());
        assertEquals(0.04f, normal.temporalDepthRejection());
        SsgiSettings velocity = base.withTemporalVelocityRejection(16.0f);
        assertEquals(16.0f, velocity.temporalVelocityRejection());
        SsgiSettings frames = base.withHistoryFrames(32);
        assertEquals(32, frames.historyFrames());
        assertEquals(0.8f, frames.temporalBlend());
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
