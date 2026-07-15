package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.backend.RenderFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderPipelineTest {
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
    void lightingBinderCountsLightTypesWithoutGlContext() {
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.directional(new Vector3f(-1, -1, -1), new Vector3f(1), 1.0f));
        scene.addLight(SceneLight.point(new Vector3f(1, 2, 3), new Vector3f(1), 2.0f, 10.0f));
        scene.addLight(SceneLight.spot(new Vector3f(), new Vector3f(0, -1, 0),
                new Vector3f(1), 1.0f, 12.0f, 0.3f, 0.8f));

        LightingBinder.LightCounts counts = LightingBinder.count(scene);

        assertEquals(1, counts.directional());
        assertEquals(1, counts.point());
        assertEquals(1, counts.spot());
    }

    @Test
    void lightingBinderClampsCountsToShaderArrayLimits() {
        Scene scene = new Scene(new Camera());
        for (int i = 0; i < LightingBinder.MAX_DIRECTIONAL_LIGHTS + 3; i++) {
            scene.addLight(SceneLight.directional(new Vector3f(-1, -1, -1), new Vector3f(1), 1.0f));
        }
        for (int i = 0; i < LightingBinder.MAX_POINT_LIGHTS + 3; i++) {
            scene.addLight(SceneLight.point(new Vector3f(i, 0, 0), new Vector3f(1), 1.0f, 10.0f));
        }

        LightingBinder.LightCounts counts = LightingBinder.count(scene);

        assertEquals(LightingBinder.MAX_DIRECTIONAL_LIGHTS, counts.directional());
        assertEquals(LightingBinder.MAX_POINT_LIGHTS, counts.point());
    }

    @Test
    void shadowDirectionalIndexMatchesOriginalShaderLightOrder() {
        Scene scene = new Scene(new Camera());
        SceneLight unshadowed = SceneLight.directional(
                new Vector3f(1, -1, 0), new Vector3f(1), 0.5f);
        SceneLight shadowed = SceneLight.shadowedDirectional(
                new Vector3f(-1, -1, 0), new Vector3f(1), 1.0f);
        scene.addLight(unshadowed).addLight(shadowed);

        LightingBinder.ShadowDirectionalLight selection =
                LightingBinder.shadowDirectionalLight(scene).orElseThrow();

        assertEquals(shadowed, selection.light());
        assertEquals(1, selection.shaderIndex());
    }

    @Test
    void shadowLightOutsideDirectionalShaderLimitIsNotSelected() {
        Scene scene = new Scene(new Camera());
        for (int i = 0; i < LightingBinder.MAX_DIRECTIONAL_LIGHTS; i++) {
            scene.addLight(SceneLight.directional(new Vector3f(i + 1, -1, 0), new Vector3f(1), 1.0f));
        }
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(0, -1, -1), new Vector3f(1), 1.0f));

        assertTrue(LightingBinder.shadowDirectionalLight(scene).isEmpty());
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
}
