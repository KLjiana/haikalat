package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.AdaptiveSamplingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.DepthOfFieldSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.MotionBlurSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalDenoiseSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.vrs.VariableRateShadingSettings;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterRefractionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.PcssSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Pure dependency matrix for the unified feature request; no GL context is required. */
class RenderFeatureControlTest {
    private static final RenderFeaturePolicy.Facts ALL_TRUE = facts(true, true, true, true,
            true, false, true, false, false);
    private static final RenderFeaturePolicy.Facts NO_PBR = facts(false, true, true, true,
            false, true, true, false, false);

    @Test
    void defaultsKeepOnlyConfiguredEffectsActive() {
        var resolved = RenderFeaturePolicy.resolve(hdrBuilder().build(), NO_PBR);

        assertTrue(resolved.masterEnabled());
        assertTrue(resolved.directLighting());
        assertFalse(resolved.imageBasedLighting(), "no PBR material consumes IBL");
        assertTrue(resolved.directionalShadows());
        assertTrue(resolved.pointShadows());
        assertTrue(resolved.spotShadows());
        assertFalse(resolved.volumetricFog());
        assertFalse(resolved.sky());
        assertFalse(resolved.bloom());
        assertFalse(resolved.colorGrading());
        assertTrue(resolved.antiAliasing(), "hdrBuilder enables TAA");
        assertTrue(resolved.toneMapping(), "hdrBuilder enables ACES");
        assertEquals(RenderFeatureReason.NO_PBR_MATERIAL, activity(resolved,
                RenderFeature.IMAGE_BASED_LIGHTING).reason());
        assertEquals(RenderFeatureReason.DISABLED, activity(resolved,
                RenderFeature.VOLUMETRIC_FOG).reason());
    }

    @Test
    void masterPauseKeepsRequestedChildrenButMakesEverythingInactive() {
        RenderFeatureSettings requested = hdrBuilder()
                .enabled(false)
                .directionalShadows(true)
                .pointShadows(true)
                .volumetricFog(volumetric())
                .build();

        var resolved = RenderFeaturePolicy.resolve(requested, ALL_TRUE);

        assertFalse(resolved.masterEnabled());
        assertFalse(resolved.directLighting());
        assertFalse(resolved.imageBasedLighting());
        assertFalse(resolved.directionalShadows());
        assertFalse(resolved.pointShadows());
        assertFalse(resolved.spotShadows());
        assertFalse(resolved.volumetricFog());
        assertFalse(resolved.sky());
        assertTrue(requested.directionalShadows(), "requested children are preserved");
        assertEquals(RenderFeatureReason.DISABLED, activity(resolved, RenderFeature.MASTER).reason());
        for (RenderFeature feature : List.of(RenderFeature.DIRECT_LIGHTING,
                RenderFeature.SHADOW_DIRECTIONAL, RenderFeature.VOLUMETRIC_FOG)) {
            assertEquals(RenderFeatureReason.PAUSED_BY_MASTER, activity(resolved, feature).reason(),
                    feature.name());
            assertFalse(activity(resolved, feature).active(), feature.name());
        }
    }

    @Test
    void waterSurfaceActivityFollowsMasterPauseAndRejectsUnsupportedModes() {
        WaterSurfaceSettings water = WaterSurfaceSettings.builder().enabled(true).build();
        var resolved = RenderFeaturePolicy.resolve(
                hdrBuilder().waterSurface(water).build(), NO_PBR);
        assertTrue(resolved.waterSurface());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(resolved, RenderFeature.WATER_SURFACE).reason());

