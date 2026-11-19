package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.Map;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the PBR direct/IBL switch contract: with direct lighting (and
 * IBL) disabled, the image must not depend on the light intensity even when a
 * second material with overrides is drawn.
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class PbrLightingSwitchGlTest {
    @Test
    void disabledDirectLightingStaysIntensityIndependentWithTwoMaterials() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 Mesh mesh = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("pbr-switch-quad")).mesh())) {
                Material first = PbrMaterials.create(shader, properties(0.2f), Map.of(), fallbacks);
                Material second = PbrMaterials.create(shader, properties(0.9f), Map.of(), fallbacks);
                try {
                    Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 3.0f)));
                    scene.add(new SceneObject(mesh, first, (model, frame) ->
                            model.identity().translate(-1.2f, 0.0f, 0.0f)));
                    scene.add(new SceneObject(mesh, second, (model, frame) ->
                            model.identity().translate(1.2f, 0.0f, 0.0f)));
                    scene.addLight(SceneLight.directional(new Vector3f(0.2f, -0.3f, -1.0f),
                            new Vector3f(1.0f), 2.5f));
                    RenderSettings settings = RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build();
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            settings, environment);
                    try {
                        pipeline.features().submit(pipeline.features().effective().toBuilder()
                                .directLighting(false).imageBasedLighting(false).build());
                        pipeline.build();
                        pipeline.execute(device, 1.0f / 60.0f);
                        float[] bright = pipeline.captureLinearHdrRgbaFloat();

                        scene.setLight(0, SceneLight.directional(
                                new Vector3f(0.2f, -0.3f, -1.0f), new Vector3f(1.0f), 0.0f));
                        pipeline.execute(device, 1.0f / 60.0f);
                        float[] dark = pipeline.captureLinearHdrRgbaFloat();
                        assertArrayEquals(bright, dark, 0.0f,
                                "direct lighting disabled must make the image independent of "
                                        + "light intensity across multiple PBR materials");
                        assertTrue(hasNonBackground(bright),
                                "the fixture must contain visible PBR surfaces");
                        GlDebug.assertNoError("PBR lighting switch contract");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    second.close();
                    first.close();
                }
            }
            assertTrue(GlDebug.resources().liveResources().isEmpty());
        }
    }

    private static PbrMaterialProperties properties(float roughness) {
        return new PbrMaterialProperties(new Vector4f(0.7f, 0.6f, 0.5f, 1.0f), 0.0f,
                roughness, 1.0f, 1.0f, new Vector3f(), Map.of());
    }

    private static boolean hasNonBackground(float[] image) {
        for (int index = 0; index < image.length; index += 4) {
            if (image[index] > 1.0e-4f || image[index + 1] > 1.0e-4f
                    || image[index + 2] > 1.0e-4f) {
                return true;
            }
        }
        return false;
    }
}
