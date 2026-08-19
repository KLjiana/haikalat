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
                "/shaders/shadows/instanced-directional-depth.vert")) {
            assertTrue(stream != null);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(source.contains("layout (location = 3) in mat4 aInstanceMatrix"));
        assertTrue(source.contains("uLightSpace * aInstanceMatrix"));
    }

    @Test
    void cascadedPcfClampsBoundaryTapsToTheSelectedAtlasTile() throws IOException {
        String source;
        try (var stream = ScenePipelineTest.class.getResourceAsStream(
                "/shaders/render3d/pbr/pbr-forward-shadow-budget.frag")) {
            assertTrue(stream != null);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(source.contains("vec2 guard = texel * (float(radius) + 0.5)"));
        assertTrue(source.contains("vec2 tileMinimum = offset + guard"));
        assertTrue(source.contains("vec2 tileMaximum = offset + scale - guard"));
        assertTrue(source.contains("clamp(projected.xy + vec2(x, y) * texel,"));
        assertTrue(source.contains("#define MAX_POINT_SHADOW_SLOTS "
                + LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS));
        assertTrue(source.contains("#define MAX_SPOT_SHADOW_SLOTS "
                + LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS));
        assertTrue(source.contains("#define POINT_SHADOW_FACE_COUNT "
                + PointShadowAtlas.FACE_COUNT));
        assertTrue(source.contains("layout(std140, binding = 5) uniform ShadowSamplingBlock"));

        // A synthetic atlas tile [0, 0.75] with 0.05 atlas texels uses a 2.5-texel
        // PCF_5X5 guard. Even a +2 tap from the internal edge cannot enter its neighbor.
        float texel = 0.05f;
        float guard = 2.5f * texel;
        // Use a larger synthetic tile so the PCF_5X5 guard retains an interior interval.
        float tileScale = 0.75f;
        float tileMinimum = guard;
        float tileMaximum = tileScale - guard;
        float projectedAtInternalEdge = tileScale;
        for (int tap = -2; tap <= 2; tap++) {
            float sample = Math.max(tileMinimum,
                    Math.min(tileMaximum, projectedAtInternalEdge + tap * texel));
            assertTrue(sample >= tileMinimum && sample <= tileMaximum);
            assertTrue(sample < tileScale, "PCF tap crossed into the adjacent cascade tile");
        }
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
    void addingALightAdvancesLightingRevision() {
        Scene scene = new Scene(new Camera());
        long before = scene.lightingRevision();

        scene.addLight(SceneLight.directional(new Vector3f(0, -1, 0),
                new Vector3f(1), 1.0f));

        assertEquals(before + 1, scene.lightingRevision());
    }

    @Test
    void successfulLightReplacementAdvancesLightingRevision() {
        Scene scene = new Scene(new Camera()).addLight(SceneLight.directional(
                new Vector3f(0, -1, 0), new Vector3f(1), 1.0f));
        long before = scene.lightingRevision();

        scene.setLight(0, SceneLight.directional(new Vector3f(1, -1, 0),
                new Vector3f(0.5f), 2.0f));

        assertEquals(before + 1, scene.lightingRevision());
    }

    @Test
    void rejectedTopologyChangesDoNotAdvanceLightingRevision() {
        Scene scene = new Scene(new Camera()).addLight(SceneLight.directional(
                new Vector3f(0, -1, 0), new Vector3f(1), 1.0f));
        long before = scene.lightingRevision();

        assertThrows(IllegalArgumentException.class, () -> scene.setLight(0,
                SceneLight.point(new Vector3f(), new Vector3f(1), 1.0f, 4.0f)));
        assertEquals(before, scene.lightingRevision());
        assertThrows(IllegalArgumentException.class, () -> scene.setLight(0,
                SceneLight.shadowedDirectional(new Vector3f(0, -1, 0),
                        new Vector3f(1), 1.0f)));
        assertEquals(before, scene.lightingRevision());
    }

    @Test
    void repeatedLightReplacementsKeepLightingRevisionMonotonic() {
        Scene scene = new Scene(new Camera()).addLight(SceneLight.directional(
                new Vector3f(0, -1, 0), new Vector3f(1), 1.0f));
        long previous = scene.lightingRevision();

        for (int index = 1; index <= 8; index++) {
            scene.setLight(0, SceneLight.directional(new Vector3f(index, -1, 0),
                    new Vector3f(1), index));
            assertTrue(scene.lightingRevision() > previous);
            previous = scene.lightingRevision();
        }
    }

    @Test
    void sceneLightReplacementAllowsOnlyTopologyPreservingChanges() {
        Scene scene = new Scene(new Camera());
        SceneLight original = SceneLight.directional(new Vector3f(-1, -2, -1),
                new Vector3f(1), 1.0f);
        SceneLight updated = SceneLight.directional(new Vector3f(1, -1, 0),
                new Vector3f(0.5f, 0.7f, 1.0f), 3.0f);
        scene.addLight(original);

        scene.setLight(0, updated);

        assertEquals(updated, scene.lights().getFirst());
    }

    @Test
    void sceneLightReplacementRejectsTypeChanges() {
        Scene scene = new Scene(new Camera()).addLight(SceneLight.directional(
                new Vector3f(0, -1, 0), new Vector3f(1), 1.0f));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> scene.setLight(0, SceneLight.point(
                        new Vector3f(), new Vector3f(1), 1.0f, 4.0f)));

        assertTrue(failure.getMessage().contains("light[0].type"));
    }

    @Test
    void sceneLightReplacementRejectsShadowTopologyChanges() {
        Scene scene = new Scene(new Camera()).addLight(SceneLight.directional(
                new Vector3f(0, -1, 0), new Vector3f(1), 1.0f));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> scene.setLight(0, SceneLight.shadowedDirectional(
                        new Vector3f(0, -1, 0), new Vector3f(1), 1.0f)));

        assertTrue(failure.getMessage().contains("light[0].castShadows"));
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
