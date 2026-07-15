package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenePipelineTest {
    @Test
    void instancedShadowShaderUsesBatchMatrixAttributeLocations() throws IOException {
        String source;
        try (var stream = ScenePipelineTest.class.getResourceAsStream(
                "/shadows/instanced_directional_depth.vert")) {
            assertTrue(stream != null);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(source.contains("layout (location = 3) in mat4 aInstanceMatrix"));
        assertTrue(source.contains("uLightSpace * aInstanceMatrix"));
    }

    @Test
    void sceneTracksLightsAndShadowCastingDirectionalLight() {
        Scene scene = new Scene(new Camera());
        SceneLight fill = SceneLight.point(new Vector3f(1, 2, 3), new Vector3f(1, 0.8f, 0.7f), 2.0f, 10.0f);
        SceneLight sun = SceneLight.shadowedDirectional(new Vector3f(-1, -2, -1), new Vector3f(1, 1, 1), 1.0f);

        scene.addLight(fill).addLight(sun);

        assertEquals(2, scene.lights().size());
        assertTrue(scene.hasShadowCastingDirectionalLight());
        assertEquals(sun, scene.firstShadowCastingDirectionalLight().orElseThrow());
        assertEquals(1.0f, sun.direction().length(), 1.0e-6f);
    }

    @Test
    void transformBuildsModelMatrix() {
        Transform transform = Transform.at(1.0f, 2.0f, 3.0f).scale(2.0f);
        Vector3f transformedOrigin = transform.matrix().transformPosition(new Vector3f(0, 0, 0));

        assertEquals(new Vector3f(1.0f, 2.0f, 3.0f), transformedOrigin);
    }

    @Test
    void passPlanCanIncludeDirectionalShadowPass() {
        assertEquals(List.of(DirectionalShadowMap.PASS_NAME,
                        PostProcessTargets.GEOMETRY_PASS,
                        PostProcessTargets.PRESENT_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.NONE, true));
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.FXAA_PASS,
                        PostProcessTargets.PRESENT_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.FXAA, false));
    }

    @Test
    void directionalShadowMapProvidesDepthDescriptorAndLightMatrix() {
        DirectionalShadowMap shadowMap = new DirectionalShadowMap(new ShadowSettings(1024, 12.0f, 0.1f, 50.0f));
        SceneLight light = SceneLight.shadowedDirectional(new Vector3f(-1, -1, -1), new Vector3f(1, 1, 1), 1.0f);

        Matrix4f matrix = shadowMap.lightSpaceMatrix(light, new Vector3f());

        assertEquals(1024, shadowMap.descriptor().width());
        assertEquals(1024, shadowMap.descriptor().height());
        assertTrue(shadowMap.descriptor().colorAttachments().isEmpty());
        assertTrue(Float.isFinite(matrix.m00()));
        assertThrows(IllegalArgumentException.class,
                () -> shadowMap.lightSpaceMatrix(SceneLight.point(new Vector3f(), new Vector3f(1), 1, 1), new Vector3f()));
    }

    @Test
    void directionalShadowMatrixHandlesWorldUpParallelDirection() {
        DirectionalShadowMap shadowMap = DirectionalShadowMap.defaults();
        SceneLight light = SceneLight.shadowedDirectional(new Vector3f(0, -1, 0), new Vector3f(1), 1.0f);

        Matrix4f matrix = shadowMap.lightSpaceMatrix(light, new Vector3f());

        float[] values = matrix.get(new float[16]);
        for (float value : values) {
            assertTrue(Float.isFinite(value));
        }
    }
}
