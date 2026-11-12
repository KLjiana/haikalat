package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingCapability;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.AdaptiveSamplingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.vrs.VariableRateShadingSettings;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unified feature-control contract for the three advanced post-process
 * features that were integrated through {@code PostProcessSettings} only.
 *
 * <p>The request is deliberately not duplicated in
 * {@link RenderFeatureSettings}: {@code postProcess.ssgi},
 * {@code postProcess.adaptiveSampling} and
 * {@code postProcess.variableRateShading} stay the single source of truth.
 * This class pins the resolved effective participation, the human-readable
 * unavailable reasons, the strict conflicts, the exact disabled no-op and the
 * topology/generation key consequences.  No GL context is required; the
 * pass-level insertion and resource release are already covered by the GL
 * composition classes.</p>
 */
class RenderFeatureControlPostProcessTest {
    private static final RenderFeaturePolicy.Facts LDR_NO_PBR = facts(false, false);
    private static final RenderFeaturePolicy.Facts HDR_PBR_WITH_NV =
            facts(true, true);
    private static final RenderFeaturePolicy.Facts HDR_PBR_WITHOUT_NV =
            facts(true, false);

    @Test
    void defaultsReportAllThreeAsDisabledWithNoEffectiveChange() {
        RenderFeatureSettings defaults = RenderFeatureSettings.defaults();
        var resolved = RenderFeaturePolicy.resolve(defaults, LDR_NO_PBR);

        assertFalse(resolved.ssgi());
        assertFalse(resolved.adaptiveSampling());
        assertFalse(resolved.variableRateShading());
        assertEquals(RenderFeatureReason.DISABLED,
                activity(resolved, RenderFeature.SSGI).reason());
        assertEquals(RenderFeatureReason.DISABLED,
                activity(resolved, RenderFeature.ADAPTIVE_SAMPLING).reason());
        assertEquals(RenderFeatureReason.DISABLED,
                activity(resolved, RenderFeature.VARIABLE_RATE_SHADING).reason());
        assertTrue(activity(resolved, RenderFeature.SSGI).detail().isEmpty());
        assertSame(defaults.postProcess(), resolved.effectivePostProcess(),
                "the disabled request must be the exact no-op value");
    }

    @Test
    void ssgiIsActiveWithHdrPbrMaterialsAndNoTemporalUpscaler() {
        RenderFeatureSettings request = hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .ssgi(SsgiSettings.defaults()).build())
                .build();

        var resolved = RenderFeaturePolicy.resolve(request, HDR_PBR_WITHOUT_NV);