        var paused = RenderFeaturePolicy.resolve(
                hdrBuilder().enabled(false).waterSurface(water).build(), NO_PBR);
        assertFalse(paused.waterSurface());
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(paused, RenderFeature.WATER_SURFACE).reason());

        RenderFeatureSettings ldr = RenderFeatureSettings.builder().waterSurface(water).build();
        assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(ldr, NO_PBR),
                "linear water absorption requires HDR tone mapping");

        RenderFeatureSettings volumetric = hdrBuilder()
                .waterSurface(water).volumetricFog(volumetric()).build();
        assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(volumetric, ALL_TRUE),
                "volumetric fog composition is not part of the first water version");
    }

    @Test
    void shadowRequestWithoutEligibleCasterIsSuppressedNotFailed() {
        var facts = facts(false, true, false, true, true, true, true, false, false);
        var requested = hdrBuilder().pointShadows(true).build();

        var resolved = RenderFeaturePolicy.resolve(requested, facts);

        assertFalse(resolved.pointShadows());
        assertEquals(RenderFeatureReason.NO_ELIGIBLE_LIGHT,
                activity(resolved, RenderFeature.SHADOW_POINT).reason());
        assertTrue(resolved.directionalShadows());
        assertTrue(resolved.spotShadows());
    }

    @Test
    void iblReportsMissingPbrMaterialAndMissingEnvironmentSeparately() {
        var noMaterial = RenderFeaturePolicy.resolve(hdrBuilder().build(), NO_PBR);
        assertEquals(RenderFeatureReason.NO_PBR_MATERIAL,
                activity(noMaterial, RenderFeature.IMAGE_BASED_LIGHTING).reason());

        // A PBR scene must have a borrowed environment, so the request is
        // rejected before IBL can be resolved; NO_ENVIRONMENT is defensive for
        // future PBR-less producers.
        assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(hdrBuilder().build(),
                        facts(true, true, true, true, false, false, true, false, false)));
    }

    @Test
    void invalidCombinationsFailBeforeCandidatePreparation() {
        assertMessage("volumetric fog replaces analytic fog", hdrBuilder()
                .volumetricFog(volumetric())
                .postProcess(PostProcessSettings.builder()
                        .fog(FogSettings.builder().enabled(true).build()).build())
                .build(), ALL_TRUE);
        assertMessage("GTAO requires", hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .gtao(GtaoSettings.defaults().withEnabled(true)).build())
                .build(), NO_PBR);
        assertMessage("requires an explicit borrowed PbrEnvironment", hdrBuilder().build(),
                facts(true, true, true, true, false, false, true, false, false));
        assertThrows(IllegalStateException.class, () -> RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.NONE)
                .bloomSettings(BloomSettings.builder().enabled(true).build()).build());
        assertMessage("custom HDR VFX must use hdrVfxWithFog", hdrBuilder()
                .volumetricFog(volumetric())
                .hdrVfx(true)
                .build(), facts(true, true, true, true, true, true, false, false, false));
        assertMessage("contact shadows require at least one metallic-roughness PBR material",
                contactRequest(true), NO_PBR);
        assertMessage("contact shadows require an enabled directional shadow caster",
                contactRequest(false), ALL_TRUE);
        assertMessage("contact shadows require an enabled directional shadow caster",
                contactRequest(true), facts(true, false, true, true, true, true, true, false, false));
        assertMessage("cloud shadows require at least one metallic-roughness PBR material",
                cloudRequest(true), NO_PBR);
        assertMessage("cloud shadows require an enabled directional shadow caster",
                cloudRequest(false), ALL_TRUE);
        assertMessage("cloud shadows require an enabled directional shadow caster",
                cloudRequest(true), facts(true, false, true, true, true, true, true, false, false));
        assertMessage("PCSS requires at least one metallic-roughness PBR material",
                pcssRequest(true), NO_PBR);
        assertMessage("PCSS requires an enabled directional shadow caster",
                pcssRequest(false), ALL_TRUE);
        assertMessage("PCSS requires an enabled directional shadow caster",
                pcssRequest(true), facts(true, false, true, true, true, true, true, false, false));
    }

    @Test
    void screenSpaceReflectionsRequireHdrAndPbrMaterials() {
        PostProcessSettings ssr = PostProcessSettings.builder()
                .ssr(SsrSettings.defaults()).build();

        assertMessage("screen-space reflections require HDR tone mapping",
                RenderFeatureSettings.builder().hdrVfx(false).postProcess(ssr).build(),
                NO_PBR);
        assertMessage("screen-space reflections require at least one metallic-roughness PBR material",
                hdrBuilder().postProcess(ssr).build(), NO_PBR);
        var resolved = RenderFeaturePolicy.resolve(
                hdrBuilder().postProcess(ssr).build(), ALL_TRUE);
        assertTrue(resolved.masterEnabled(),
                "a valid SSR request must resolve with HDR, PBR materials and an environment");
    }

    @Test
    void smaaIsAnHdrOnlyAntiAliasingRequestInTheUnifiedControl() {
        RenderFeatureSettings settings = hdrBuilder().render(RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.SMAA)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build()).build();

        var resolved = RenderFeaturePolicy.resolve(settings, NO_PBR);
        assertTrue(resolved.antiAliasing());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(resolved, RenderFeature.ANTI_ALIASING).reason());

        assertThrows(IllegalStateException.class, () -> RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.SMAA)
                .toneMappingMode(ToneMappingMode.NONE)
                .build());
    }

    @Test
    void projectedDecalRequestIsOptionalAndReportsWhyItCannotRun() {
        ProjectedDecalSettings request = new ProjectedDecalSettings(
                true, 0.6f, 0.02f, 0.5f, false, true, 8);
        assertEquals(ProjectedDecalSettings.disabled(),
                RenderFeatureSettings.defaults().projectedDecals(),
                "the default request must stay an exact no-op");
        assertThrows(NullPointerException.class,
                () -> RenderFeatureSettings.builder().projectedDecals(null));

        assertFalse(RenderFeaturePolicy.resolve(hdrBuilder().build(),
                        decalFacts(true, true)).projectedDecals());
        assertEquals(RenderFeatureReason.DISABLED,
                activity(RenderFeaturePolicy.resolve(hdrBuilder().build(),
                        decalFacts(true, true)), RenderFeature.PROJECTED_DECALS).reason());

        var active = RenderFeaturePolicy.resolve(
                hdrBuilder().projectedDecals(request).build(), decalFacts(true, true));
        assertTrue(active.projectedDecals());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(active, RenderFeature.PROJECTED_DECALS).reason());
        assertEquals(request, hdrBuilder().projectedDecals(request).build().projectedDecals(),
                "the request stays in the effective snapshot");

        var paused = RenderFeaturePolicy.resolve(
                hdrBuilder().enabled(false).projectedDecals(request).build(),
                decalFacts(true, true));
        assertFalse(paused.projectedDecals());
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(paused, RenderFeature.PROJECTED_DECALS).reason());

        var missingSource = RenderFeaturePolicy.resolve(
                hdrBuilder().projectedDecals(request).build(), decalFacts(false, true));
        assertFalse(missingSource.projectedDecals());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(missingSource, RenderFeature.PROJECTED_DECALS).reason());
        assertTrue(activity(missingSource, RenderFeature.PROJECTED_DECALS).detail()
                        .contains("registered projected-decal renderer/source"),
                activity(missingSource, RenderFeature.PROJECTED_DECALS).detail());

        var missingDepth = RenderFeaturePolicy.resolve(
                hdrBuilder().projectedDecals(request).build(), decalFacts(true, false));
        assertFalse(missingDepth.projectedDecals());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(missingDepth, RenderFeature.PROJECTED_DECALS).reason());
        assertTrue(activity(missingDepth, RenderFeature.PROJECTED_DECALS).detail()
                        .contains("single-sample scene depth"),
                activity(missingDepth, RenderFeature.PROJECTED_DECALS).detail());

        var ldr = RenderFeaturePolicy.resolve(
                RenderFeatureSettings.builder().projectedDecals(request).build(),
                decalFacts(false, true, true));
        assertFalse(ldr.projectedDecals());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(ldr, RenderFeature.PROJECTED_DECALS).reason());
        assertTrue(activity(ldr, RenderFeature.PROJECTED_DECALS).detail()
                        .contains("HDR tone mapping"),
                activity(ldr, RenderFeature.PROJECTED_DECALS).detail());
    }

    private static RenderFeaturePolicy.Facts decalFacts(boolean sourcePresent,
                                                        boolean depthAvailable) {
        return decalFacts(true, sourcePresent, depthAvailable);
    }

    private static RenderFeaturePolicy.Facts decalFacts(boolean pbr, boolean sourcePresent,
                                                        boolean depthAvailable) {
        return new RenderFeaturePolicy.Facts(pbr, true, true, true, true,
                false, false, false, false, true, false, false, true, false, false,
                sourcePresent, depthAvailable);
    }

    @Test
    void builderRoundTripKeepsEveryComponent() {
        RenderFeatureSettings original = hdrBuilder()
                .enabled(false)
                .directLighting(false)
                .imageBasedLighting(false)
                .directionalShadows(false)
                .pointShadows(false)
                .spotShadows(false)
                .hdrVfx(false)
                .preview(false)
                .projectedDecals(new ProjectedDecalSettings(
                        true, 0.6f, 0.02f, 0.5f, false, true, 8))
                .build();

        assertEquals(original, original.toBuilder().build());
        assertEquals(original.render(), original.toBuilder().build().render());
        assertEquals(original.projectedDecals(),
                original.toBuilder().build().projectedDecals());
    }

    // ---- v0.28.1 low-resolution foundation: renderScale rejection matrix ----

    /**
     * One requested composition that assumes full-resolution scene data.  The
     * factory is applied at the 1.0 control and at a reduced scale, so the same
     * request exercises both the no-op and the fail-closed branch.
     */
    private record RenderScaleCase(RenderScalePolicy.Feature feature,
                                   RenderFeaturePolicy.Facts facts,
                                   Function<Float, RenderFeatureSettings> request) {
        @Override
        public String toString() {
            return feature.code();
        }
    }

    private static final RenderFeaturePolicy.Facts SCALE_FACTS =
            new RenderFeaturePolicy.Facts(true, true, true, true, true, true, true, true,
                    true, true, true, false, true, true, false, true, true);
    private static final RenderFeaturePolicy.Facts SCALE_FACTS_WITH_SOURCE =
            new RenderFeaturePolicy.Facts(true, true, true, true, true, true, true, true,
                    true, true, true, false, true, true, true, true, true);
    /**
     * LDR is a legal full-resolution configuration, so its 1.0 control cannot
     * carry the PBR/HDR-VFX facts that independently require HDR.
     */
    private static final RenderFeaturePolicy.Facts SCALE_FACTS_LDR =
            new RenderFeaturePolicy.Facts(false, true, true, true, true, false, true, true,
                    true, true, true, false, true, true, false, true, true);

    private static Stream<RenderScaleCase> renderScaleCases() {
        return Stream.of(
                new RenderScaleCase(RenderScalePolicy.Feature.MSAA, SCALE_FACTS,
                        scale -> base(scale).render(hdrRender(scale)
                                .antiAliasingMode(AntiAliasingMode.MSAA).msaaSamples(4)
                                .build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.TAA, SCALE_FACTS,
                        scale -> base(scale).render(hdrRender(scale)
                                .antiAliasingMode(AntiAliasingMode.TAA)
                                .build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.LOW_RESOLUTION_LDR, SCALE_FACTS_LDR,
                        scale -> RenderFeatureSettings.builder()
                                .render(RenderSettings.builder()
                                        .antiAliasingMode(AntiAliasingMode.NONE)
                                        .vsync(false).renderScale(scale).build())
                                .build()),
                new RenderScaleCase(RenderScalePolicy.Feature.BLOOM, SCALE_FACTS,
                        scale -> base(scale).render(hdrRender(scale)
                                .bloomSettings(BloomSettings.builder().enabled(true)
                                        .maxLevels(4).build())
                                .build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.AUTO_EXPOSURE, SCALE_FACTS,
                        scale -> base(scale).render(hdrRender(scale)
                                .exposureMode(ExposureMode.AUTO).build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.SSR, SCALE_FACTS,
                        scale -> base(scale).postProcess(PostProcessSettings.builder()
                                .ssr(SsrSettings.defaults()).build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.SSGI, SCALE_FACTS,
                        scale -> base(scale).postProcess(PostProcessSettings.builder()
                                .ssgi(SsgiSettings.defaults()).build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.GTAO, SCALE_FACTS,
                        scale -> base(scale).postProcess(PostProcessSettings.builder()
                                .gtao(GtaoSettings.defaults().withEnabled(true)).build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.TEMPORAL_DENOISE, SCALE_FACTS,
                        scale -> base(scale).postProcess(PostProcessSettings.builder()
                                .temporalDenoise(TemporalDenoiseSettings.defaults())
                                .build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.DEPTH_OF_FIELD, SCALE_FACTS,
                        scale -> base(scale).postProcess(PostProcessSettings.builder()
                                .depthOfField(DepthOfFieldSettings.of(4.0f, 2.8f, 8.0f))
                                .build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.MOTION_BLUR, SCALE_FACTS,
                        scale -> base(scale).postProcess(PostProcessSettings.builder()
                                .motionBlur(MotionBlurSettings.of(0.5f, 16.0f)).build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.SUBSURFACE_SCATTERING, SCALE_FACTS,
                        scale -> base(scale).postProcess(PostProcessSettings.builder()
                                .subsurfaceScattering(SubsurfaceScatteringSettings.ofSkin())
                                .build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.ADAPTIVE_SAMPLING, SCALE_FACTS,
                        scale -> base(scale).postProcess(PostProcessSettings.builder()
                                .adaptiveSampling(AdaptiveSamplingSettings.defaults())
                                .build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.VARIABLE_RATE_SHADING, SCALE_FACTS,
                        scale -> base(scale).postProcess(PostProcessSettings.builder()
                                .variableRateShading(VariableRateShadingSettings.disabled()
                                        .withEnabled(true))
                                .build()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.PROJECTED_DECALS, SCALE_FACTS,
                        scale -> base(scale).projectedDecals(new ProjectedDecalSettings(
                                true, 0.6f, 0.02f, 0.5f, false, true, 8)).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.WATER_SURFACE, SCALE_FACTS,
                        scale -> base(scale).waterSurface(activeWater()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.WATER_REFRACTION, SCALE_FACTS,
                        scale -> base(scale).waterSurface(activeWater())
                                .waterRefraction(WaterRefractionSettings.disabled()
                                        .withEnabled(true))
                                .build()),
                new RenderScaleCase(RenderScalePolicy.Feature.CONTACT_SHADOWS, SCALE_FACTS,
                        scale -> base(scale).directionalShadows(true)
                                .directionalShadowMap(activeContactShadows()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.CAUSTICS, SCALE_FACTS,
                        scale -> base(scale).waterSurface(activeWater())
                                .caustics(CausticsSettings.disabled().withEnabled(true))
                                .build()),
                new RenderScaleCase(RenderScalePolicy.Feature.PLANAR_REFLECTIONS, SCALE_FACTS,
                        scale -> base(scale).planarReflections(
                                PlanarReflectionSettings.disabled().withEnabled(true)).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.DDGI, SCALE_FACTS,
                        scale -> base(scale).ddgi(DdgiSettings.disabled().withEnabled(true))
                                .build()),
                new RenderScaleCase(RenderScalePolicy.Feature.VOXEL_CONE_TRACING, SCALE_FACTS,
                        scale -> base(scale).vct(VctSettings.enabled(32, 8.0f)).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.HAIR_STRANDS, SCALE_FACTS,
                        scale -> base(scale)
                                .hairStrands(HairStrandSettings.disabled().withEnabled(true))
                                .build()),
                new RenderScaleCase(RenderScalePolicy.Feature.VOLUMETRIC_CLOUDS, SCALE_FACTS,
                        scale -> base(scale).volumetricClouds(
                                VolumetricCloudSettings.disabled().withEnabled(true)).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.VOLUMETRIC_SMOKE, SCALE_FACTS,
                        scale -> base(scale)
                                .smokeSimulation(SmokeSimulationSettings.disabled()
                                        .withEnabled(true))
                                .smokeRender(SmokeRenderSettings.disabled().withEnabled(true))
                                .build()),
                new RenderScaleCase(RenderScalePolicy.Feature.VOLUMETRIC_PARTICLES, SCALE_FACTS,
                        scale -> base(scale).volumetricParticles(
                                VolumetricParticleSettings.disabled().withEnabled(true)).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.VOLUMETRIC_FOG, SCALE_FACTS,
                        scale -> base(scale).volumetricFog(volumetric()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.WEIGHTED_OIT, SCALE_FACTS,
                        scale -> base(scale).weightedOit(WeightedOitSettings.defaults()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.STOCHASTIC_TRANSPARENCY, SCALE_FACTS,
                        scale -> base(scale).stochasticTransparency(
                                StochasticTransparencySettings.defaults()).build()),
                new RenderScaleCase(RenderScalePolicy.Feature.TEMPORAL_UPSCALING_SOURCE,
                        SCALE_FACTS_WITH_SOURCE, scale -> base(scale).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("renderScaleCases")
    void reducedRenderScaleRejectsFullResolutionCompositionsAndScaleOneDoesNot(
            RenderScaleCase testCase) {
        RenderFeatureSettings full = testCase.request().apply(1.0f);
        assertDoesNotThrow(() -> RenderFeaturePolicy.resolve(full, testCase.facts()),
                "renderScale 1.0 must keep today's behaviour for " + testCase.feature().code());

        RenderFeatureSettings reduced = testCase.request().apply(0.5f);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(reduced, testCase.facts()),
                testCase.feature().code());
        assertTrue(failure.getMessage().contains(testCase.feature().code()),
                () -> "expected reason '" + testCase.feature().code() + "' but got: "
                        + failure.getMessage());
    }

    @Test
    void renderScaleMatrixCoversEverySettingsExpressibleFeature() {
        Set<RenderScalePolicy.Feature> covered = renderScaleCases()
                .map(RenderScaleCase::feature).collect(Collectors.toSet());
        Set<RenderScalePolicy.Feature> expected = EnumSet.complementOf(EnumSet.of(
                RenderScalePolicy.Feature.UNDERWATER_VOLUME,
                RenderScalePolicy.Feature.EMBEDDED_TARGET));
        assertEquals(expected, covered,
                "every settings-expressible full-resolution composition needs a matrix case");
    }

    @Test
    void renderScaleReasonCodesAreDistinctAndDocumented() {
        assertEquals(32, RenderScalePolicy.Feature.values().length,
                "the documented matrix must list every rejected composition");
        Set<String> codes = Arrays.stream(RenderScalePolicy.Feature.values())
                .map(RenderScalePolicy.Feature::code).collect(Collectors.toSet());
        assertEquals(RenderScalePolicy.Feature.values().length, codes.size(),
                "each rejection needs its own stable reason code");
    }

    private static RenderSettings.Builder hdrRender(float renderScale) {
        return RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES).vsync(false).renderScale(renderScale);
    }

    private static RenderFeatureSettings.Builder base(float renderScale) {
        return RenderFeatureSettings.builder().render(hdrRender(renderScale).build());
    }

    private static WaterSurfaceSettings activeWater() {
        return WaterSurfaceSettings.builder().enabled(true).build();
    }

    private static DirectionalShadowMap activeContactShadows() {
        ContactShadowSettings contact = new ContactShadowSettings(true, 1.0f, 0.5f, 8, 0.8f);
        return new DirectionalShadowMap(new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.003f,
                contact));
    }

    private static void assertMessage(String fragment, RenderFeatureSettings settings,
                                      RenderFeaturePolicy.Facts facts) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(settings, facts));
        assertTrue(failure.getMessage().contains(fragment),
                () -> "expected message containing '" + fragment + "' but got: "
                        + failure.getMessage());
    }

    private static RenderFeatureActivity activity(RenderFeaturePolicy.Resolved resolved,
                                                 RenderFeature feature) {
        return resolved.activities().stream().filter(a -> a.feature() == feature).findFirst()
                .orElseThrow();
    }

    private static RenderFeaturePolicy.Facts facts(boolean pbr, boolean directional,
                                                   boolean point, boolean spot, boolean env,
                                                   boolean vfxRecorder, boolean fogAware,
                                                   boolean fogOptOut, boolean previewSelected) {
        return new RenderFeaturePolicy.Facts(pbr, directional, point, spot, env, vfxRecorder,
                fogAware, fogOptOut, previewSelected, directional);
    }

    private static RenderFeatureSettings.Builder hdrBuilder() {
        return RenderFeatureSettings.builder().render(RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.TAA)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build());
    }

    private static RenderFeatureSettings contactRequest(boolean directionalShadows) {
        ContactShadowSettings contact = new ContactShadowSettings(true, 1.0f, 0.5f, 8, 0.8f);
        return hdrBuilder()
                .directionalShadows(directionalShadows)
                .directionalShadowMap(new DirectionalShadowMap(
                        new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.003f, contact)))
                .build();
    }

    private static RenderFeatureSettings cloudRequest(boolean directionalShadows) {
        CloudShadowSettings clouds = new CloudShadowSettings(true, 0.6f, 0.3f, 0.08f, 1.5f, 0.7f);
        return hdrBuilder()
                .directionalShadows(directionalShadows)
                .directionalShadowMap(new DirectionalShadowMap(
                        new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.003f,
                                ContactShadowSettings.disabled(), clouds)))
                .build();
    }

    private static RenderFeatureSettings pcssRequest(boolean directionalShadows) {
        PcssSettings pcss = PcssSettings.disabled().withEnabled(true);
        return hdrBuilder()
                .directionalShadows(directionalShadows)
                .directionalShadowMap(new DirectionalShadowMap(
                        new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.003f,
                                ContactShadowSettings.disabled(), CloudShadowSettings.disabled(),
                                pcss)))
                .build();
    }

    private static VolumetricFogSettings volumetric() {
        return new VolumetricFogSettings(true, 80,
                VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.vacuum(), List.of(), 0, true, 0.9f, 1, new Vector3f());
    }
}
