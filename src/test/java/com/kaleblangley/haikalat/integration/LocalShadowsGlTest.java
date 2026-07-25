package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.PointShadowAtlas;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SpotShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.Transform;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.Map;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class LocalShadowsGlTest {
    @Test
    void pointAtlasAndSpotDepthPassesChangeFinalPbrPixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/render3d/pbr/pbr_forward.vert", "/render3d/pbr/pbr_forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("local-shadow-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.72f, 0.68f, 0.62f, 1.0f),
                                0.0f, 0.8f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    RenderSettings settings = RenderSettings.builder()
                            .toneMappingMode(ToneMappingMode.ACES)
                            .bloomSettings(BloomSettings.disabled())
                            .vsync(false)
                            .build();
                    Scene unshadowed = scene(quad, material, false);
                    ByteBuffer baseline = render(window, device, unshadowed, settings, environment, null);

                    Scene shadowed = scene(quad, material, true);
                    RenderPipeline pipeline = new RenderPipeline(window, shadowed, null,
                            settings, environment);
                    try {
                        ByteBuffer withShadows = render(window, device, shadowed, settings,
                                environment, pipeline);

                        assertTrue(pipeline.graph().hasPass(PointShadowAtlas.PASS_NAME));
                        assertTrue(pipeline.graph().hasPass(SpotShadowMap.PASS_NAME));
                        assertEquals(6, pipeline.lastPointShadowCasterDrawCount(),
                                "one caster must be drawn into all six point-light atlas faces");
                        assertEquals(1, pipeline.lastSpotShadowCasterDrawCount());
                        assertTrue(changedRgbPixels(baseline, withShadows) > 2,
                                "local-light shadow maps must alter final PBR pixels");
                        assertEquals(GL_NO_ERROR, glGetError());
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                }
            }
        }
    }

    private static Scene scene(Mesh quad, Material material, boolean shadows) {
        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 5.0f)));
        scene.add(MeshRenderer.of(quad, material, Transform.at(0.0f, 0.0f, 0.0f).scale(3.2f))
                .withoutShadows());
        scene.add(MeshRenderer.of(quad, material,
                Transform.at(-0.45f, 0.18f, 0.9f).scale(0.72f)));
        Vector3f pointPosition = new Vector3f(1.4f, 1.0f, 3.2f);
        Vector3f spotPosition = new Vector3f(-1.5f, 1.2f, 3.4f);
        Vector3f spotDirection = new Vector3f(0.2f, -0.15f, -1.0f);
        if (shadows) {
            scene.addLight(SceneLight.shadowedPoint(pointPosition, new Vector3f(1.0f, 0.55f, 0.3f),
                    34.0f, 9.0f));
            scene.addLight(SceneLight.shadowedSpot(spotPosition, spotDirection,
                    new Vector3f(0.3f, 0.55f, 1.0f), 46.0f, 10.0f, 0.18f, 0.58f));
        } else {
            scene.addLight(SceneLight.point(pointPosition, new Vector3f(1.0f, 0.55f, 0.3f),
                    34.0f, 9.0f));
            scene.addLight(SceneLight.spot(spotPosition, spotDirection,
                    new Vector3f(0.3f, 0.55f, 1.0f), 46.0f, 10.0f, 0.18f, 0.58f));
        }
        return scene;
    }

    private static ByteBuffer render(GlfwWindow window, GlRenderDevice device, Scene scene,
                                     RenderSettings settings, PbrEnvironment environment,
                                     RenderPipeline supplied) {
        RenderPipeline pipeline = supplied == null
                ? new RenderPipeline(window, scene, null, settings, environment) : supplied;
        try {
            pipeline.build();
            pipeline.execute(device);
            ByteBuffer pixels = BufferUtils.createByteBuffer(window.width() * window.height() * 4);
            glReadPixels(0, 0, window.width(), window.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            return pixels;
        } finally {
            if (supplied == null) pipeline.close();
        }
    }

    private static int changedRgbPixels(ByteBuffer left, ByteBuffer right) {
        int changed = 0;
        for (int offset = 0; offset < Math.min(left.capacity(), right.capacity()); offset += 4) {
            int delta = Math.abs(Byte.toUnsignedInt(left.get(offset))
                    - Byte.toUnsignedInt(right.get(offset)))
                    + Math.abs(Byte.toUnsignedInt(left.get(offset + 1))
                    - Byte.toUnsignedInt(right.get(offset + 1)))
                    + Math.abs(Byte.toUnsignedInt(left.get(offset + 2))
                    - Byte.toUnsignedInt(right.get(offset + 2)));
            if (delta > 6) changed++;
        }
        return changed;
    }
}
