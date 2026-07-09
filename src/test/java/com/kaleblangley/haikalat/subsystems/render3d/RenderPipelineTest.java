package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
