package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.GraphRecordingFixture;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiIndirectPass;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiProbeVolume;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandPass;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pipeline.PipelineGeneration;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctBox;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctPass;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctProbeBatch;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSceneInput;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production wiring contract for DDGI (#44), VCT (#45) and hair strands (#46).
 *
 * <p>Graph cases use the real {@link RenderGraph} compiler and recording
 * fixture, so pass order, dependencies and colour/depth aliasing are the actual
 * production declarations. Pipeline cases exercise the frame-boundary settings
 * transactions without a GL context: candidate creation must fail closed and
 * restore the previous request.</p>
 */
class RenderPipelineWiringB3Test {
    private static final RenderFeaturePolicy.Facts NO_PBR = new RenderFeaturePolicy.Facts(
            false, true, true, true, false, false, false, false, false, true);

    @Test
    void disabledSettingsLeaveTopologyAndCompositionUnchanged() {
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
                StochasticTransparencySettings.disabled(),
                DdgiSettings.disabled(), VctSettings.disabled(), HairStrandSettings.disabled());

        assertEquals(baseline, disabled,
                "disabled DDGI/VCT/hair settings must not change the topology key");
        assertFalse(disabled.ddgi());
        assertFalse(disabled.voxelConeTracing());
        assertFalse(disabled.hairStrands());
    }

    @Test
    void activeRequestsWithoutTheirInputsStayUnavailableAndDoNotChangeTheKey() {
        Scene scene = new Scene(new Camera());
        PipelineTopology baseline = PipelineTopology.capture(scene, hdr(AntiAliasingMode.TAA),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());
        // No directional light and a TAA raster: both requests are ineligible.
        PipelineTopology unavailable = PipelineTopology.capture(scene, hdr(AntiAliasingMode.TAA),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                ddgi(), vct(), hair());

        assertEquals(baseline, unavailable);
        assertFalse(unavailable.ddgi());
        assertFalse(unavailable.voxelConeTracing());
        assertFalse(unavailable.hairStrands());
    }

    @Test
    void ddgiProbeUpdateRunsAfterOpaqueForwardAndBeforeTheIndirectBridge() {
        Scene scene = new Scene(new Camera());
        scene.addLight(directional());
        PipelineTopology topology = PipelineTopology.capture(scene,
                hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 48, false,
                false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                ddgi(), VctSettings.disabled(), HairStrandSettings.disabled());
        assertTrue(topology.ddgi());
        assertTrue(topology.sceneBuffers().requires(
                com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH));
        assertTrue(topology.sceneBuffers().requires(
                com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.NORMAL));
        assertTrue(topology.sceneSurface().single(
                com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH)
                != null, "DDGI must expose the shared single-sample depth");
        assertTrue(topology.sceneSurface().single(
                com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.NORMAL)
                != null, "DDGI must expose the shared single-sample normal");

        try (RenderGraph graph = GraphRecordingFixture.graph(64, 48)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .createDepthTexture("scene.depth")
                    .execute((res, cmd) -> { });
            RenderPipeline.addDdgiPasses(graph, PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.GEOMETRY_PASS,
                    RenderFormat.RGBA16F, (res, cmd) -> { }, (res, cmd) -> { });
            graph.addPass(PostProcessTargets.TONE_MAPPING_PASS)
                    .computeOnly()
                    .dependsOn(DdgiIndirectPass.PASS_NAME)
                    .execute((res, cmd) -> { });
            graph.compile();

            List<String> order = graph.description().executionOrder();
            assertTrue(order.indexOf(PostProcessTargets.GEOMETRY_PASS)
                    < order.indexOf(DdgiProbeVolume.PASS_NAME),
                    "the probe update must follow the opaque forward pass: " + order);
            assertTrue(order.indexOf(DdgiProbeVolume.PASS_NAME)
                    < order.indexOf(DdgiIndirectPass.PASS_NAME),
                    "the indirect bridge must consume an updated probe atlas: " + order);
            assertTrue(order.indexOf(DdgiIndirectPass.PASS_NAME)
                    < order.indexOf(PostProcessTargets.TONE_MAPPING_PASS),
                    "the indirect bridge must precede the post-process/TAA chain: " + order);

            var indirect = pass(graph, DdgiIndirectPass.PASS_NAME);
            assertNull(indirect.depthAttachment(),
                    "the DDGI indirect bridge must not attach or write the shared depth");
            assertEquals(RenderPipeline.DDGI_INDIRECT_COLOR,
                    indirect.colorAttachments().getFirst().logicalName());
            assertTrue(indirect.directDependencies().contains(DdgiProbeVolume.PASS_NAME));
            assertTrue(indirect.directDependencies().contains(PostProcessTargets.GEOMETRY_PASS));
            var probe = pass(graph, DdgiProbeVolume.PASS_NAME);
            assertTrue(probe.directDependencies().contains(PostProcessTargets.GEOMETRY_PASS));
        }
    }

    @Test
    void vctVoxelizationAndTraceRunBeforeTheComposite() {
        Scene scene = new Scene(new Camera());
        scene.addLight(directional());
        PipelineTopology topology = PipelineTopology.capture(scene,
                hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 48, false,
                false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                DdgiSettings.disabled(), vct(), HairStrandSettings.disabled());
        assertTrue(topology.voxelConeTracing());
        assertTrue(topology.sceneBuffers().requires(
                com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH));
        assertTrue(topology.sceneSurface().single(
                com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH)
                != null, "VCT must expose the shared single-sample depth");

        try (RenderGraph graph = GraphRecordingFixture.graph(64, 48)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .createDepthTexture("scene.depth")
                    .execute((res, cmd) -> { });
            RenderPipeline.addVctPasses(graph, PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.GEOMETRY_PASS, RenderFormat.RGBA16F,
                    (res, cmd) -> { }, (res, cmd) -> { }, (res, cmd) -> { });
            graph.addPass(PostProcessTargets.TONE_MAPPING_PASS)
                    .computeOnly()
                    .dependsOn(RenderPipeline.VCT_COMPOSITE_PASS)
                    .execute((res, cmd) -> { });
            graph.compile();

            List<String> order = graph.description().executionOrder();
            assertTrue(order.indexOf(PostProcessTargets.GEOMETRY_PASS)
                    < order.indexOf(RenderPipeline.VCT_VOXELIZE_PASS));
            assertTrue(order.indexOf(RenderPipeline.VCT_VOXELIZE_PASS)
                    < order.indexOf(RenderPipeline.VCT_TRACE_PASS),
                    "the cone trace must consume the voxelized volume: " + order);
            assertTrue(order.indexOf(RenderPipeline.VCT_TRACE_PASS)
                    < order.indexOf(RenderPipeline.VCT_COMPOSITE_PASS),
                    "the composite must follow voxelization/trace: " + order);
            assertTrue(order.indexOf(RenderPipeline.VCT_COMPOSITE_PASS)
                    < order.indexOf(PostProcessTargets.TONE_MAPPING_PASS),
                    "the composite must precede Bloom/TAA: " + order);

            var composite = pass(graph, RenderPipeline.VCT_COMPOSITE_PASS);
            assertNull(composite.depthAttachment(),
                    "the VCT composite must not attach or write the shared depth");
            assertEquals(RenderPipeline.VCT_COLOR,
                    composite.colorAttachments().getFirst().logicalName());
            assertTrue(composite.directDependencies()
                    .contains(RenderPipeline.VCT_VOXELIZE_PASS));
            assertTrue(composite.directDependencies()
                    .contains(RenderPipeline.VCT_TRACE_PASS));
            assertTrue(composite.directDependencies()
                    .contains(PostProcessTargets.GEOMETRY_PASS));
        }
    }

    @Test
    void hairForwardSharesSceneColourAndRasterDepthInTheTransparentStage() {
        Scene scene = new Scene(new Camera());
        scene.addLight(directional());
        PipelineTopology topology = PipelineTopology.capture(scene,
                hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 48, false,
                false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                DdgiSettings.disabled(), VctSettings.disabled(), hair());
        assertTrue(topology.hairStrands());
        assertTrue(topology.sceneSurface().raw(
                com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH)
                != null);

        try (RenderGraph graph = GraphRecordingFixture.graph(64, 48)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .createDepthTexture("scene.depth")
                    .execute((res, cmd) -> { });
            RenderPipeline.addHairStrandPass(graph, topology, hdr(AntiAliasingMode.NONE),
                    "scene.depth", PostProcessTargets.GEOMETRY_PASS, (res, cmd) -> { });
            graph.addPass(PostProcessTargets.TONE_MAPPING_PASS)
                    .computeOnly()
                    .dependsOn(HairStrandPass.PASS_NAME)
                    .execute((res, cmd) -> { });
            graph.compile();

            List<String> order = graph.description().executionOrder();
            assertTrue(order.indexOf(PostProcessTargets.GEOMETRY_PASS)
                    < order.indexOf(HairStrandPass.PASS_NAME),
                    "hair must draw after the sorted transparent queue: " + order);
            assertTrue(order.indexOf(HairStrandPass.PASS_NAME)
                    < order.indexOf(PostProcessTargets.TONE_MAPPING_PASS),
                    "hair must stay in the transparent HDR stage before post-process: " + order);

            var hairPass = pass(graph, HairStrandPass.PASS_NAME);
            assertEquals("scene.depth", hairPass.depthAttachment().logicalName());
            assertEquals(PostProcessTargets.SCENE_COLOR,
                    hairPass.colorAttachments().getFirst().logicalName());
            assertTrue(graph.matchesColorStorage(HairStrandPass.PASS_NAME,
                    PostProcessTargets.GEOMETRY_PASS, List.of(0)),
                    "hair must alias the geometry colour instead of owning a second target");
            assertTrue(hairPass.directDependencies().contains(
                    PostProcessTargets.GEOMETRY_PASS));
        }
    }

    @Test
    void hairVelocityPassAliasesTheSurfaceVelocitySlotAndRunsBeforeTaa() {
        Scene scene = new Scene(new Camera());
        scene.addLight(directional());
        PostProcessSettings ssrEffects = PostProcessSettings.builder()
                .ssr(SsrSettings.builder().enabled(true).build())
                .build();
        PipelineTopology topology = PipelineTopology.capture(scene,
                hdr(AntiAliasingMode.TAA), ssrEffects, 64, 48, false,
                false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                DdgiSettings.disabled(), VctSettings.disabled(), hair());
        assertTrue(topology.hairStrands());
        assertEquals(1, topology.sampleCount(),
                "hair and MSAA are rejected by policy; the velocity pass is single-sample");
        var velocity = topology.sceneSurface().raw(
                com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.VELOCITY);
        assertNotNull(velocity, "TAA must expose the raw single-sample velocity attachment");
        assertEquals(1, velocity.colorSlot(),
                "SSR's NORMAL channel precedes VELOCITY in the shared surface MRT order");

        try (RenderGraph graph = GraphRecordingFixture.graph(64, 48)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .execute((res, cmd) -> { });
            graph.addPass(PostProcessTargets.SCENE_SURFACE_PASS)
                    .createColors(List.of(PostProcessTargets.SCENE_NORMAL,
                                    PostProcessTargets.SCENE_VELOCITY),
                            List.of(RenderFormat.RG16F, RenderFormat.RG16F))
                    .createDepthTexture(PostProcessTargets.SCENE_DEPTH)
                    .execute((res, cmd) -> { });
            RenderPipeline.addHairStrandPass(graph, topology, hdr(AntiAliasingMode.TAA),
                    PostProcessTargets.SCENE_DEPTH, PostProcessTargets.SCENE_SURFACE_PASS,
                    (res, cmd) -> { });
            RenderPipeline.addHairStrandVelocityPass(graph, topology,
                    PostProcessTargets.SCENE_DEPTH, PostProcessTargets.SCENE_SURFACE_PASS,
                    (res, cmd) -> { });
            graph.addPass(PostProcessTargets.TAA_PASS)
                    .writeToExternalTarget()
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .dependsOn(PostProcessTargets.SCENE_SURFACE_PASS)
                    .execute((res, cmd) -> { });
            graph.compile();

            List<String> order = graph.description().executionOrder();
            assertTrue(order.indexOf(HairStrandPass.PASS_NAME)
                            < order.indexOf(HairStrandPass.VELOCITY_PASS_NAME),
                    "the velocity draw must follow the forward transparent half: " + order);
            assertTrue(order.indexOf(HairStrandPass.VELOCITY_PASS_NAME)
                            < order.indexOf(PostProcessTargets.TAA_PASS),
                    "the velocity draw must complete before TAA samples sceneVelocity: " + order);

            var surface = pass(graph, PostProcessTargets.SCENE_SURFACE_PASS);
            assertEquals(List.of(PostProcessTargets.SCENE_NORMAL,
                            PostProcessTargets.SCENE_VELOCITY),
                    surface.colorAttachments().stream()
                            .map(RenderGraph.AttachmentDescription::logicalName).toList(),
                    "the hair velocity pass must not shadow or replace the surface outputs");
            assertTrue(graph.matchesColorStorage(PostProcessTargets.SCENE_SURFACE_PASS,
                            null, List.of()),
                    "the surface pass must keep owning its colour attachments");

            var velocityPass = pass(graph, HairStrandPass.VELOCITY_PASS_NAME);
            assertEquals(PostProcessTargets.SCENE_VELOCITY,
                    velocityPass.colorAttachments().getFirst().logicalName());
            assertEquals(PostProcessTargets.SCENE_DEPTH,
                    velocityPass.depthAttachment().logicalName(),
                    "the velocity pass must depth-test against the shared raster depth");
            assertFalse(velocityPass.clearColor());
            assertFalse(velocityPass.clearDepth());
            assertTrue(graph.matchesColorStorage(HairStrandPass.VELOCITY_PASS_NAME,
                            PostProcessTargets.SCENE_SURFACE_PASS, List.of(1)),
                    "the velocity pass must borrow sceneVelocity at its source slot, not copy it");
            assertTrue(velocityPass.directDependencies()
                            .contains(PostProcessTargets.SCENE_SURFACE_PASS));
            assertTrue(velocityPass.directDependencies()
                            .contains(HairStrandPass.PASS_NAME),
                    "the velocity draw must be ordered after the forward transparent half");
        }

        PipelineTopology withoutNormal = PipelineTopology.capture(scene,
                hdr(AntiAliasingMode.TAA), PostProcessSettings.defaults(), 64, 48, false,
                false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                DdgiSettings.disabled(), VctSettings.disabled(), hair());
        var normalFree = withoutNormal.sceneSurface().raw(
                com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.VELOCITY);
        assertNotNull(normalFree);
        assertEquals(0, normalFree.colorSlot(),
                "without NORMAL the velocity attachment is the first colour slot");
        try (RenderGraph graph = GraphRecordingFixture.graph(64, 48)) {
            graph.addPass(PostProcessTargets.SCENE_SURFACE_PASS)
                    .createColor(PostProcessTargets.SCENE_VELOCITY, RenderFormat.RG16F)
                    .createDepthTexture(PostProcessTargets.SCENE_DEPTH)
                    .execute((res, cmd) -> { });
            // The velocity pass is declared next to the forward pass; the
            // ordering stub keeps this mini-graph equivalent in topology terms.
            graph.addPass(HairStrandPass.PASS_NAME)
                    .computeOnly()
                    .dependsOn(PostProcessTargets.SCENE_SURFACE_PASS)
                    .execute((res, cmd) -> { });
            RenderPipeline.addHairStrandVelocityPass(graph, withoutNormal,
                    PostProcessTargets.SCENE_DEPTH, PostProcessTargets.SCENE_SURFACE_PASS,
                    (res, cmd) -> { });
            graph.compile();
            assertTrue(graph.matchesColorStorage(HairStrandPass.VELOCITY_PASS_NAME,
                            PostProcessTargets.SCENE_SURFACE_PASS, List.of(0)),
                    "the slot-0 fallback must alias the first source attachment");
        }
    }

    @Test
    void featurePolicyReportsActiveUnavailableAndLightReasons() {
        Scene scene = new Scene(new Camera());
        scene.addLight(directional());
        var ddgiActive = RenderFeaturePolicy.resolve(hdrBuilder()
                .ddgi(ddgi()).build(), NO_PBR);
        assertTrue(ddgiActive.ddgi());
        assertEquals(RenderFeatureReason.ACTIVE, activity(ddgiActive, RenderFeature.DDGI).reason());

        var ddgiWithoutLight = RenderFeaturePolicy.resolve(hdrBuilder()
                .ddgi(ddgi()).build(), facts(false));
        assertFalse(ddgiWithoutLight.ddgi());
        assertEquals(RenderFeatureReason.NO_ELIGIBLE_LIGHT,
                activity(ddgiWithoutLight, RenderFeature.DDGI).reason());

        var ddgiUnderMsaa = RenderFeaturePolicy.resolve(hdrBuilder()
                .render(hdr(AntiAliasingMode.MSAA)).ddgi(ddgi()).build(), NO_PBR);
        assertFalse(ddgiUnderMsaa.ddgi());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(ddgiUnderMsaa, RenderFeature.DDGI).reason());

        var ddgiUnderTaa = RenderFeaturePolicy.resolve(hdrBuilder()
                .render(hdr(AntiAliasingMode.TAA)).ddgi(ddgi()).build(), NO_PBR);
        assertFalse(ddgiUnderTaa.ddgi());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(ddgiUnderTaa, RenderFeature.DDGI).reason());

        var vctActive = RenderFeaturePolicy.resolve(hdrBuilder().vct(vct()).build(), NO_PBR);
        assertTrue(vctActive.vct());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(vctActive, RenderFeature.VOXEL_CONE_TRACING).reason());

        var vctUnderMsaa = RenderFeaturePolicy.resolve(hdrBuilder()
                .render(hdr(AntiAliasingMode.MSAA)).vct(vct()).build(), NO_PBR);
        assertFalse(vctUnderMsaa.vct());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(vctUnderMsaa, RenderFeature.VOXEL_CONE_TRACING).reason());

        var hairActive = RenderFeaturePolicy.resolve(hdrBuilder().hairStrands(hair()).build(),
                NO_PBR);
        assertTrue(hairActive.hairStrands());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(hairActive, RenderFeature.HAIR_STRANDS).reason());

        var hairWithoutLight = RenderFeaturePolicy.resolve(hdrBuilder()
                .hairStrands(hair()).build(), facts(false));
        assertFalse(hairWithoutLight.hairStrands());
        assertEquals(RenderFeatureReason.NO_ELIGIBLE_LIGHT,
                activity(hairWithoutLight, RenderFeature.HAIR_STRANDS).reason());

        var pausedDdgi = RenderFeaturePolicy.resolve(hdrBuilder()
                .enabled(false).ddgi(ddgi()).build(), NO_PBR);
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(pausedDdgi, RenderFeature.DDGI).reason());
        var pausedVct = RenderFeaturePolicy.resolve(hdrBuilder()
                .enabled(false).vct(vct()).build(), NO_PBR);
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(pausedVct, RenderFeature.VOXEL_CONE_TRACING).reason());
        var pausedHair = RenderFeaturePolicy.resolve(hdrBuilder()
                .enabled(false).hairStrands(hair()).build(), NO_PBR);
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(pausedHair, RenderFeature.HAIR_STRANDS).reason());
    }

    @Test
    void ddgiAndVctAreMutuallyExclusive() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(hdrBuilder()
                        .ddgi(ddgi()).vct(vct()).build(), NO_PBR));
        assertTrue(failure.getMessage().contains("mutually exclusive"));

        Scene scene = new Scene(new Camera());
        scene.addLight(directional());
        PipelineTopology both = PipelineTopology.capture(scene,
                hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 48, false,
                false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                ddgi(), vct(), HairStrandSettings.disabled());
        // The capture predicates keep both requests in the key; the assembly
        // policy must reject that combination before any pass is assembled.
        assertTrue(both.ddgi());
        assertTrue(both.voxelConeTracing());
        IllegalStateException assemblyFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(both, false).validate());
        assertTrue(assemblyFailure.getMessage().contains("mutually exclusive"));
    }

    @Test
    void settingsTransactionsRollBackWhenCandidateAssemblyFails() throws Exception {
        Scene scene = new Scene(new Camera());
        scene.addLight(directional());
        RenderPipeline pipeline = new RenderPipeline(window(), scene, null,
                hdr(AntiAliasingMode.NONE));
        PipelineTopology topology = PipelineTopology.capture(scene,
                hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 48, false,
                false, DirectionalCascadeSettings.disabled());
        PipelineGeneration injected = new PipelineGeneration(topology);
        RenderGraph graph = GraphRecordingFixture.graph(64, 48);
        try {
            setField(injected, "graph", graph);
            setField(pipeline, "activeGeneration", injected);
            // No GL context exists here, so candidate creation fails before any
            // candidate can be published; the apply transaction must restore the
            // previous settings and keep the active generation.
            assertThrows(IllegalStateException.class, () -> pipeline.applyDdgi(ddgi()));
            assertEquals(DdgiSettings.disabled(), pipeline.ddgi());
            assertSame(injected, pipeline.activeGenerationForTest());

            assertThrows(IllegalStateException.class, () -> pipeline.applyVct(vct()));
            assertEquals(VctSettings.disabled(), pipeline.vct());
            assertSame(injected, pipeline.activeGenerationForTest());

            assertThrows(IllegalStateException.class, () -> pipeline.applyHairStrands(hair()));
            assertEquals(HairStrandSettings.disabled(), pipeline.hairStrands());
            assertSame(injected, pipeline.activeGenerationForTest());
        } finally {
            setField(pipeline, "activeGeneration", null);
            pipeline.close();
            graph.close();
            injected.close();
        }
    }

    @Test
    void stagedInputsAreCopiedValidatedAndDefaultsAreNoOps() {
        Scene scene = new Scene(new Camera());
        scene.addLight(directional());
        RenderPipeline pipeline = new RenderPipeline(window(), scene, null,
                hdr(AntiAliasingMode.NONE));
        try {
            assertNull(pipeline.vctSceneInput());
            assertEquals(List.of(), pipeline.vctDirtyBoxes());
            assertNull(pipeline.vctProbes());
            assertFalse(pipeline.ddgi().active());
            assertFalse(pipeline.vct().enabled());
            assertFalse(pipeline.hairStrands().active());
            assertEquals(new Matrix4f(), pipeline.hairStrandModel());

            VctSceneInput sceneInput = VctSceneInput.builder(7L)
                    .addBox(new Vector3f(-1.0f), new Vector3f(1.0f), 1.0f, 0.5f, 0.5f)
                    .build();
            pipeline.vctSceneInput(sceneInput);
            assertSame(sceneInput, pipeline.vctSceneInput());

            VctBox dirty = VctBox.of(-1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f);
            pipeline.vctDirtyBoxes(List.of(dirty));
            assertThrows(UnsupportedOperationException.class,
                    () -> pipeline.vctDirtyBoxes().add(dirty),
                    "the staged dirty-region list must be immutable");

            VctProbeBatch probes = VctProbeBatch.builder()
                    .addProbe(new Vector3f(), new Vector3f(0.0f, 1.0f, 0.0f),
                            new Vector3f(0.0f, 0.0f, 1.0f), 0.4f)
                    .build();
            pipeline.vctProbes(probes);
            assertSame(probes, pipeline.vctProbes());

            pipeline.vctSurface(0.25f, new Vector3f(0.5f, 0.4f, 0.3f));
            assertEquals(0.25f, pipeline.vctSurfaceRoughness());
            assertEquals(new Vector3f(0.5f, 0.4f, 0.3f), pipeline.vctSurfaceAlbedo());
            assertThrows(IllegalArgumentException.class,
                    () -> pipeline.vctSurface(1.5f, new Vector3f(1.0f)));
            assertThrows(IllegalArgumentException.class,
                    () -> pipeline.vctSurface(0.5f, new Vector3f(-1.0f)));

            Matrix4f model = new Matrix4f().translation(1.0f, 2.0f, 3.0f);
            pipeline.hairStrandModel(model);
            assertEquals(model, pipeline.hairStrandModel());
            assertNotEquals(model, pipeline.hairStrandModel().translation(9.0f, 0.0f, 0.0f));
            assertThrows(IllegalArgumentException.class,
                    () -> pipeline.hairStrandModel(new Matrix4f().scale(Float.NaN)));
        } finally {
            pipeline.close();
        }
    }

    @Test
    void resizeKeepsFeatureTopologyAndCloseIsIdempotent() {
        Scene scene = new Scene(new Camera());
        scene.addLight(directional());
        PipelineTopology topology = PipelineTopology.capture(scene,
                hdr(AntiAliasingMode.NONE), PostProcessSettings.defaults(), 64, 48, false,
                false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                ddgi(), VctSettings.disabled(), hair());
        PipelineTopology resized = topology.withExtent(96, 72);
        assertEquals(96, resized.width());
        assertEquals(72, resized.height());
        assertTrue(resized.ddgi());
        assertTrue(resized.hairStrands());
        assertFalse(resized.voxelConeTracing());

        PipelineGeneration generation = new PipelineGeneration(resized);
        generation.close();
        generation.close();
        assertTrue(generation.isClosed());
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

    private static RenderFeaturePolicy.Facts facts(boolean directionalLightPresent) {
        return new RenderFeaturePolicy.Facts(false, true, true, true, false, false, false,
                false, false, directionalLightPresent);
    }

    private static SceneLight directional() {
        return SceneLight.shadowedDirectional(new Vector3f(0.0f, -1.0f, -0.3f),
                new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
    }

    private static DdgiSettings ddgi() {
        return DdgiSettings.builder().enabled(true).build();
    }

    private static VctSettings vct() {
        return VctSettings.enabled(32, 8.0f);
    }

    private static HairStrandSettings hair() {
        return HairStrandSettings.builder().build();
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
