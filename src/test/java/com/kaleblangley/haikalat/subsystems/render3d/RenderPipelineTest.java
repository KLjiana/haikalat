package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.ColorGradingLut;
import com.kaleblangley.haikalat.subsystems.postprocess.ColorGradingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.backend.RenderFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderPipelineTest {
    @Test
    void frameOwnedUniformClassificationCoversLightingShadowAndEnvironmentOnly() {
        assertTrue(RenderPipeline.isFrameOwnedUniform("uDirectionalShadowFrameLightIndex"));
        assertTrue(RenderPipeline.isFrameOwnedUniform("uCameraPosition"));
        assertTrue(RenderPipeline.isFrameOwnedUniform("uShadowMap"));
        assertTrue(RenderPipeline.isFrameOwnedUniform("uPrefilteredMap"));
        assertTrue(RenderPipeline.isFrameOwnedUniform("uDirectionalCascadeMatrices[2]"));
        assertTrue(!RenderPipeline.isFrameOwnedUniform("uColor"));
        assertTrue(!RenderPipeline.isFrameOwnedUniform("uModel"));
    }

    @Test
    void autoExposureAlwaysBuildsResizeStableReductionTopology() {
        assertEquals(14, PostProcessPassBuilder.AUTO_EXPOSURE_REDUCTION_PASS_COUNT);
        assertEquals(13, PostProcessPassBuilder.AUTO_EXPOSURE_RELATIVE_PASS_COUNT);
        assertEquals(0.5f, PostProcessPassBuilder.autoExposureRelativeScale(0));
        assertEquals(1.0f / 8192.0f, PostProcessPassBuilder.autoExposureRelativeScale(12));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPassBuilder.autoExposureRelativeScale(13));
    }
    @Test
    void instancedRendererRequiresExplicitShadowOptIn() {
        InstancedRenderer defaults = new InstancedRenderer(null, null);
        InstancedRenderer enabled = new InstancedRenderer(null, null, true);

        assertTrue(!defaults.castShadows());
        assertTrue(enabled.castShadows());
    }

    @Test
    void passPlanUsesPresentPassForNoneAndMsaa() {
        List<String> expected = List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.PRESENT_PASS);

        assertEquals(expected, RenderPipeline.passNamesFor(AntiAliasingMode.NONE));
        assertEquals(expected, RenderPipeline.passNamesFor(AntiAliasingMode.MSAA));
    }

    @Test
    void passPlanUsesDedicatedPostprocessPassesForFxaaAndTaa() {
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.FXAA_PASS,
                        PostProcessTargets.PRESENT_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.FXAA));
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS,
                        PostProcessTargets.PRESENT_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.TAA));
    }

    @Test
    void hdrPassPlansKeepAaInTheRequiredColorSpace() {
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS,
                        PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.PRESENT_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.NONE, ToneMappingMode.ACES));
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS,
                        PostProcessTargets.HDR_RESOLVE_PASS,
                        PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.PRESENT_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.MSAA, ToneMappingMode.ACES));
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS,
                        PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.FXAA_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.FXAA, ToneMappingMode.ACES));
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS,
                        PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.PRESENT_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.TAA, ToneMappingMode.ACES));
    }

    @Test
    void finalBackbufferAnchorCoversEveryLdrAndHdrAaCombination() {
        for (AntiAliasingMode mode : AntiAliasingMode.values()) {
            assertEquals(PostProcessTargets.PRESENT_PASS,
                    PostProcessPassBuilder.finalPassNameFor(mode, ToneMappingMode.NONE),
                    "LDR final pass for " + mode);
            assertEquals(mode == AntiAliasingMode.FXAA
                            ? PostProcessTargets.FXAA_PASS : PostProcessTargets.PRESENT_PASS,
                    PostProcessPassBuilder.finalPassNameFor(mode, ToneMappingMode.ACES),
                    "HDR final pass for " + mode);
        }
    }

    @Test
    void finalPassNameRejectsQueriesBeforeBuildAndAfterClose() {
        RenderWindowStub window = new RenderWindowStub();
        RenderPipeline pipeline = new RenderPipeline(window, new Scene(new Camera()), null,
                RenderSettings.builder().build());

        assertThrows(IllegalStateException.class, pipeline::finalPassName);
        pipeline.close();
        assertThrows(IllegalStateException.class, pipeline::finalPassName);
    }

    @Test
    void postProcessBuilderPublishesOnlyTheRegisteredBackbufferPass() {
        RenderWindowStub window = new RenderWindowStub();
        try (PostProcessPassBuilder builder = PostProcessPassBuilder.create(
                RenderSettings.builder().build(), PostProcessSettings.defaults(),
                window, window.width(), window.height());
             RenderGraph graph = new RenderGraph(window.width(), window.height())) {
            assertThrows(IllegalStateException.class, builder::finalPassName);

            builder.addFinalPass(graph);

            assertEquals(PostProcessTargets.PRESENT_PASS, builder.finalPassName());
            assertTrue(graph.hasPass(builder.finalPassName()));
            assertTrue(graph.passWritesToBackbuffer(builder.finalPassName()));
            assertThrows(IllegalStateException.class, () -> builder.addFinalPass(graph));
        }
    }

    @Test
    void optionalPostEffectsRequireHdrAndFogAllowsManagedMsaaDepthResolve() {
        RenderWindowStub window = new RenderWindowStub();
        PostProcessSettings grading = PostProcessSettings.builder()
                .colorGrading(ColorGradingSettings.of(ColorGradingLut.identity(4), 1.0f))
                .build();
        PostProcessSettings fog = PostProcessSettings.builder()
                .fog(FogSettings.builder().build())
                .build();

        RenderPipeline ldr = new RenderPipeline(window, new Scene(new Camera()), null,
                RenderSettings.builder().build()).postProcessSettings(grading);
        assertThrows(IllegalStateException.class, ldr::build);

        RenderSettings msaa = RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                .antiAliasingMode(AntiAliasingMode.MSAA).build();
        PipelineTopology topology = PipelineTopology.capture(new Scene(new Camera()), msaa,
                fog, 640, 480, false, false, DirectionalCascadeSettings.disabled());
        assertDoesNotThrow(() -> new PipelineFeaturePolicy(topology, false).validate());
    }

    @Test
    void hdrPassPlanCanPrefixDirectionalShadowWithoutReordering() {
        assertEquals(List.of(DirectionalShadowMap.PASS_NAME,
                        PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS,
                        PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.PRESENT_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.TAA, ToneMappingMode.ACES, true));
    }

    @Test
    void bloomPassPlanRunsAfterHdrAaAndBeforeToneMapping() {
        BloomSettings bloom = BloomSettings.builder().enabled(true).maxLevels(3).build();

        assertEquals(List.of(
                        PostProcessTargets.GEOMETRY_PASS,
                        PostProcessTargets.TAA_PASS,
                        PostProcessTargets.BLOOM_EXTRACT_PASS,
                        PostProcessTargets.BLOOM_DOWN_PASS_PREFIX + "1",
                        PostProcessTargets.BLOOM_DOWN_PASS_PREFIX + "2",
                        PostProcessTargets.BLOOM_UP_PASS_PREFIX + "1",
                        PostProcessTargets.BLOOM_UP_PASS_PREFIX + "0",
                        PostProcessTargets.TONE_MAPPING_PASS,
                        PostProcessTargets.PRESENT_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.TAA, ToneMappingMode.ACES,
                        bloom, false));
    }

    @Test
    void hdrVfxRequiresHdr() {
        RenderWindowStub window = new RenderWindowStub();
        RenderPipeline ldr = new RenderPipeline(window, new Scene(new Camera()), null,
                RenderSettings.builder().build()).hdrVfx((resources, commands) -> { });
        assertThrows(IllegalStateException.class, ldr::build);

    }

    @Test
    void hdrUsesFloatTargetsWhileLdrUsesSrgbSceneAndHistoryFormats() {
        RenderSettings ldr = RenderSettings.builder().build();
        RenderSettings hdr = RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES).build();

        assertEquals(RenderFormat.SRGB8_ALPHA8, ForwardPassBuilder.sceneColorFormat(ldr));
        assertEquals(RenderFormat.SRGB8_ALPHA8, PostProcessPassBuilder.taaHistoryFormat(ldr));
        assertEquals(RenderFormat.RGBA16F, ForwardPassBuilder.sceneColorFormat(hdr));
        assertEquals(RenderFormat.RGBA16F, PostProcessPassBuilder.taaHistoryFormat(hdr));
    }

    @Test
    void postProcessPassBuilderCanPrefixShadowPass() {
        assertEquals(List.of(DirectionalShadowMap.PASS_NAME,
                        PostProcessTargets.GEOMETRY_PASS,
                        PostProcessTargets.TAA_PASS,
                        PostProcessTargets.PRESENT_PASS),
                PostProcessPassBuilder.passNamesFor(AntiAliasingMode.TAA, true));
    }

    @Test
    void frameLightTableCountsAndAddressesLightsByStableId() {
        Scene scene = new Scene(new Camera());
        SceneLight directional = SceneLight.directional(
                new Vector3f(-1, -1, -1), new Vector3f(1), 1.0f);
        SceneLight point = SceneLight.point(new Vector3f(1, 2, 3), new Vector3f(1), 2.0f, 10.0f);
        SceneLight spot = SceneLight.spot(new Vector3f(), new Vector3f(0, -1, 0),
                new Vector3f(1), 1.0f, 12.0f, 0.3f, 0.8f);
        scene.addLight(directional).addLight(point).addLight(spot);

        FrameLightTable table = FrameLightTable.build(scene.lightEntries(),
                new Matrix4f(), ClusteredLightingSettings.defaults());

        assertEquals(1, table.directionalCount());
        assertEquals(2, table.localCount());
        assertEquals(3, table.totalCount());
        assertEquals(0, table.frameLightIndex(scene.lightEntries().get(0).stableId()));
        assertEquals(1, table.frameLightIndex(scene.lightEntries().get(1).stableId()));
        assertEquals(2, table.frameLightIndex(scene.lightEntries().get(2).stableId()));
        assertEquals(LightType.POINT, table.record(1).type());
        assertEquals(LightType.SPOT, table.record(2).type());
    }

    @Test
    void frameLightTableOrdersLocalLightsByStableId() {
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.point(new Vector3f(1, 0, 0), new Vector3f(1), 1.0f, 5.0f));
        scene.addLight(SceneLight.point(new Vector3f(2, 0, 0), new Vector3f(1), 1.0f, 5.0f));
        long first = scene.lightEntries().get(0).stableId();
        long second = scene.lightEntries().get(1).stableId();

        FrameLightTable table = FrameLightTable.build(scene.lightEntries(),
                new Matrix4f(), ClusteredLightingSettings.defaults());

        assertEquals(0, table.frameLightIndex(first));
        assertEquals(1, table.frameLightIndex(second));
        assertTrue(table.record(0).stableId() < table.record(1).stableId());
    }

    @Test
    void frameLightTableRejectsExceedingConfiguredCapacity() {
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.point(new Vector3f(1, 0, 0), new Vector3f(1), 1.0f, 5.0f));
        scene.addLight(SceneLight.point(new Vector3f(2, 0, 0), new Vector3f(1), 1.0f, 5.0f));
        ClusteredLightingSettings settings = ClusteredLightingSettings.builder()
                .maxLocalLights(1).build();

        assertThrows(IllegalStateException.class, () -> FrameLightTable.build(
                scene.lightEntries(), new Matrix4f(), settings));
    }

    @Test
    void frameLightTableIsFrozenAgainstLaterSceneMutation() {
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.point(new Vector3f(1, 2, 3), new Vector3f(1), 2.0f, 10.0f));
        FrameLightTable table = FrameLightTable.build(scene.lightEntries(),
                new Matrix4f(), ClusteredLightingSettings.defaults());
        scene.setLight(0, SceneLight.point(new Vector3f(9, 9, 9), new Vector3f(0), 0.0f, 10.0f));

        assertEquals(2.0f, table.record(0).intensity());
        assertEquals(1.0f, table.record(0).position().x);
    }

    @Test
    void lightTablePackerWritesTheLockedStd430Layout() {
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.shadowedDirectional(
                new Vector3f(0, -1, 0), new Vector3f(1, 1, 1), 2.0f));
        scene.addLight(SceneLight.point(new Vector3f(1, 2, 3), new Vector3f(1, 0, 0),
                4.0f, 10.0f));
        FrameLightTable table = FrameLightTable.build(scene.lightEntries(),
                new Matrix4f(), ClusteredLightingSettings.defaults());
        java.nio.ByteBuffer packed = LightTablePacker.allocate(8);
        LightTablePacker.pack(table, ShadowFramePlan.EMPTY, packed);

        assertEquals(1, packed.getInt(0));
        assertEquals(1, packed.getInt(4));
        assertEquals(2, packed.getInt(8));
        assertEquals(0, packed.getInt(LightTablePacker.HEADER_BYTES
                + LightTablePacker.METADATA_OFFSET));
        assertEquals(2.0f, packed.getFloat(LightTablePacker.HEADER_BYTES
                + LightTablePacker.COLOR_INTENSITY_OFFSET + 12));
        assertEquals(1, packed.getInt(LightTablePacker.HEADER_BYTES
                + LightTablePacker.RECORD_BYTES + LightTablePacker.METADATA_OFFSET));
        assertEquals(1.0f, packed.getFloat(LightTablePacker.HEADER_BYTES
                + LightTablePacker.RECORD_BYTES
                + LightTablePacker.POSITION_RANGE_OFFSET));
        assertEquals(10.0f, packed.getFloat(LightTablePacker.HEADER_BYTES
                + LightTablePacker.RECORD_BYTES
                + LightTablePacker.POSITION_RANGE_OFFSET + 12));
    }

    @Test
    void sceneLightRejectsDegenerateRuntimeInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> SceneLight.directional(new Vector3f(), new Vector3f(1), 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> SceneLight.point(new Vector3f(), new Vector3f(1), 1.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> SceneLight.spot(new Vector3f(), new Vector3f(0, -1, 0), new Vector3f(1),
                        1.0f, 10.0f, 0.8f, 0.3f));
    }

    @Test
    void cameraUniformsAppliesJitterOnlyForTaa() {
        Matrix4f none = new Matrix4f();
        Matrix4f taa = new Matrix4f();

        CameraUniforms.applyTemporalJitter(none, 100, 100, AntiAliasingMode.FXAA, 0);
        CameraUniforms.applyTemporalJitter(taa, 100, 100, AntiAliasingMode.TAA, 0);

        assertEquals(0.0f, none.m20(), 1.0e-6f);
        assertEquals(0.0f, none.m21(), 1.0e-6f);
        assertNotEquals(0.0f, taa.m20(), 1.0e-6f);
        assertNotEquals(0.0f, taa.m21(), 1.0e-6f);
    }

    private static final class RenderWindowStub
            implements com.kaleblangley.haikalat.subsystems.windowing.RenderWindow {
        @Override
        public int width() {
            return 800;
        }

        @Override
        public int height() {
            return 600;
        }
    }
}
