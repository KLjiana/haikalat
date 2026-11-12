package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.GraphRecordingFixture;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pipeline.PipelineGeneration;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulation;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeVolumePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricPassBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-wiring contract for the visible cloud layer (#40) and the smoke
 * simulation/volume consumers (#43).
 *
 * <p>Pure JVM cases cover the topology key, effective reasons, colour-chain
 * declaration and the transaction/rollback boundary. The graph assertions use
 * the real {@link RenderGraph} compiler with the recording fixture, so the
 * declared order and the absent shared-depth attachment are production
 * declarations rather than a duplicated test topology. Real-GL contribution and
 * resource-lifecycle cases live in the guarded
 * {@code VolumetricCloudPipelineGlTest}/{@code SmokePipelineGlTest}.</p>
 */
class VolumetricCloudSmokeWiringTest {
    private static final RenderFeaturePolicy.Facts NO_PBR = new RenderFeaturePolicy.Facts(
            false, true, true, true, false, false, false, false, false);
    private static final RenderFeaturePolicy.Facts PBR_WITH_CASTER = new RenderFeaturePolicy.Facts(
            true, true, true, true, true, false, false, false, false);

    @Test
    void disabledSettingsLeaveTheTopologyKeyUnchanged() {
        Scene scene = new Scene(new Camera());
        PipelineTopology baseline = PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());
        PipelineTopology explicitDisabled = capture(scene, hdr(AntiAliasingMode.NONE),
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), CloudShadowSettings.disabled());

        assertEquals(baseline, explicitDisabled,
                "disabled medium settings must not change the topology key");
        assertFalse(explicitDisabled.volumetricClouds());
        assertFalse(explicitDisabled.volumetricSmoke());

        var resolved = RenderFeaturePolicy.resolve(
                RenderFeatureSettings.builder().render(hdr(AntiAliasingMode.NONE)).build(),
                NO_PBR);
        assertFalse(resolved.volumetricClouds());
        assertFalse(resolved.volumetricSmoke());
        assertEquals(RenderFeatureReason.DISABLED,
                activity(resolved, RenderFeature.VOLUMETRIC_CLOUDS).reason());
        assertEquals(RenderFeatureReason.DISABLED,
                activity(resolved, RenderFeature.VOLUMETRIC_SMOKE).reason());
    }

    @Test
    void cloudsRequireStableSingleSampleHdrDepthAndMatchingCoverage() {
        Scene scene = new Scene(new Camera());
        PipelineTopology active = capture(scene, hdr(AntiAliasingMode.NONE), clouds(),
                SmokeSimulationSettings.disabled(), SmokeRenderSettings.disabled(),
                CloudShadowSettings.disabled());
        assertTrue(active.volumetricClouds());
        assertNotNull(active.sceneSurface().single(SceneBufferChannelHolder.DEPTH),
                "an active cloud layer must structurally request the same-frame opaque depth");
        new PipelineFeaturePolicy(active, false).validate();

        assertFalse(capture(scene, hdr(AntiAliasingMode.TAA), clouds(),
                SmokeSimulationSettings.disabled(), SmokeRenderSettings.disabled(),
                CloudShadowSettings.disabled()).volumetricClouds(),
                "TAA jitter is not a supported cloud raster");
        assertFalse(capture(scene, hdr(AntiAliasingMode.MSAA), clouds(),
                SmokeSimulationSettings.disabled(), SmokeRenderSettings.disabled(),
                CloudShadowSettings.disabled()).volumetricClouds(),
                "MSAA raw attachments are not supported");
        assertFalse(capture(scene, RenderSettings.builder().vsync(false).build(), clouds(),
                SmokeSimulationSettings.disabled(), SmokeRenderSettings.disabled(),
                CloudShadowSettings.disabled()).volumetricClouds(),
                "the cloud layer requires the linear HDR colour chain");

        CloudShadowSettings shadowClouds = cloudShadowField(0.4f);
        CloudShadowSettings otherCoverage = cloudShadowField(0.7f);
        assertFalse(capture(scene, hdr(AntiAliasingMode.NONE),
                clouds().withCoverageField(otherCoverage), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), shadowClouds, true).volumetricClouds(),
                "an enabled #10 cloud shadow must share the visible layer's coverage field");
        assertTrue(capture(scene, hdr(AntiAliasingMode.NONE),
                clouds().withCoverageField(shadowClouds), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), shadowClouds, true).volumetricClouds());
    }

    @Test
    void smokeRequiresTheSimulationOwnerAndStableSingleSampleRaster() {
        Scene scene = new Scene(new Camera());
        PipelineTopology active = capture(scene, hdr(AntiAliasingMode.NONE),
                VolumetricCloudSettings.disabled(), smokeSimulation(), smokeRender(),
                CloudShadowSettings.disabled());
        assertTrue(active.volumetricSmoke());
        assertNotNull(active.sceneSurface().single(SceneBufferChannelHolder.DEPTH));
        new PipelineFeaturePolicy(active, false).validate();

        assertFalse(capture(scene, hdr(AntiAliasingMode.NONE),
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                smokeRender(), CloudShadowSettings.disabled()).volumetricSmoke(),
                "the volume pass must not exist without its simulation owner");
        assertFalse(capture(scene, hdr(AntiAliasingMode.NONE),
                VolumetricCloudSettings.disabled(), smokeSimulation(),
                SmokeRenderSettings.disabled(), CloudShadowSettings.disabled()).volumetricSmoke(),
                "a simulation without a consumer is not a rendered medium");
        assertFalse(capture(scene, hdr(AntiAliasingMode.TAA),
                VolumetricCloudSettings.disabled(), smokeSimulation(), smokeRender(),
                CloudShadowSettings.disabled()).volumetricSmoke());
        assertFalse(capture(scene, hdr(AntiAliasingMode.MSAA),
                VolumetricCloudSettings.disabled(), smokeSimulation(), smokeRender(),
                CloudShadowSettings.disabled()).volumetricSmoke());
    }

    @Test
    void cloudAndSmokeComposeOnceAfterTheFogAndParticleChain() {
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
            graph.addPass(VolumetricPassBuilder.TRANSPARENT_PASS)
                    .shareColorTextures(List.of(VolumetricPassBuilder.SCENE_COLOR),
                            List.of(RenderFormat.RGBA16F), VolumetricParticlePassName.NAME, 1)
                    .shareDepthTexture("scene.depth", PostProcessTargets.GEOMETRY_PASS)
                    .noClear()
                    .execute((res, cmd) -> { });

            RenderPipeline.addVolumetricCloudPass(graph, VolumetricPassBuilder.TRANSPARENT_PASS,
                    PostProcessTargets.GEOMETRY_PASS, (res, cmd) -> { });
            RenderPipeline.addSmokeSimulationPass(graph, (res, cmd) -> { });
            RenderPipeline.addSmokeVolumePass(graph, VolumetricCloudPass.PASS_NAME,
                    PostProcessTargets.GEOMETRY_PASS, (res, cmd) -> { });
            graph.addPass(PostProcessTargets.TONE_MAPPING_PASS)
                    .computeOnly()
                    .dependsOn(SmokeVolumePass.PASS_NAME)
                    .execute((res, cmd) -> { });
            graph.compile();

            List<String> order = graph.description().executionOrder();
            assertTrue(order.indexOf(VolumetricPassBuilder.COMPOSITE_PASS)
                    < order.indexOf(VolumetricCloudPass.PASS_NAME));
            assertTrue(order.indexOf(VolumetricParticlePassName.NAME)
                    < order.indexOf(VolumetricCloudPass.PASS_NAME),
                    "clouds integrate the fog/particle-composited colour: " + order);
            assertTrue(order.indexOf(VolumetricCloudPass.PASS_NAME)
                            < order.indexOf(SmokeVolumePass.PASS_NAME),
                    "smoke composites after the cloud medium: " + order);
            assertTrue(order.indexOf(SmokeSimulation.PASS_NAME)
                            < order.indexOf(SmokeVolumePass.PASS_NAME),
                    "the simulation stage must precede its consumer: " + order);
            assertTrue(order.indexOf(SmokeVolumePass.PASS_NAME)
                            < order.indexOf(PostProcessTargets.TONE_MAPPING_PASS),
                    "both media must stay before the post-process chain: " + order);

            var cloud = pass(graph, VolumetricCloudPass.PASS_NAME);
            assertNull(cloud.depthAttachment(),
                    "the cloud bridge must not attach or write the shared opaque depth");
            assertEquals(VolumetricCloudPass.COLOR_TEXTURE,
                    cloud.colorAttachments().getFirst().logicalName());
            assertEquals("RGBA16F", cloud.colorAttachments().getFirst().format());
            assertTrue(cloud.directDependencies().contains(
                    VolumetricPassBuilder.TRANSPARENT_PASS));
            assertTrue(cloud.directDependencies().contains(PostProcessTargets.GEOMETRY_PASS));

            var smoke = pass(graph, SmokeVolumePass.PASS_NAME);
            assertNull(smoke.depthAttachment(),
                    "the smoke bridge must not attach or write the shared opaque depth");
            assertEquals(SmokeVolumePass.COLOR_TEXTURE,
                    smoke.colorAttachments().getFirst().logicalName());
            assertEquals("RGBA16F", smoke.colorAttachments().getFirst().format());
            assertTrue(smoke.directDependencies().contains(SmokeSimulation.PASS_NAME));
            assertTrue(smoke.directDependencies().contains(VolumetricCloudPass.PASS_NAME));
        }
    }

    @Test
    void featureControlReportsCloudAndSmokeReasons() {
        var cloudsActive = RenderFeaturePolicy.resolve(baseBuilder()
                .volumetricClouds(clouds()).build(), NO_PBR);
        assertTrue(cloudsActive.volumetricClouds());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(cloudsActive, RenderFeature.VOLUMETRIC_CLOUDS).reason());

        var cloudsUnderTaa = RenderFeaturePolicy.resolve(
                baseBuilder().render(hdr(AntiAliasingMode.TAA))
                        .volumetricClouds(clouds()).build(), NO_PBR);
        assertFalse(cloudsUnderTaa.volumetricClouds());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(cloudsUnderTaa, RenderFeature.VOLUMETRIC_CLOUDS).reason());

        CloudShadowSettings shadows = cloudShadowField(0.4f);
        var covered = RenderFeaturePolicy.resolve(baseBuilder()
                .directionalShadowMap(directionalShadowMap(shadows))
                .volumetricClouds(clouds().withCoverageField(shadows)).build(),
                PBR_WITH_CASTER);
        assertTrue(covered.volumetricClouds());
        var mismatched = RenderFeaturePolicy.resolve(baseBuilder()
                .directionalShadowMap(directionalShadowMap(shadows))
                .volumetricClouds(clouds().withCoverageField(cloudShadowField(0.8f))).build(),
                PBR_WITH_CASTER);
        assertFalse(mismatched.volumetricClouds());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(mismatched, RenderFeature.VOLUMETRIC_CLOUDS).reason());

        var smokeActive = RenderFeaturePolicy.resolve(baseBuilder()
                .smokeSimulation(smokeSimulation()).smokeRender(smokeRender()).build(), NO_PBR);
        assertTrue(smokeActive.volumetricSmoke());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(smokeActive, RenderFeature.VOLUMETRIC_SMOKE).reason());

        var smokeWithoutOwner = RenderFeaturePolicy.resolve(baseBuilder()
                .smokeRender(smokeRender()).build(), NO_PBR);
        assertFalse(smokeWithoutOwner.volumetricSmoke());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(smokeWithoutOwner, RenderFeature.VOLUMETRIC_SMOKE).reason());

        var smokeWithoutConsumer = RenderFeaturePolicy.resolve(baseBuilder()
                .smokeSimulation(smokeSimulation()).build(), NO_PBR);
        assertFalse(smokeWithoutConsumer.volumetricSmoke());
        assertEquals(RenderFeatureReason.DISABLED,
                activity(smokeWithoutConsumer, RenderFeature.VOLUMETRIC_SMOKE).reason());

        var smokeUnderTaa = RenderFeaturePolicy.resolve(baseBuilder()
                .render(hdr(AntiAliasingMode.TAA))
                .smokeSimulation(smokeSimulation()).smokeRender(smokeRender()).build(), NO_PBR);
        assertFalse(smokeUnderTaa.volumetricSmoke());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(smokeUnderTaa, RenderFeature.VOLUMETRIC_SMOKE).reason());

        var paused = RenderFeaturePolicy.resolve(baseBuilder()
                .enabled(false)
                .volumetricClouds(clouds())
                .smokeSimulation(smokeSimulation())
                .smokeRender(smokeRender())
                .build(), NO_PBR);
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(paused, RenderFeature.VOLUMETRIC_CLOUDS).reason());
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(paused, RenderFeature.VOLUMETRIC_SMOKE).reason());
        assertFalse(paused.volumetricClouds());
        assertFalse(paused.volumetricSmoke());
    }

    @Test
    void handBuiltActiveTopologiesAreRejectedWithoutTheirInputs() {
        Scene scene = new Scene(new Camera());
        PipelineTopology base = PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());
        PipelineTopology cloudWithoutDepth = copy(base, true, false);
        IllegalStateException depthFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(cloudWithoutDepth, false).validate());
        assertTrue(depthFailure.getMessage().contains("volumetric clouds"));

        PipelineTopology cloudUnderTaa = copyWithMode(base, AntiAliasingMode.TAA, true, false);
        IllegalStateException taaFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(cloudUnderTaa, false).validate());
        assertTrue(taaFailure.getMessage().contains("TAA"));

        PipelineTopology smokeWithoutDepth = copy(base, false, true);
        IllegalStateException smokeFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(smokeWithoutDepth, false).validate());
        assertTrue(smokeFailure.getMessage().contains("smoke volume rendering"));
    }

    @Test
    void settingsTransactionsRollBackWhenTheCandidateCannotBeBuilt() throws Exception {
        Scene scene = new Scene(new Camera());
        RenderPipeline pipeline = new RenderPipeline(window(), scene, null,
                hdr(AntiAliasingMode.NONE));
        PipelineTopology topology = capture(scene, hdr(AntiAliasingMode.NONE), clouds(),
                smokeSimulation(), smokeRender(), CloudShadowSettings.disabled());
        PipelineGeneration injected = new PipelineGeneration(topology);
        RenderGraph graph = GraphRecordingFixture.graph(64, 48);
        try {
            setField(injected, "graph", graph);
            setField(pipeline, "activeGeneration", injected);

            assertThrows(IllegalStateException.class,
                    () -> pipeline.applyVolumetricClouds(clouds()));
            assertEquals(VolumetricCloudSettings.disabled(), pipeline.volumetricClouds());
            assertSame(injected, pipeline.activeGenerationForTest());

            assertThrows(IllegalStateException.class,
                    () -> pipeline.applySmokeSimulation(smokeSimulation()));
            assertEquals(SmokeSimulationSettings.disabled(), pipeline.smokeSimulation());
            assertEquals(SmokeRenderSettings.disabled(), pipeline.smokeRender());
            assertSame(injected, pipeline.activeGenerationForTest());

            assertThrows(IllegalStateException.class,
                    () -> pipeline.applySmokeRender(smokeRender()));
            assertEquals(SmokeRenderSettings.disabled(), pipeline.smokeRender());
            assertSame(injected, pipeline.activeGenerationForTest());
        } finally {
            setField(pipeline, "activeGeneration", null);
            pipeline.close();
            graph.close();
            injected.close();
        }
    }

    @Test
    void builderEntriesAndExtentChangesPreserveTheMediumRequests() {
        Scene scene = new Scene(new Camera());
        PipelineTopology active = capture(scene, hdr(AntiAliasingMode.NONE), clouds(),
                smokeSimulation(), smokeRender(), CloudShadowSettings.disabled());
        assertTrue(active.volumetricClouds());
        assertTrue(active.volumetricSmoke());
        PipelineTopology resized = active.withExtent(128, 96);
        assertTrue(resized.volumetricClouds());
        assertTrue(resized.volumetricSmoke());
        assertNotNull(resized.sceneSurface().single(SceneBufferChannelHolder.DEPTH));
        assertEquals(128, resized.width());
        assertEquals(96, resized.height());

        RenderFeatureSettings roundTrip = RenderFeatureSettings.defaults().toBuilder()
                .volumetricClouds(clouds())
                .smokeSimulation(smokeSimulation())
                .smokeRender(smokeRender())
                .build();
        assertEquals(clouds(), roundTrip.volumetricClouds());
        assertEquals(smokeSimulation(), roundTrip.smokeSimulation());
        assertEquals(smokeRender(), roundTrip.smokeRender());

        RenderPipeline pipeline = new RenderPipeline(window(), scene, null,
                hdr(AntiAliasingMode.NONE));
        try {
            assertEquals(VolumetricCloudSettings.disabled(), pipeline.volumetricClouds());
            assertEquals(SmokeSimulationSettings.disabled(), pipeline.smokeSimulation());
            assertEquals(SmokeRenderSettings.disabled(), pipeline.smokeRender());
            pipeline.volumetricClouds(clouds());
            pipeline.smokeSimulation(smokeSimulation());
            pipeline.smokeRender(smokeRender());
            assertEquals(clouds(), pipeline.volumetricClouds());
            assertEquals(smokeSimulation(), pipeline.smokeSimulation());
            assertEquals(smokeRender(), pipeline.smokeRender());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void sameExtentResizeIsANoOpAndGenerationCloseIsIdempotent() throws Exception {
        Scene scene = new Scene(new Camera());
        PipelineTopology topology = capture(scene, hdr(AntiAliasingMode.NONE), clouds(),
                smokeSimulation(), smokeRender(), CloudShadowSettings.disabled());
        PipelineGeneration generation = new PipelineGeneration(topology);
        RenderGraph graph = GraphRecordingFixture.graph(64, 48);
        try {
            setField(generation, "graph", graph);
            generation.resize(64, 48);
            assertFalse(generation.isClosed(), "a same-extent resize keeps the generation");
            generation.close();
            generation.close();
            assertTrue(generation.isClosed(), "close is idempotent with the medium flags set");
        } finally {
            graph.close();
        }
    }

    /** Channel indirection keeps the public imports focused on the production API under test. */
    private static final class SceneBufferChannelHolder {
        private static final com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel
                DEPTH = com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH;
    }

    /** Constant indirection keeps the particle pass name beside the volumetric imports. */
    private static final class VolumetricParticlePassName {
        private static final String NAME =
                com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticlePass.PASS_NAME;
    }

    private static PipelineTopology capture(Scene scene, RenderSettings settings,
                                            VolumetricCloudSettings clouds,
                                            SmokeSimulationSettings simulation,
                                            SmokeRenderSettings render,
                                            CloudShadowSettings directionalCloudShadows) {
        return capture(scene, settings, clouds, simulation, render, directionalCloudShadows,
                false);
    }

    private static PipelineTopology capture(Scene scene, RenderSettings settings,
                                            VolumetricCloudSettings clouds,
                                            SmokeSimulationSettings simulation,
                                            SmokeRenderSettings render,
                                            CloudShadowSettings directionalCloudShadows,
                                            boolean directionalCaster) {
        Scene captureScene = scene;
        if (directionalCaster) {
            captureScene = new Scene(new Camera());
            captureScene.addLight(SceneLight.shadowedDirectional(new Vector3f(0.0f, -1.0f, 0.0f),
                    new Vector3f(1.0f), 1.0f));
        }
        return PipelineTopology.capture(captureScene, settings, PostProcessSettings.defaults(),
                64, 48, false, false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(), StochasticTransparencySettings.disabled(),
                clouds, simulation, render, directionalCloudShadows,
                WeightedOitSettings.disabled(), false, PlanarReflectionSettings.disabled(),
                CausticsSettings.disabled());
    }

    private static PipelineTopology copy(PipelineTopology base, boolean clouds, boolean smoke) {
        return new PipelineTopology(base.width(), base.height(), base.antiAliasingMode(),
                base.sampleCount(), base.hdr(), base.bloom(), base.bloomLevels(),
                base.automaticExposure(), base.colorGrading(), base.fog(),
                base.directionalShadow(), base.pointShadow(), base.spotShadow(),
                base.directionalCascadeCount(), base.directionalShadowAtlasSize(),
                base.pointShadowCapacity(), base.pointShadowResolution(),
                base.spotShadowCapacity(), base.spotShadowResolution(),
                base.pbrMaterials(), base.hdrVfx(), base.embedded(), base.gtaoEnabled(),
                base.gtaoTemporal(), base.gtaoHalfWidth(), base.gtaoHalfHeight(),
                base.sceneSurface(), base.volumetricFog(), base.volumetricQuality(),
                base.volumetricEmission(), base.waterSurface(), base.volumetricParticles(),
                base.stochasticTransparency(), clouds, smoke, false, false, false);
    }

    private static PipelineTopology copyWithMode(PipelineTopology base, AntiAliasingMode mode,
                                                 boolean clouds, boolean smoke) {
        return new PipelineTopology(base.width(), base.height(), mode,
                base.sampleCount(), base.hdr(), base.bloom(), base.bloomLevels(),
                base.automaticExposure(), base.colorGrading(), base.fog(),
                base.directionalShadow(), base.pointShadow(), base.spotShadow(),
                base.directionalCascadeCount(), base.directionalShadowAtlasSize(),
                base.pointShadowCapacity(), base.pointShadowResolution(),
                base.spotShadowCapacity(), base.spotShadowResolution(),
                base.pbrMaterials(), base.hdrVfx(), base.embedded(), base.gtaoEnabled(),
                base.gtaoTemporal(), base.gtaoHalfWidth(), base.gtaoHalfHeight(),
                base.sceneSurface(), base.volumetricFog(), base.volumetricQuality(),
                base.volumetricEmission(), base.waterSurface(), base.volumetricParticles(),
                base.stochasticTransparency(), clouds, smoke, false, false, false);
    }

    private static RenderFeatureSettings.Builder baseBuilder() {
        return RenderFeatureSettings.builder().render(hdr(AntiAliasingMode.NONE));
    }

    private static RenderSettings hdr(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static VolumetricCloudSettings clouds() {
        return VolumetricCloudSettings.disabled().withEnabled(true);
    }

    private static SmokeSimulationSettings smokeSimulation() {
        return SmokeSimulationSettings.disabled().withEnabled(true);
    }

    private static SmokeRenderSettings smokeRender() {
        return SmokeRenderSettings.disabled().withEnabled(true);
    }

    private static CloudShadowSettings cloudShadowField(float coverage) {
        return new CloudShadowSettings(true, coverage, 0.2f, 0.12f, 1.2f, 0.65f);
    }

    private static DirectionalShadowMap directionalShadowMap(CloudShadowSettings cloudShadows) {
        return new DirectionalShadowMap(new ShadowSettings(2048, 20.0f, 0.1f, 80.0f, 0.003f,
                ContactShadowSettings.disabled(), cloudShadows));
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

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
