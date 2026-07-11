package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
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
    void passPlanUsesPresentPassForNoneAndMsaa() {
        List<String> expected = List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.PRESENT_PASS);

        assertEquals(expected, RenderPipeline.passNamesFor(AntiAliasingMode.NONE));
        assertEquals(expected, RenderPipeline.passNamesFor(AntiAliasingMode.MSAA));
    }

    @Test
    void passPlanUsesDedicatedPostprocessPassesForFxaaAndTaa() {
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.FXAA_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.FXAA));
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.TAA));
    }

    @Test
    void postProcessPassBuilderCanPrefixShadowPass() {
        assertEquals(List.of(DirectionalShadowMap.PASS_NAME,
                        PostProcessTargets.GEOMETRY_PASS,
                        PostProcessTargets.TAA_PASS),
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