        assertTrue(resolved.ssgi());
        assertEquals(RenderFeatureReason.ACTIVE, activity(resolved, RenderFeature.SSGI).reason());
        assertTrue(activity(resolved, RenderFeature.SSGI).detail().isEmpty());
        assertTrue(resolved.effectivePostProcess().ssgi().active());
    }

    @Test
    void ssgiWithoutPbrOrHdrIsUnavailableAndStrippedFromTheEffectiveSnapshot() {
        RenderFeatureSettings noPbr = hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .ssgi(SsgiSettings.defaults()).build())
                .build();
        var withoutPbr = RenderFeaturePolicy.resolve(noPbr, LDR_NO_PBR);

        assertFalse(withoutPbr.ssgi());
        RenderFeatureActivity pbrActivity = activity(withoutPbr, RenderFeature.SSGI);
        assertEquals(RenderFeatureReason.UNAVAILABLE, pbrActivity.reason());
        assertTrue(pbrActivity.detail().contains("metallic-roughness PBR"),
                "the reason must name the PBR/ALBEDO/ROUGHNESS dependency: "
                        + pbrActivity.detail());
        assertTrue(pbrActivity.detail().contains("ALBEDO")
                        && pbrActivity.detail().contains("ROUGHNESS"),
                "the reason must name both missing surface channels: " + pbrActivity.detail());
        assertFalse(withoutPbr.effectivePostProcess().ssgi().active(),
                "an unavailable request must not reach the candidate generation");
        assertTrue(noPbr.postProcess().ssgi().active(),
                "the requested snapshot keeps the caller intent");

        RenderFeatureSettings noHdr = RenderFeatureSettings.builder()
                .render(ldr())
                .postProcess(PostProcessSettings.builder()
                        .ssgi(SsgiSettings.defaults()).build())
                .build();
        var withoutHdr = RenderFeaturePolicy.resolve(noHdr, LDR_NO_PBR);
        assertFalse(withoutHdr.ssgi());
        RenderFeatureActivity hdrActivity = activity(withoutHdr, RenderFeature.SSGI);
        assertEquals(RenderFeatureReason.UNAVAILABLE, hdrActivity.reason());
        assertTrue(hdrActivity.detail().contains("HDR"), hdrActivity.detail());
        assertFalse(withoutHdr.effectivePostProcess().ssgi().active());
    }

    @Test
    void ssgiRejectsTemporalUpscalingAsAConfigurationConflict() {
        RenderFeatureSettings request = hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .ssgi(SsgiSettings.defaults())
                        .temporalUpscaling(TemporalUpscalingSettings.defaults())
                        .build())
                .build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(request, LDR_NO_PBR));
        assertTrue(failure.getMessage().contains("temporal upscaling"),
                "the rejection must name the conflicting producer: " + failure.getMessage());
    }

    @Test
    void adaptiveSamplingRequiresAnActiveSsrConsumerAndReportsWhy() {
        RenderFeatureSettings noSsr = hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .adaptiveSampling(AdaptiveSamplingSettings.defaults()).build())
                .build();
        var resolved = RenderFeaturePolicy.resolve(noSsr, LDR_NO_PBR);

        assertFalse(resolved.adaptiveSampling());
        RenderFeatureActivity activity = activity(resolved, RenderFeature.ADAPTIVE_SAMPLING);
        assertEquals(RenderFeatureReason.UNAVAILABLE, activity.reason());
        assertTrue(activity.detail().contains("screen-space reflection"),
                activity.detail());
        assertFalse(resolved.effectivePostProcess().adaptiveSampling().active(),
                "adaptive sampling must not request a shader variant without its consumer");

        SsrSettings ssr = SsrSettings.defaults();
        RenderFeatureSettings valid = hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .ssr(ssr)
                        .adaptiveSampling(AdaptiveSamplingSettings.defaults())
                        .build())
                .build();
        var active = RenderFeaturePolicy.resolve(valid, facts(true, false));
        assertTrue(active.adaptiveSampling());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(active, RenderFeature.ADAPTIVE_SAMPLING).reason());
    }

    @Test
    void adaptiveCeilingCannotExceedTheSsrHardBound() {
        SsrSettings boundedSsr = SsrSettings.builder().enabled(true).maxSteps(24).build();
        RenderFeatureSettings request = hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .ssr(boundedSsr)
                        .adaptiveSampling(AdaptiveSamplingSettings.defaults())
                        .build())
                .build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(request, facts(true, false)));
        assertTrue(failure.getMessage().contains("maxSteps"),
                "the rejection must explain the ceiling coupling: " + failure.getMessage());
    }

    @Test
    void vrsReportsNvFallbackAndRequiresTheSubsurfaceConsumer() {
        RenderFeatureSettings vrsOnly = hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .subsurfaceScattering(SubsurfaceScatteringSettings.builder().build())
                        .variableRateShading(VariableRateShadingSettings.builder().build())
                        .build())
                .build();

        var fallback = RenderFeaturePolicy.resolve(vrsOnly, HDR_PBR_WITHOUT_NV);
        assertFalse(fallback.variableRateShading());
        RenderFeatureActivity fallbackActivity =
                activity(fallback, RenderFeature.VARIABLE_RATE_SHADING);
        assertEquals(RenderFeatureReason.UNAVAILABLE, fallbackActivity.reason());
        assertTrue(fallbackActivity.detail().contains("NV_shading_rate_image"),
                fallbackActivity.detail());
        assertTrue(fallbackActivity.detail().contains("fallback"),
                "the reason must describe the fallback, not a silent off state: "
                        + fallbackActivity.detail());
        assertTrue(fallback.effectivePostProcess().variableRateShading().active(),
                "the fallback request stays in the snapshot so PostProcessPipeline can "
                        + "report its fallback status and allocate no VRS resource");

        var active = RenderFeaturePolicy.resolve(vrsOnly, HDR_PBR_WITH_NV);
        assertTrue(active.variableRateShading());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(active, RenderFeature.VARIABLE_RATE_SHADING).reason());

        RenderFeatureSettings withoutConsumer = hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .variableRateShading(VariableRateShadingSettings.builder().build())
                        .build())
                .build();
        var unavailable = RenderFeaturePolicy.resolve(withoutConsumer, HDR_PBR_WITH_NV);
        assertFalse(unavailable.variableRateShading());
        RenderFeatureActivity consumerActivity =
                activity(unavailable, RenderFeature.VARIABLE_RATE_SHADING);
        assertEquals(RenderFeatureReason.UNAVAILABLE, consumerActivity.reason());
        assertTrue(consumerActivity.detail().contains("subsurface scattering"),
                consumerActivity.detail());
        assertFalse(unavailable.effectivePostProcess().variableRateShading().active(),
                "without its declared consumer the VRS request must not reach assembly");
    }

    @Test
    void vrsWithoutHdrIsUnavailableAndStripped() {
        RenderFeatureSettings request = RenderFeatureSettings.builder()
                .render(ldr())
                .postProcess(PostProcessSettings.builder()
                        .subsurfaceScattering(SubsurfaceScatteringSettings.builder().build())
                        .variableRateShading(VariableRateShadingSettings.builder().build())
                        .build())
                .build();

        var resolved = RenderFeaturePolicy.resolve(request, facts(false, true));
        assertFalse(resolved.variableRateShading());
        RenderFeatureActivity activity =
                activity(resolved, RenderFeature.VARIABLE_RATE_SHADING);
        assertEquals(RenderFeatureReason.UNAVAILABLE, activity.reason());
        assertTrue(activity.detail().contains("HDR"), activity.detail());
        assertFalse(resolved.effectivePostProcess().variableRateShading().active());
    }

    @Test
    void masterPauseKeepsThePostProcessRequestButPausesAllThree() {
        RenderFeatureSettings request = hdrBuilder()
                .enabled(false)
                .postProcess(PostProcessSettings.builder()
                        .ssgi(SsgiSettings.defaults())
                        .ssr(SsrSettings.defaults())
                        .adaptiveSampling(AdaptiveSamplingSettings.defaults())
                        .subsurfaceScattering(SubsurfaceScatteringSettings.builder().build())
                        .variableRateShading(VariableRateShadingSettings.builder().build())
                        .build())
                .build();

        var resolved = RenderFeaturePolicy.resolve(request, HDR_PBR_WITH_NV);
        assertFalse(resolved.ssgi());
        assertFalse(resolved.adaptiveSampling());
        assertFalse(resolved.variableRateShading());
        for (RenderFeature feature : new RenderFeature[]{RenderFeature.SSGI,
                RenderFeature.ADAPTIVE_SAMPLING, RenderFeature.VARIABLE_RATE_SHADING}) {
            assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                    activity(resolved, feature).reason(), feature.name());
        }
        assertEquals(request.postProcess(), resolved.effectivePostProcess(),
                "pause must keep the complete request for the resume path");
    }

    @Test
    void controlPublishesRequestedAndSanitizedEffectiveSeparately() {
        RenderPipeline pipeline = pipeline();
        try {
            PostProcessSettings ssgiOnly = PostProcessSettings.builder()
                    .ssgi(SsgiSettings.defaults()).build();
            RenderFeatureSettings requested = RenderFeatureSettings.builder()
                    .render(hdr(AntiAliasingMode.NONE))
                    .postProcess(ssgiOnly)
                    .build();
            var resolved = RenderFeaturePolicy.resolve(requested, LDR_NO_PBR);
            RenderFeatureSettings effective = requested.toBuilder()
                    .postProcess(resolved.effectivePostProcess()).build();

            long revision = pipeline.features().submit(requested);
            pipeline.applyFeatureFields(effective);
            pipeline.features().publish(requested, effective, revision,
                    resolved.activities());

            assertEquals(requested, pipeline.features().requested(),
                    "the caller intent is preserved verbatim");
            assertEquals(effective, pipeline.features().effective(),
                    "the effective snapshot carries the sanitized post-process request");
            assertTrue(pipeline.features().requested().postProcess().ssgi().active());
            assertFalse(pipeline.features().effective().postProcess().ssgi().active());
            assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
            assertFalse(pipeline.ssgi().active(),
                    "the candidate path consumes the sanitized value");
        } finally {
            pipeline.close();
        }
    }

    @Test
    void featureRequestAndPostProcessSettingsBothReachThePipelineAccessors() {
        RenderPipeline pipeline = pipeline();
        try {
            PostProcessSettings requested = PostProcessSettings.builder()
                    .ssgi(SsgiSettings.defaults())
                    .ssr(SsrSettings.defaults())
                    .adaptiveSampling(AdaptiveSamplingSettings.defaults())
                    .subsurfaceScattering(SubsurfaceScatteringSettings.builder().build())
                    .variableRateShading(VariableRateShadingSettings.builder().build())
                    .build();
            pipeline.postProcessSettings(requested);
            assertEquals(requested.ssgi(), pipeline.ssgi());
            assertEquals(requested.adaptiveSampling(), pipeline.adaptiveSampling());
            assertEquals(requested.variableRateShading(), pipeline.variableRateShading());

            RenderFeatureSettings featureRequest = RenderFeatureSettings.builder()
                    .render(hdr(AntiAliasingMode.NONE))
                    .postProcess(requested)
                    .build();
            long revision = pipeline.features().submit(featureRequest);

            assertEquals(revision, pipeline.features().requestedRevision());
            assertEquals(featureRequest, pipeline.features().requested());
            assertEquals(RenderFeatureStatus.PENDING, pipeline.features().status(),
                    "submission is recorded; the generation boundary applies it");

            // The build path resolves the request, sanitizes unavailable effects
            // and only then installs the effective snapshot.  This is the exact
            // sequence buildInternal/applyPendingFeatureRequest use.
            var resolved = RenderFeaturePolicy.resolve(featureRequest, HDR_PBR_WITH_NV);
            pipeline.applyFeatureFields(featureRequest.toBuilder()
                    .postProcess(resolved.effectivePostProcess()).build());
            assertEquals(requested.ssgi(), pipeline.ssgi());
            assertEquals(requested.adaptiveSampling(), pipeline.adaptiveSampling());
            assertEquals(requested.variableRateShading(), pipeline.variableRateShading());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void enablingSsgiChangesTheGenerationKeyAndDisablingRestoresTheBaseline() {
        Scene scene = new Scene(new Camera());
        RenderSettings hdr = hdr(AntiAliasingMode.NONE);
        PostProcessSettings baselineEffects = PostProcessSettings.defaults();
        PostProcessSettings ssgiEffects = PostProcessSettings.builder()
                .ssgi(SsgiSettings.defaults()).build();

        PipelineTopology baseline = capture(scene, hdr, baselineEffects);
        PipelineTopology enabled = capture(scene, hdr, ssgiEffects);
        PipelineTopology disabledAgain = capture(scene, hdr, baselineEffects);

        assertNotEquals(baseline, enabled,
                "SSGI adds ALBEDO/ROUGHNESS surface inputs and must rebuild the generation");
        assertTrue(enabled.sceneSurface().requests().requires(SceneBufferChannel.ALBEDO));
        assertTrue(enabled.sceneSurface().requests().requires(SceneBufferChannel.ROUGHNESS));
        assertEquals(baseline, disabledAgain,
                "disabling SSGI must remove the extra surface inputs and restore the key");
        assertFalse(disabledAgain.sceneSurface().requests().requires(SceneBufferChannel.ALBEDO));
    }

    @Test
    void unavailableSsgiIsSanitizedBeforeTheTopologyIsCaptured() {
        Scene scene = new Scene(new Camera());
        RenderSettings hdr = hdr(AntiAliasingMode.NONE);
        RenderFeatureSettings request = RenderFeatureSettings.builder()
                .render(hdr)
                .postProcess(PostProcessSettings.builder()
                        .ssgi(SsgiSettings.defaults()).build())
                .build();

        var resolved = RenderFeaturePolicy.resolve(request, LDR_NO_PBR);
        RenderFeatureSettings effective = request.toBuilder()
                .postProcess(resolved.effectivePostProcess()).build();
        PipelineTopology topology = capture(scene, hdr, effective.postProcess());

        assertEquals(capture(scene, hdr, PostProcessSettings.defaults()), topology);
        assertFalse(topology.sceneSurface().requests().requires(SceneBufferChannel.ALBEDO));
    }

    @Test
    void adaptiveAndVrsDoNotGrowTheGraphWhenTheirConsumersAlreadyExist() {
        Scene scene = new Scene(new Camera());
        RenderSettings hdr = hdr(AntiAliasingMode.NONE);
        PostProcessSettings consumers = PostProcessSettings.builder()
                .ssr(SsrSettings.defaults())
                .subsurfaceScattering(SubsurfaceScatteringSettings.builder().build())
                .build();
        PostProcessSettings withRequest = consumers
                .withAdaptiveSampling(AdaptiveSamplingSettings.defaults())
                .withVariableRateShading(VariableRateShadingSettings.builder().build());

        PipelineTopology consumerTopology = capture(scene, hdr, consumers);
        PipelineTopology requestTopology = capture(scene, hdr, withRequest);

        assertEquals(consumerTopology, requestTopology,
                "adaptive sampling and VRS are in-pass policies; they must not add a graph pass "
                        + "or change the generation key by themselves");
        assertTrue(withRequest.adaptiveSampling().active());
        assertTrue(withRequest.variableRateShading().active());
    }

    @Test
    void pipelinePolicyRejectsAlbedoSurfaceWithoutPbrOrHdr() {
        Scene scene = new Scene(new Camera());
        RenderSettings hdr = hdr(AntiAliasingMode.NONE);
        PostProcessSettings ssgi = PostProcessSettings.builder()
                .ssgi(SsgiSettings.defaults()).build();

        IllegalStateException noPbr = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(capture(scene, hdr, ssgi), false).validate());
        assertTrue(noPbr.getMessage().contains("metallic-roughness PBR"),
                noPbr.getMessage());

        IllegalStateException noHdr = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(
                        capture(scene, ldr(), ssgi), false).validate());
        assertTrue(noHdr.getMessage().contains("HDR"), noHdr.getMessage());
    }

    @Test
    void pipelineCapabilitySeamDrivesTheVrsFactWithoutGl() {
        RenderPipeline pipeline = pipeline();
        try {
            pipeline.variableRateShadingCapabilitySupplier(
                    VariableRateShadingCapability::unavailable);
            assertFalse(pipeline.featureFacts().variableRateShadingAvailable());

            VariableRateShadingCapability supported = VariableRateShadingCapability.evaluate(
                    true, true, true, false, false, false, 16, 16, 16, 4);
            pipeline.variableRateShadingCapabilitySupplier(() -> supported);
            assertTrue(pipeline.featureFacts().variableRateShadingAvailable());

            RenderFeatureSettings vrs = hdrBuilder()
                    .postProcess(PostProcessSettings.builder()
                            .subsurfaceScattering(
                                    SubsurfaceScatteringSettings.builder().build())
                            .variableRateShading(
                                    VariableRateShadingSettings.builder().build())
                            .build())
                    .build();
            assertTrue(RenderFeaturePolicy.resolve(vrs, pipeline.featureFacts())
                    .variableRateShading());

            pipeline.variableRateShadingCapabilitySupplier(
                    VariableRateShadingCapability::unavailable);
            assertFalse(RenderFeaturePolicy.resolve(vrs, pipeline.featureFacts())
                    .variableRateShading());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void activityDetailRemainsSourceCompatibleForExistingCallers() {
        RenderFeatureActivity legacy = new RenderFeatureActivity(
                RenderFeature.SSGI, false, RenderFeatureReason.DISABLED);
        assertEquals("", legacy.detail());
        assertEquals(RenderFeatureReason.DISABLED, legacy.reason());
    }

    private static PipelineTopology capture(Scene scene, RenderSettings settings,
                                            PostProcessSettings effects) {
        return PipelineTopology.capture(scene, settings, effects, 64, 48, false, false,
                DirectionalCascadeSettings.disabled());
    }

    private static RenderPipeline pipeline() {
        return new RenderPipeline(window(), new Scene(new Camera()), null,
                hdr(AntiAliasingMode.NONE));
    }

    private static RenderWindow window() {
        return new RenderWindow() {
            @Override
            public int width() {
                return 64;
            }

            @Override
            public int height() {
                return 48;
            }
        };
    }

    private static RenderFeaturePolicy.Facts facts(boolean pbr, boolean nvAvailable) {
        return new RenderFeaturePolicy.Facts(pbr, true, true, true, pbr,
                false, false, false, false, nvAvailable);
    }

    private static RenderFeatureActivity activity(RenderFeaturePolicy.Resolved resolved,
                                                  RenderFeature feature) {
        return resolved.activities().stream()
                .filter(activity -> activity.feature() == feature)
                .findFirst().orElseThrow();
    }

    private static RenderFeatureSettings.Builder hdrBuilder() {
        return RenderFeatureSettings.builder().render(hdr(AntiAliasingMode.NONE));
    }

    private static RenderSettings hdr(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static RenderSettings ldr() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.NONE)
                .vsync(false)
                .build();
    }
}
