package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.GraphRecordingFixture;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.pipeline.PipelineGeneration;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferRequirements;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencyPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticlePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricPassBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

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
            false, true, true, true, false, false, false, false, false);

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
                base.waterSurface(), particles, stochastic, clouds, smoke);
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
