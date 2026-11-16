package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.GraphRecordingFixture;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsPass;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pipeline.PipelineGeneration;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionPass;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferRequirements;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencyPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticlePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricPassBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalPass;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production wiring contract for the two opt-in composition passes.
 *
 * <p>The graph cases use the real {@link RenderGraph} compiler with the recording
 * fixture (no GL allocation), so the declared order, colour aliasing and the
 * absent shared-depth attachment are the actual production declarations rather
 * than a duplicated test topology.</p>
 */
class RenderPipelineWiringTest {
    private static final RenderFeaturePolicy.Facts NO_PBR = new RenderFeaturePolicy.Facts(
            false, true, true, true, false, false, false, false, false, true);

    @Test
    void disabledSettingsLeaveTheTopologyAndCompositionUnchanged() {
        Scene scene = new Scene(new Camera());
        PipelineTopology baseline = PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());

        PipelineTopology disabled = PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled());

        assertEquals(baseline, disabled,
                "disabled particle/stochastic settings must not change the topology key");
        assertFalse(disabled.volumetricParticles());
        assertFalse(disabled.stochasticTransparency());
        assertEquals(VolumetricPassBuilder.COMPOSITE_PASS,
                RenderPipeline.volumetricTransparentColorSource(false),
                "the disabled particle bridge keeps the fog composite as colour source");
    }

    @Test
    void activeRequestsWithoutTheirCompositionInputsStayUnavailable() {
        Scene scene = new Scene(new Camera());
        // Particle requested without fog, and stochastic requested under TAA: both
        // have no compatible composition and must not change the topology key.
        PipelineTopology baseline = PipelineTopology.capture(scene, hdr(AntiAliasingMode.TAA),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());
        PipelineTopology unavailable = PipelineTopology.capture(scene, hdr(AntiAliasingMode.TAA),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                particles(), StochasticTransparencySettings.defaults());

        assertEquals(baseline, unavailable);
        assertFalse(unavailable.volumetricParticles());
        assertFalse(unavailable.stochasticTransparency());
    }

    @Test
    void particleCompositionRunsAfterTheFogCompositeAndBeforeTransparency() {
        RenderSettings settings = hdr(AntiAliasingMode.NONE);
        Scene scene = new Scene(new Camera());
        PipelineTopology topology = PipelineTopology.capture(scene, settings,
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(),
                volumetric(), true, true, true, WaterSurfaceSettings.disabled(),
                particles(), StochasticTransparencySettings.disabled());
        assertTrue(topology.volumetricParticles());

        try (RenderGraph graph = GraphRecordingFixture.graph(64, 48)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .createDepthTexture("scene.depth")
                    .execute((res, cmd) -> { });
            graph.addPass(VolumetricPassBuilder.COMPOSITE_PASS)
                    .createColors(List.of(VolumetricPassBuilder.COMPOSITE_COLOR,
                            VolumetricPassBuilder.OPAQUE_REACTIVE),
                            List.of(RenderFormat.RGBA16F, RenderFormat.R8))
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute((res, cmd) -> { });
            RenderPipeline.addVolumetricParticlePass(graph,
                    VolumetricPassBuilder.COMPOSITE_PASS, PostProcessTargets.GEOMETRY_PASS,
                    (res, cmd) -> { });
            String transparentSource = RenderPipeline.volumetricTransparentColorSource(true);
            graph.addPass(VolumetricPassBuilder.TRANSPARENT_PASS)
                    .shareColorTextures(List.of(VolumetricPassBuilder.SCENE_COLOR),
                            List.of(RenderFormat.RGBA16F), transparentSource, 1)
                    .shareDepthTexture("scene.depth", PostProcessTargets.GEOMETRY_PASS)
                    .noClear()
                    .execute((res, cmd) -> { });
            graph.compile();

            List<String> order = graph.description().executionOrder();
            assertTrue(order.indexOf(VolumetricPassBuilder.COMPOSITE_PASS)
                            < order.indexOf(VolumetricParticlePass.PASS_NAME),
                    "particles must run after the fog colour composite: " + order);
            assertTrue(order.indexOf(VolumetricParticlePass.PASS_NAME)
                            < order.indexOf(VolumetricPassBuilder.TRANSPARENT_PASS),
                    "transparency/HDR VFX must consume the particle output: " + order);
            assertTrue(order.indexOf(PostProcessTargets.GEOMETRY_PASS)
                    < order.indexOf(VolumetricPassBuilder.COMPOSITE_PASS));

            var particle = pass(graph, VolumetricParticlePass.PASS_NAME);
            assertTrue(particle.directDependencies().contains(
                    VolumetricPassBuilder.COMPOSITE_PASS));
            assertTrue(particle.directDependencies().contains(
                    PostProcessTargets.GEOMETRY_PASS));
            assertEquals("RGBA16F", particle.colorAttachments().getFirst().format());
            assertEquals(VolumetricParticlePass.COLOR_TEXTURE,
                    particle.colorAttachments().getFirst().logicalName());
            assertTrue(graph.matchesColorStorage(VolumetricPassBuilder.TRANSPARENT_PASS,
                            VolumetricParticlePass.PASS_NAME, List.of(0)),
                    "the transparency replay must alias the particle-owned colour");
        }
    }

    @Test
    void stochasticCompositionSharesSceneColourAndHasNoSharedDepthAttachment() {
        try (RenderGraph graph = GraphRecordingFixture.graph(64, 48)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .createDepthTexture("scene.depth")
                    .execute((res, cmd) -> { });
            RenderPipeline.addStochasticTransparencyPass(graph,
                    PostProcessTargets.SCENE_COLOR, PostProcessTargets.GEOMETRY_PASS,
                    RenderFormat.RGBA16F, 1, PostProcessTargets.GEOMETRY_PASS,
                    (res, cmd) -> { });
            graph.addPass(PostProcessTargets.TONE_MAPPING_PASS)
                    .computeOnly()
                    .dependsOn(StochasticTransparencyPass.PASS_NAME)
                    .execute((res, cmd) -> { });
            graph.compile();

            List<String> order = graph.description().executionOrder();
            assertTrue(order.indexOf(PostProcessTargets.GEOMETRY_PASS)
                    < order.indexOf(StochasticTransparencyPass.PASS_NAME));
            assertTrue(order.indexOf(StochasticTransparencyPass.PASS_NAME)
                            < order.indexOf(PostProcessTargets.TONE_MAPPING_PASS),
                    "the composite must stay before the post-process colour chain: " + order);

            var stochastic = pass(graph, StochasticTransparencyPass.PASS_NAME);
            assertNull(stochastic.depthAttachment(),
                    "the stochastic composite must not attach or write the shared opaque depth");
            assertEquals(PostProcessTargets.SCENE_COLOR,
                    stochastic.colorAttachments().getFirst().logicalName());
            assertTrue(stochastic.directDependencies().contains(
                    PostProcessTargets.GEOMETRY_PASS));
            assertTrue(graph.matchesColorStorage(StochasticTransparencyPass.PASS_NAME,
                    PostProcessTargets.GEOMETRY_PASS, List.of(0)));
        }
    }

    @Test
    void featureControlReportsActiveUnavailableAndPausedReasons() {
        var particlesActive = RenderFeaturePolicy.resolve(hdrBuilder()
                .volumetricFog(volumetric())
                .volumetricParticles(particles()).build(), NO_PBR);
        assertTrue(particlesActive.volumetricParticles());
        assertEquals(RenderFeatureReason.ACTIVE, activity(particlesActive,
                RenderFeature.VOLUMETRIC_PARTICLES).reason());

        var particlesWithoutFog = RenderFeaturePolicy.resolve(
                hdrBuilder().volumetricParticles(particles()).build(), NO_PBR);
        assertFalse(particlesWithoutFog.volumetricParticles());
        assertEquals(RenderFeatureReason.UNAVAILABLE, activity(particlesWithoutFog,
                RenderFeature.VOLUMETRIC_PARTICLES).reason());

        var particlesUnderMsaa = RenderFeaturePolicy.resolve(hdrBuilder()
                .render(hdr(AntiAliasingMode.MSAA))
                .volumetricFog(volumetric())
                .volumetricParticles(particles()).build(), NO_PBR);
        assertFalse(particlesUnderMsaa.volumetricParticles());
        assertEquals(RenderFeatureReason.UNAVAILABLE, activity(particlesUnderMsaa,
                RenderFeature.VOLUMETRIC_PARTICLES).reason());

        var stochasticActive = RenderFeaturePolicy.resolve(hdrBuilder()
                .stochasticTransparency(StochasticTransparencySettings.defaults()).build(),
                NO_PBR);
        assertTrue(stochasticActive.stochasticTransparency());
        assertEquals(RenderFeatureReason.ACTIVE, activity(stochasticActive,
                RenderFeature.STOCHASTIC_TRANSPARENCY).reason());

        var stochasticUnderTaa = RenderFeaturePolicy.resolve(hdrBuilder()
                .render(hdr(AntiAliasingMode.TAA))
                .stochasticTransparency(StochasticTransparencySettings.defaults()).build(),
                NO_PBR);
        assertFalse(stochasticUnderTaa.stochasticTransparency());
        assertEquals(RenderFeatureReason.UNAVAILABLE, activity(stochasticUnderTaa,
                RenderFeature.STOCHASTIC_TRANSPARENCY).reason());

        var paused = RenderFeaturePolicy.resolve(hdrBuilder()
                .enabled(false)
                .volumetricFog(volumetric())
                .volumetricParticles(particles())
                .stochasticTransparency(StochasticTransparencySettings.defaults()).build(),
                NO_PBR);
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(paused, RenderFeature.VOLUMETRIC_PARTICLES).reason());
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(paused, RenderFeature.STOCHASTIC_TRANSPARENCY).reason());
        assertFalse(paused.volumetricParticles());
        assertFalse(paused.stochasticTransparency());
    }

    @Test
    void activeCompositionWithoutRequiredInputsIsRejectedAtAssemblyTime() {
        Scene scene = new Scene(new Camera());
        PipelineTopology base = PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());

        PipelineTopology particleWithoutFog = copy(base, base.sampleCount(), true,
                base.sceneSurface(), false, true, false, false, false);
        IllegalStateException fogFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(particleWithoutFog, false).validate());
        assertTrue(fogFailure.getMessage().contains("volumetric fog colour composition"));

        PipelineTopology noDepthSurface = base.sceneSurface().hasSurface()
                ? base : copy(base, 1, true,
                SceneSurfaceLayout.plan(1, SceneBufferRequirements.none(),
                        SceneBufferRequirements.none(), SceneSurfaceLayout.ReactiveMode.NONE),
                false, false, true, false, false);
        IllegalStateException depthFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(noDepthSurface, false).validate());
        assertTrue(depthFailure.getMessage().contains("shared single-sample scene depth"));

        PipelineTopology msaa = copy(base, 4, true,
                SceneSurfaceLayout.plan(4, SceneBufferRequirements.none(),
                        SceneBufferRequirements.none(), SceneSurfaceLayout.ReactiveMode.NONE),
                false, false, true, false, false);
        IllegalStateException samplesFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(msaa, false).validate());
        assertTrue(samplesFailure.getMessage().contains("stable single-sample HDR target"));

        PipelineTopology ldr = copy(base, 1, false, base.sceneSurface(), false, false, true,
                false, false);
        IllegalStateException ldrFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(ldr, false).validate());
        assertTrue(ldrFailure.getMessage().contains("HDR tone mapping"));

        PipelineTopology valid = PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(),
                volumetric(), true, true, true, WaterSurfaceSettings.disabled(),
                particles(), StochasticTransparencySettings.defaults());
        assertTrue(valid.volumetricParticles());
        assertTrue(valid.stochasticTransparency());
        new PipelineFeaturePolicy(valid, false).validate();
    }

    @Test
    void stagedSurfacesAndSettingsChangesAreTransactional() {
        RenderPipeline pipeline = new RenderPipeline(window(), new Scene(new Camera()), null,
                RenderSettings.builder().build());
        try {
            assertEquals(VolumetricParticleSettings.disabled(), pipeline.volumetricParticles());
            assertEquals(StochasticTransparencySettings.disabled(),
                    pipeline.stochasticTransparency());
            assertEquals(List.of(), pipeline.stochasticTransparencySurfaces());

            pipeline.volumetricParticles(particles());
            assertEquals(particles(), pipeline.volumetricParticles());
            pipeline.stochasticTransparency(StochasticTransparencySettings.defaults());

            OitSurface surface = OitSurface.of(new Vector3f(0.0f, 0.0f, -2.0f),
                    new Vector3f(0.5f, 0.0f, 0.0f), new Vector3f(0.0f, 0.5f, 0.0f),
                    new Vector3f(1.0f, 0.2f, 0.1f), 1.0f);
            pipeline.stochasticTransparencySurfaces(List.of(surface, surface));
            assertEquals(2, pipeline.stochasticTransparencySurfaces().size());
            assertThrows(UnsupportedOperationException.class,
                    () -> pipeline.stochasticTransparencySurfaces().add(surface),
                    "the staged set must be immutable");
            assertThrows(NullPointerException.class,
                    () -> pipeline.stochasticTransparencySurfaces(
                            java.util.Arrays.asList(surface, null)));
            assertThrows(IllegalArgumentException.class,
                    () -> pipeline.stochasticTransparencySurfaces(
                            java.util.Collections.nCopies(5000, surface)),
                    "the staged set must respect maximumSurfaces");

            // A change that the staged set cannot satisfy must fail before the
            // effective settings are replaced.
            StochasticTransparencySettings oneSurface =
                    StochasticTransparencySettings.defaults().withMaximumSurfaces(1);
            assertThrows(IllegalArgumentException.class,
                    () -> pipeline.applyStochasticTransparency(oneSurface));
            assertEquals(StochasticTransparencySettings.defaults(),
                    pipeline.stochasticTransparency(),
                    "a rejected settings change must keep the previous value");

            StochasticTransparencySettings applied =
                    StochasticTransparencySettings.defaults().withSamplesPerFrame(2);
            pipeline.applyStochasticTransparency(applied);
            assertEquals(applied, pipeline.stochasticTransparency());
            pipeline.applyVolumetricParticles(particles().withEnabled(false));
            assertFalse(pipeline.volumetricParticles().active());

            RenderFeatureSettings roundTrip = RenderFeatureSettings.defaults().toBuilder()
                    .volumetricParticles(particles())
                    .stochasticTransparency(StochasticTransparencySettings.defaults())
                    .build();
            assertEquals(particles(), roundTrip.volumetricParticles());
            assertEquals(StochasticTransparencySettings.defaults(),
                    roundTrip.stochasticTransparency());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void duplicateFogMediumIsRejectedByTheBatchPolicy() {
        VolumetricFogSettings fog = new VolumetricFogSettings(true, 32,
                VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.vacuum(),
                List.of(LocalFogVolume.box(new Vector3f(0.0f), new Vector3f(1.0f),
                        1.0f, new Vector3f(1.0f))),
                0.0f, false, 0.0f, 1337, new Vector3f());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(hdrBuilder()
                        .volumetricFog(fog)
                        .volumetricParticles(particles()).build(), NO_PBR));
        assertTrue(failure.getMessage().contains("composited twice"));
    }

    @Test
    void s3CompositionsAreDisabledByDefaultAndKeepTheTopologyUnchanged() {
        Scene scene = new Scene(new Camera());
        RenderSettings hdr = hdr(AntiAliasingMode.NONE);
        PipelineTopology baseline = PipelineTopology.capture(scene, hdr,
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());
        PipelineTopology disabled = PipelineTopology.capture(scene, hdr,
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                WeightedOitSettings.disabled(), false, PlanarReflectionSettings.disabled(),
                CausticsSettings.disabled());

        assertEquals(baseline, disabled,
                "disabled OIT/planar/caustics settings must not change the topology key");
        assertFalse(disabled.weightedOit());
        assertFalse(disabled.planarReflections());
        assertFalse(disabled.caustics());

        RenderFeatureSettings legacy = new RenderFeatureSettings(true, true, true, true, true,
                true, true, true, hdr, PostProcessSettings.defaults(),
                VolumetricFogSettings.disabled(), OutdoorEnvironmentSettings.disabled(),
                DirectionalShadowMap.defaults(), DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), ClusteredLightingSettings.defaults(),
                WaterSurfaceSettings.disabled(), VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled());
        assertEquals(WeightedOitSettings.disabled(), legacy.weightedOit());
        assertEquals(PlanarReflectionSettings.disabled(), legacy.planarReflections());
        assertEquals(CausticsSettings.disabled(), legacy.caustics());
        assertEquals(legacy, legacy.toBuilder().build(),
                "the retained 19-component constructor must round-trip through the builder");
    }

    @Test
    void oitReportsMissingSetAndRejectsHdrOrAaCombinationsAsUnavailable() {
        RenderSettings hdrNoAa = hdr(AntiAliasingMode.NONE);
        RenderFeatureSettings requested = hdrBuilder()
                .weightedOit(WeightedOitSettings.defaults()).build();

        var undeclared = RenderFeaturePolicy.resolve(requested,
                oitFacts(false, false, hdrNoAa));
        assertFalse(undeclared.weightedOit());
        assertEquals(RenderFeatureReason.NO_TRANSPARENT_SET,
                activity(undeclared, RenderFeature.WEIGHTED_OIT).reason());

        var active = RenderFeaturePolicy.resolve(requested,
                oitFacts(true, false, hdrNoAa));
        assertTrue(active.weightedOit());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(active, RenderFeature.WEIGHTED_OIT).reason());

        var taa = RenderFeaturePolicy.resolve(hdrBuilder()
                .render(hdr(AntiAliasingMode.TAA))
                .weightedOit(WeightedOitSettings.defaults()).build(),
                oitFacts(true, false, hdr(AntiAliasingMode.TAA)));
        assertFalse(taa.weightedOit());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(taa, RenderFeature.WEIGHTED_OIT).reason());

        var msaa = RenderFeaturePolicy.resolve(hdrBuilder()
                .render(hdr(AntiAliasingMode.MSAA))
                .weightedOit(WeightedOitSettings.defaults()).build(),
                oitFacts(true, false, hdr(AntiAliasingMode.MSAA)));
        assertFalse(msaa.weightedOit());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(msaa, RenderFeature.WEIGHTED_OIT).reason());

        IllegalArgumentException ldr = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(hdrBuilder()
                        .render(RenderSettings.builder().build())
                        .weightedOit(WeightedOitSettings.defaults()).build(),
                        oitFacts(true, false, RenderSettings.builder().build())));
        assertTrue(ldr.getMessage().contains("weighted OIT requires HDR"));

        var paused = RenderFeaturePolicy.resolve(hdrBuilder().enabled(false)
                .weightedOit(WeightedOitSettings.defaults()).build(),
                oitFacts(true, false, hdrNoAa));
        assertFalse(paused.weightedOit());
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(paused, RenderFeature.WEIGHTED_OIT).reason());
    }

    @Test
    void oitAndStochasticRejectTheSameTransparentSet() {
        RenderSettings hdr = hdr(AntiAliasingMode.NONE);
        RenderFeatureSettings sameSet = hdrBuilder()
                .weightedOit(WeightedOitSettings.defaults())
                .stochasticTransparency(StochasticTransparencySettings.defaults())
                .build();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(sameSet, oitFacts(true, true, hdr)));
        assertTrue(failure.getMessage().contains("same transparent surface set"));

        // Disjoint claims are the supported way to run both strategies.
        assertDoesNotThrow(() -> RenderFeaturePolicy.resolve(sameSet,
                oitFacts(true, false, hdr)));
    }

    @Test
    void planarReflectionsRequireHdrAndAValidatedPlane() {
        var active = RenderFeaturePolicy.resolve(hdrBuilder()
                .planarReflections(PlanarReflectionSettings.builder().enabled(true)
                        .planeY(0.0f).build()).build(), NO_PBR);
        assertTrue(active.planarReflections());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(active, RenderFeature.PLANAR_REFLECTIONS).reason());

        IllegalArgumentException ldr = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(RenderFeatureSettings.builder()
                        .render(RenderSettings.builder().build())
                        .planarReflections(PlanarReflectionSettings.builder().enabled(true)
                                .planeY(0.0f).build()).build(), NO_PBR));
        assertTrue(ldr.getMessage().contains("planar reflections require HDR"));

        // "Missing plane" is rejected by the settings value before wiring: the
        // canonical constructor refuses a degenerate normal.
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().enabled(true)
                        .plane(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f).build());
    }

    @Test
    void causticsRequireWaterADownwardDirectionalLightAndHdr() {
        WaterSurfaceSettings water = WaterSurfaceSettings.builder().enabled(true).build();
        CausticsSettings caustics = CausticsSettings.builder().enabled(true)
                .waterPlane(0.0f).build();

        IllegalArgumentException noWater = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(hdrBuilder().caustics(caustics).build(),
                        NO_PBR));
        assertTrue(noWater.getMessage().contains("active finite water surface"));

        var active = RenderFeaturePolicy.resolve(hdrBuilder().waterSurface(water)
                .caustics(caustics).build(), factsDownward(true));
        assertTrue(active.caustics());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(active, RenderFeature.CAUSTICS).reason());

        var noLight = RenderFeaturePolicy.resolve(hdrBuilder().waterSurface(water)
                .caustics(caustics).build(), factsDownward(false));
        assertFalse(noLight.caustics());
        assertEquals(RenderFeatureReason.NO_ELIGIBLE_LIGHT,
                activity(noLight, RenderFeature.CAUSTICS).reason());

        var msaa = RenderFeaturePolicy.resolve(hdrBuilder()
                .render(hdr(AntiAliasingMode.MSAA))
                .waterSurface(water).caustics(caustics).build(), factsDownward(true));
        assertFalse(msaa.caustics(),
                "the single-sample caustics composite is unsupported on an MSAA target");
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(msaa, RenderFeature.CAUSTICS).reason());
    }

    @Test
    void compositionOrderingPutsReflectionsAndCausticsBeforeWaterAndOitBeforePostProcess() {
        RenderSettings hdr = hdr(AntiAliasingMode.NONE);
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.directional(new Vector3f(0.0f, -1.0f, -0.2f),
                new Vector3f(1.0f), 1.0f));
        WaterSurfaceSettings water = WaterSurfaceSettings.builder().enabled(true).build();
        PipelineTopology topology = PipelineTopology.capture(scene, hdr,
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, water, VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                WeightedOitSettings.defaults(), true,
                PlanarReflectionSettings.builder().enabled(true).planeY(0.0f).build(),
                CausticsSettings.builder().enabled(true).waterPlane(0.0f).build());
        assertTrue(topology.weightedOit());
        assertTrue(topology.planarReflections());
        assertTrue(topology.caustics());

        try (RenderGraph graph = GraphRecordingFixture.graph(64, 48)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .createDepthTexture("scene.depth")
                    .execute((res, cmd) -> { });
            RenderPipeline.addPlanarReflectionPass(graph, PostProcessTargets.SCENE_COLOR,
                    PostProcessTargets.GEOMETRY_PASS, RenderFormat.RGBA16F, 1,
                    (res, cmd) -> { });
            RenderPipeline.addCausticsPass(graph, PostProcessTargets.SCENE_COLOR,
                    PostProcessTargets.GEOMETRY_PASS, RenderFormat.RGBA16F, 1,
                    "scene.depth", PostProcessTargets.GEOMETRY_PASS, (res, cmd) -> { });
            graph.addPass("WaterSurface")
                    .createColor("water.color", RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .dependsOn(PlanarReflectionPass.PASS_NAME)
                    .dependsOn(CausticsPass.PASS_NAME)
                    .execute((res, cmd) -> { });
            RenderPipeline.addWeightedOitPass(graph, "water.color", "WaterSurface",
                    RenderFormat.RGBA16F, 1, (res, cmd) -> { });
            graph.addPass(PostProcessTargets.TONE_MAPPING_PASS)
                    .computeOnly()
                    .dependsOn(WeightedOitPass.PASS_NAME)
                    .execute((res, cmd) -> { });
            graph.compile();

            List<String> order = graph.description().executionOrder();
            assertTrue(order.indexOf(PostProcessTargets.GEOMETRY_PASS)
                    < order.indexOf(PlanarReflectionPass.PASS_NAME), order.toString());
            assertTrue(order.indexOf(PlanarReflectionPass.PASS_NAME)
                    < order.indexOf("WaterSurface"),
                    "planar reflections must record before their water consumer: " + order);
            assertTrue(order.indexOf(CausticsPass.PASS_NAME)
                    < order.indexOf("WaterSurface"),
                    "caustics add light to the opaque scene before water consumes it: " + order);
            assertTrue(order.indexOf("WaterSurface") < order.indexOf(WeightedOitPass.PASS_NAME),
                    "OIT resolves over the final transparent/water colour: " + order);
            assertTrue(order.indexOf(WeightedOitPass.PASS_NAME)
                    < order.indexOf(PostProcessTargets.TONE_MAPPING_PASS), order.toString());

            assertNull(pass(graph, PlanarReflectionPass.PASS_NAME).depthAttachment(),
                    "the planar reflection pass must not attach or write the shared depth");
            assertNull(pass(graph, CausticsPass.PASS_NAME).depthAttachment(),
                    "the caustics pass must not attach or write the shared depth");
            assertNull(pass(graph, WeightedOitPass.PASS_NAME).depthAttachment(),
                    "the OIT resolve must not attach or write the shared depth");
            assertEquals(PostProcessTargets.SCENE_COLOR,
                    pass(graph, PlanarReflectionPass.PASS_NAME).colorAttachments()
                            .getFirst().logicalName());
            assertEquals(PostProcessTargets.SCENE_COLOR,
                    pass(graph, CausticsPass.PASS_NAME).colorAttachments()
                            .getFirst().logicalName());
            assertTrue(graph.matchesColorStorage(WeightedOitPass.PASS_NAME, "WaterSurface",
                    List.of(0)));
        }
    }

    @Test
    void builderTimeCompositionValidationFailsClosedBeforeBuild() {
        Scene scene = new Scene(new Camera());
        RenderPipeline pipeline = new RenderPipeline(window(), scene, null,
                hdr(AntiAliasingMode.NONE));
        try {
            // The builder accepts the value so call order does not matter; the
            // missing water dependency fails closed before any generation exists.
            pipeline.caustics(CausticsSettings.builder().enabled(true).build());
            IllegalArgumentException missingWater = assertThrows(
                    IllegalArgumentException.class, pipeline::build);
            assertTrue(missingWater.getMessage().contains("active finite water surface"));

            assertThrows(IllegalArgumentException.class,
                    () -> new RenderPipeline(window(), new Scene(new Camera()), null,
                            RenderSettings.builder().build())
                            .planarReflections(PlanarReflectionSettings.builder()
                                    .enabled(true).planeY(0.0f).build()),
                    "planar reflections require HDR");
            assertThrows(IllegalArgumentException.class,
                    () -> new RenderPipeline(window(), new Scene(new Camera()), null,
                            RenderSettings.builder().build())
                            .weightedOit(WeightedOitSettings.defaults()),
                    "weighted OIT requires HDR");
        } finally {
            pipeline.close();
        }
    }

    @Test
    void transparentStrategyStagingRejectsAnOverlappingSurface() {
        RenderPipeline pipeline = new RenderPipeline(window(), new Scene(new Camera()), null,
                hdr(AntiAliasingMode.NONE))
                .weightedOit(WeightedOitSettings.defaults())
                .stochasticTransparency(StochasticTransparencySettings.defaults());
        try {
            OitSurface surface = OitSurface.of(new Vector3f(0.0f, 0.0f, -2.0f),
                    new Vector3f(0.5f, 0.0f, 0.0f), new Vector3f(0.0f, 0.5f, 0.0f),
                    new Vector3f(1.0f, 0.2f, 0.1f), 1.0f);
            pipeline.weightedOitSurfaces(List.of(surface));
            assertEquals(List.of(surface), pipeline.weightedOitSurfaces());
            assertTrue(pipeline.weightedOitSetDeclared());
            assertThrows(IllegalArgumentException.class,
                    () -> pipeline.stochasticTransparencySurfaces(List.of(surface)),
                    "the same surface cannot be claimed by both strategies");
            assertEquals(List.of(), pipeline.stochasticTransparencySurfaces());
            // The overlap is a staging conflict, not a settings-shape conflict:
            // the stochastic set is still empty, so a scalar settings change is
            // allowed and simply leaves that strategy with no recorded draw.
            assertDoesNotThrow(() -> pipeline.applyStochasticTransparency(
                    StochasticTransparencySettings.defaults().withSamplesPerFrame(2)));
        } finally {
            pipeline.close();
        }
    }

    @Test
    void s3SettingsRollBackWhenCandidateGenerationCannotBeBuilt() throws Exception {
        Scene scene = new Scene(new Camera());
        WaterSurfaceSettings water = WaterSurfaceSettings.builder().enabled(true).build();
        RenderPipeline pipeline = new RenderPipeline(window(), scene, null,
                hdr(AntiAliasingMode.NONE))
                .waterSurface(water);
        PipelineTopology topology = PipelineTopology.capture(scene,
                hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 48, false,
                false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(),
                volumetric(), true, true, true, water,
                particles(), StochasticTransparencySettings.defaults());
        PipelineGeneration injected = new PipelineGeneration(topology);
        RenderGraph graph = GraphRecordingFixture.graph(64, 48);
        try {
            setField(injected, "graph", graph);
            setField(pipeline, "activeGeneration", injected);
            assertThrows(IllegalStateException.class,
                    () -> pipeline.applyWeightedOit(WeightedOitSettings.defaults()));
            assertEquals(WeightedOitSettings.disabled(), pipeline.weightedOit());
            assertSame(injected, pipeline.activeGenerationForTest());

            assertThrows(IllegalStateException.class,
                    () -> pipeline.applyPlanarReflections(PlanarReflectionSettings.builder()
                            .enabled(true).planeY(0.0f).build()));
            assertEquals(PlanarReflectionSettings.disabled(), pipeline.planarReflections());
            assertSame(injected, pipeline.activeGenerationForTest());

            assertThrows(IllegalStateException.class,
                    () -> pipeline.applyCaustics(CausticsSettings.builder().enabled(true)
                            .waterPlane(0.0f).build()));
            assertEquals(CausticsSettings.disabled(), pipeline.caustics());
            assertSame(injected, pipeline.activeGenerationForTest());
        } finally {
            setField(pipeline, "activeGeneration", null);
            pipeline.close();
            graph.close();
            injected.close();
        }
    }

    @Test
    void firstOitSetDeclarationPublishesACandidateOrRollsBack() throws Exception {
        Scene scene = new Scene(new Camera());
        RenderPipeline pipeline = new RenderPipeline(window(), scene, null,
                hdr(AntiAliasingMode.NONE))
                .weightedOit(WeightedOitSettings.defaults());
        PipelineTopology topology = captureTopology(scene, DirectionalCascadeSettings.disabled(),
                WeightedOitSettings.disabled());
        assertFalse(topology.weightedOit(),
                "the injected generation predates the OIT set declaration");
        PipelineGeneration injected = new PipelineGeneration(topology);
        RenderGraph graph = GraphRecordingFixture.graph(64, 48);
        try {
            setField(injected, "graph", graph);
            setField(pipeline, "activeGeneration", injected);
            OitSurface surface = OitSurface.of(new Vector3f(0.0f, 0.0f, -2.0f),
                    new Vector3f(0.5f, 0.0f, 0.0f), new Vector3f(0.0f, 0.5f, 0.0f),
                    new Vector3f(1.0f, 0.2f, 0.1f), 1.0f);
            // No GL context: the first declaration must try to publish the pass
            // and then restore the undeclared state when the candidate fails.
            assertThrows(IllegalStateException.class,
                    () -> pipeline.weightedOitSurfaces(List.of(surface)));
            assertFalse(pipeline.weightedOitSetDeclared(),
                    "a failed first declaration must roll back");
            assertEquals(List.of(), pipeline.weightedOitSurfaces());
            assertSame(injected, pipeline.activeGenerationForTest());
        } finally {
            setField(pipeline, "activeGeneration", null);
            pipeline.close();
            graph.close();
            injected.close();
        }
    }

    @Test
    void settingsRollBackWhenCandidateGenerationCannotBeBuilt() throws Exception {
        Scene scene = new Scene(new Camera());
        RenderPipeline pipeline = new RenderPipeline(window(), scene, null,
                hdr(AntiAliasingMode.NONE));
        PipelineTopology topology = PipelineTopology.capture(scene,
                hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 48, false,
                false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(),
                volumetric(), true, true, true, WaterSurfaceSettings.disabled(),
                particles(), StochasticTransparencySettings.defaults());
        PipelineGeneration injected = new PipelineGeneration(topology);
        RenderGraph graph = GraphRecordingFixture.graph(64, 48);
        try {
            setField(injected, "graph", graph);
            setField(pipeline, "activeGeneration", injected);
            // No GL context exists here, so candidate assembly fails before any
            // candidate can be published; the apply transaction must restore the
            // previous settings and keep the active generation.
            assertThrows(IllegalStateException.class,
                    () -> pipeline.applyVolumetricParticles(particles()));
            assertEquals(VolumetricParticleSettings.disabled(),
                    pipeline.volumetricParticles());
            assertSame(injected, pipeline.activeGenerationForTest());

            assertThrows(IllegalStateException.class,
                    () -> pipeline.applyStochasticTransparency(
                            StochasticTransparencySettings.defaults().withSamplesPerFrame(2)));
            assertEquals(StochasticTransparencySettings.disabled(),
                    pipeline.stochasticTransparency());
            assertSame(injected, pipeline.activeGenerationForTest());
        } finally {
            setField(pipeline, "activeGeneration", null);
            pipeline.close();
            graph.close();
            injected.close();
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static PipelineTopology captureTopology(Scene scene,
                                                    DirectionalCascadeSettings cascades,
                                                    WeightedOitSettings oit) {
        return PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false, cascades,
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(), StochasticTransparencySettings.disabled(),
                oit, false, PlanarReflectionSettings.disabled(), CausticsSettings.disabled());
    }

    private static RenderFeatureSettings.Builder hdrBuilder() {
        return RenderFeatureSettings.builder().render(hdr(AntiAliasingMode.NONE));
    }

    private static RenderFeaturePolicy.Facts oitFacts(boolean setDeclared,
                                                      boolean overlappingSet,
                                                      RenderSettings render) {
        return new RenderFeaturePolicy.Facts(false, true, true, true, false,
                false, false, false, false, true, setDeclared, overlappingSet);
    }

    private static RenderFeaturePolicy.Facts factsDownward(boolean downward) {
        return new RenderFeaturePolicy.Facts(false, true, true, true, false,
                false, false, false, false, downward, false, false);
    }

    private static RenderSettings hdr(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static VolumetricFogSettings volumetric() {
        return new VolumetricFogSettings(true, 32, VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.vacuum(), List.of(), 0.0f, false, 0.0f, 1337,
                new Vector3f());
    }

    private static VolumetricParticleSettings particles() {
        return VolumetricParticleSettings.disabled().withEnabled(true);
    }

    private static RenderFeatureActivity activity(RenderFeaturePolicy.Resolved resolved,
                                                  RenderFeature feature) {
        return resolved.activities().stream().filter(a -> a.feature() == feature).findFirst()
                .orElseThrow();
    }

    private static RenderGraph.PassDescription pass(RenderGraph graph, String name) {
        return graph.description().passes().stream()
                .filter(pass -> pass.name().equals(name)).findFirst().orElseThrow();
    }

    private static PipelineTopology copy(PipelineTopology base, int sampleCount, boolean hdr,
                                         SceneSurfaceLayout surface, boolean volumetricFog,
                                         boolean particles, boolean stochastic,
                                         boolean clouds, boolean smoke) {
        return new PipelineTopology(base.width(), base.height(), base.antiAliasingMode(),
                sampleCount, hdr, base.bloom(), base.bloomLevels(),
                base.automaticExposure(), base.colorGrading(), base.fog(),
                base.directionalShadow(), base.pointShadow(), base.spotShadow(),
                base.directionalCascadeCount(), base.directionalShadowAtlasSize(),
                base.pointShadowCapacity(), base.pointShadowResolution(),
                base.spotShadowCapacity(), base.spotShadowResolution(),
                base.pbrMaterials(), base.hdrVfx(), base.embedded(), base.gtaoEnabled(),
                base.gtaoTemporal(), base.gtaoHalfWidth(), base.gtaoHalfHeight(), surface,
                volumetricFog, base.volumetricQuality(), base.volumetricEmission(),
                base.waterSurface(), particles, stochastic, clouds, smoke,
                false, false, false, false, false, false, base.screenSpaceContactShadows());
    }

    @Test
    void projectedDecalPassDeclaresAResolvedDepthCopyAndKeepsTheSceneColourChain() {
        try (RenderGraph graph = GraphRecordingFixture.graph(64, 48)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .createDepthTexture("scene.depth")
                    .execute((res, cmd) -> { });
            RenderPipeline.addProjectedDecalPass(graph, PostProcessTargets.GEOMETRY_PASS,
                    RenderFormat.RGBA16F, 1, (res, cmd) -> { }, (res, cmd) -> { });
            graph.compile();
            var description = graph.description();
            var decal = description.passes().stream()
                    .filter(candidate -> candidate.name().equals(ProjectedDecalPass.PASS_NAME))
                    .findFirst().orElseThrow();
            var resolve = description.passes().stream()
                    .filter(candidate -> candidate.name()
                            .equals(ProjectedDecalPass.DEPTH_RESOLVE_PASS))
                    .findFirst().orElseThrow();
            assertNull(decal.depthAttachment(),
                    "the decal draw must not attach or write the shared scene depth");
            assertEquals(1, decal.samples());
            assertEquals(1, resolve.samples(),
                    "the decal shader samples a single-sample depth texture");
            assertTrue(decal.directDependencies().contains(
                            ProjectedDecalPass.DEPTH_RESOLVE_PASS));
            assertTrue(decal.directDependencies()
                    .contains(PostProcessTargets.GEOMETRY_PASS));
            assertTrue(resolve.directDependencies()
                    .contains(PostProcessTargets.GEOMETRY_PASS));
            assertTrue(description.passes().stream().anyMatch(candidate ->
                    candidate.name().equals(ProjectedDecalPass.PASS_NAME)
                            && candidate.colorAttachments().stream().anyMatch(attachment ->
                            attachment.logicalName().equals(ProjectedDecalPass.COLOR_TEXTURE))),
                    "the stage owns the colour target that continues the chain");
        }

        // MSAA: the colour target keeps the topology sample count while the
        // sampled depth copy stays single-sample.
        try (RenderGraph graph = GraphRecordingFixture.graph(64, 48)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColorMS(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F, 4)
                    .createDepth()
                    .execute((res, cmd) -> { });
            RenderPipeline.addProjectedDecalPass(graph, PostProcessTargets.GEOMETRY_PASS,
                    RenderFormat.RGBA16F, 4, (res, cmd) -> { }, (res, cmd) -> { });
            graph.compile();
            var description = graph.description();
            var decal = description.passes().stream()
                    .filter(candidate -> candidate.name().equals(ProjectedDecalPass.PASS_NAME))
                    .findFirst().orElseThrow();
            var resolve = description.passes().stream()
                    .filter(candidate -> candidate.name()
                            .equals(ProjectedDecalPass.DEPTH_RESOLVE_PASS))
                    .findFirst().orElseThrow();
            assertEquals(4, decal.samples());
            assertNull(decal.depthAttachment(),
                    "an MSAA decal target must not attach a multisampled depth texture");
            assertEquals(1, resolve.samples());
        }
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
}
