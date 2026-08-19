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
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.PointShadowAtlas;
import com.kaleblangley.haikalat.subsystems.render3d.Render3dDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowFilterMode;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSelectionMode;
import com.kaleblangley.haikalat.subsystems.render3d.SpotShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.Transform;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
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
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BLOCK_BINDING;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BLOCK_DATA_SIZE;
import static org.lwjgl.opengl.GL31.glGetActiveUniformBlocki;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class LocalShadowsGlTest {
    @Test
    void shadowSamplingBlockHasTheLockedStd140SizeAndBinding() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward-shadow-budget.frag")) {
                int block = shader.uniformBlockIndex("ShadowSamplingBlock");
                assertEquals(1_392,
                        glGetActiveUniformBlocki(shader.id(), block, GL_UNIFORM_BLOCK_DATA_SIZE));
                assertEquals(5,
                        glGetActiveUniformBlocki(shader.id(), block, GL_UNIFORM_BLOCK_BINDING));
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void directionalCascadeCacheSurvivesStableCameraResizeAndNonCasterChanges() {
        try (GlfwWindow context = hiddenWindow()) {
            context.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward-shadow-budget.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("directional-cache-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.7f, 0.68f, 0.62f, 1.0f),
                                0.0f, 0.8f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    Camera camera = new Camera(new Vector3f(0.0f, 0.5f, 6.0f));
                    Scene scene = new Scene(camera);
                    Transform receiver = Transform.at(0.0f, 0.0f, 0.0f).scale(3.2f);
                    Transform caster = Transform.at(0.0f, 0.15f, 1.0f).scale(0.65f);
                    scene.add(MeshRenderer.of(quad, material, receiver).withoutShadows());
                    scene.add(MeshRenderer.of(quad, material, caster));
                    scene.addLight(SceneLight.shadowedDirectional(
                            new Vector3f(-0.6f, -1.0f, -0.45f), new Vector3f(1.0f), 3.0f));
                    RenderSettings settings = RenderSettings.builder()
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build();
                    MutableWindow viewport = new MutableWindow(640, 360);
                    RenderPipeline pipeline = new RenderPipeline(viewport, scene, null,
                            settings, environment)
                            .directionalCascades(new DirectionalCascadeSettings(
                                    4, 512, 0.6f, 0.08f))
                            .localShadows(LocalShadowPipelineSettings.balanced());
                    try {
                        pipeline.build();
                        pipeline.execute(device);
                        assertEquals(4, pipeline.lastShadowCasterDrawCount());
                        assertEquals(4, pipeline.lastRender3dDiagnostics().shadows().tilesRendered());

                        pipeline.execute(device);
                        assertEquals(0, pipeline.lastShadowCasterDrawCount());
                        assertEquals(4, pipeline.lastRender3dDiagnostics().shadows().tilesReused());

                        receiver.position(0.15f, 0.0f, 0.0f);
                        pipeline.execute(device);
                        assertEquals(0, pipeline.lastShadowCasterDrawCount(),
                                "castShadows=false changes must not invalidate shadow depth");

                        camera.setPosition(new Vector3f(0.00001f, 0.5f, 6.0f));
                        pipeline.execute(device);
                        assertEquals(0, pipeline.lastShadowCasterDrawCount(),
                                "sub-texel stabilized camera movement must reuse CSM tiles");

                        viewport.resize(800, 450);
                        pipeline.resize(800, 450);
                        pipeline.execute(device);
                        assertEquals(0, pipeline.lastShadowCasterDrawCount(),
                                "same-aspect target resize must retain fixed CSM content");
                        var directionalPass = pipeline.graph().description().passes().stream()
                                .filter(pass -> pass.name().equals("DirectionalShadowPass"))
                                .findFirst().orElseThrow();
                        assertEquals(512, directionalPass.width());
                        assertEquals(512, directionalPass.height());

                        caster.position(0.2f, 0.15f, 1.0f);
                        pipeline.execute(device);
                        assertEquals(4, pipeline.lastShadowCasterDrawCount());
                        assertEquals(4, pipeline.lastRender3dDiagnostics().shadows().tilesRendered());
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

    @Test
    void balancedAtlasCachesStaticTilesAndInvalidatesOnlyTheMovedLight() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward-shadow-budget.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("balanced-shadow-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.65f, 0.7f, 0.75f, 1.0f),
                                0.0f, 0.75f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    RenderSettings settings = RenderSettings.builder()
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build();
                    Scene scene = balancedScene(quad, material);
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            settings, environment).localShadows(LocalShadowPipelineSettings.balanced());
                    try {
                        pipeline.build();
                        pipeline.execute(device);
                        Render3dDiagnostics.ShadowSummary first =
                                pipeline.lastRender3dDiagnostics().shadows();
                        assertEquals(12, pipeline.lastPointShadowCasterDrawCount());
                        assertEquals(4, pipeline.lastSpotShadowCasterDrawCount());
                        assertEquals(16, first.tilesRendered());
                        assertEquals(2, first.pointSelected());
                        assertEquals(4, first.spotSelected());

                        pipeline.execute(device);
                        Render3dDiagnostics.ShadowSummary stable =
                                pipeline.lastRender3dDiagnostics().shadows();
                        assertEquals(0, pipeline.lastPointShadowCasterDrawCount());
                        assertEquals(0, pipeline.lastSpotShadowCasterDrawCount());
                        assertEquals(0, stable.tilesRendered());
                        assertEquals(16, stable.tilesReused());

                        scene.setLight(0, SceneLight.shadowedPoint(
                                new Vector3f(-1.15f, 1.4f, 2.7f), new Vector3f(1, 0.3f, 0.2f),
                                28.0f, 8.0f));
                        pipeline.execute(device);
                        Render3dDiagnostics.ShadowSummary movedPoint =
                                pipeline.lastRender3dDiagnostics().shadows();
                        assertEquals(6, pipeline.lastPointShadowCasterDrawCount());
                        assertEquals(0, pipeline.lastSpotShadowCasterDrawCount());
                        assertEquals(6, movedPoint.tilesRendered());
                        assertEquals(10, movedPoint.tilesReused());

                        scene.setLight(2, SceneLight.shadowedSpot(
                                new Vector3f(-1.5f, 2.8f, 3.2f),
                                new Vector3f(0.2f, -0.7f, -1.0f), new Vector3f(0.3f, 0.5f, 1),
                                32.0f, 9.0f, 0.15f, 0.55f));
                        pipeline.execute(device);
                        assertEquals(0, pipeline.lastPointShadowCasterDrawCount());
                        assertEquals(1, pipeline.lastSpotShadowCasterDrawCount());
                        assertEquals(1, pipeline.lastRender3dDiagnostics().shadows().tilesRendered());
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

    @Test
    void pointAtlasAndSpotDepthPassesChangeFinalPbrPixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("local-shadow-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality())) {
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

    @Test
    void allFilterPresetsProduceStableGuardedShadowPixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward-shadow-budget.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("filter-shadow-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.72f, 0.68f, 0.62f, 1.0f),
                                0.0f, 0.8f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    RenderSettings renderSettings = RenderSettings.builder()
                            .toneMappingMode(ToneMappingMode.ACES)
                            .bloomSettings(BloomSettings.disabled())
                            .vsync(false)
                            .build();
                    ByteBuffer unshadowed = render(window, device, scene(quad, material, false),
                            renderSettings, environment, null);
                    LocalShadowSettings local = LocalShadowSettings.defaults();
                    for (ShadowFilterMode filter : ShadowFilterMode.values()) {
                        Scene shadowed = scene(quad, material, true);
                        LocalShadowPipelineSettings quality = new LocalShadowPipelineSettings(
                                local, local, 1, 1, ShadowSelectionMode.SCENE_ORDER,
                                filter, 0.002f, 1.15f, true);
                        RenderPipeline pipeline = new RenderPipeline(window, shadowed, null,
                                renderSettings, environment).localShadows(quality);
                        try {
                            ByteBuffer first = render(window, device, shadowed, renderSettings,
                                    environment, pipeline);
                            assertTrue(changedRgbPixels(unshadowed, first) > 2,
                                    filter + " must retain a visible local-shadow footprint");
                            assertEquals(filter.name(),
                                    pipeline.lastRender3dDiagnostics().shadows().filterMode());

                            pipeline.execute(device);
                            ByteBuffer stable = capture(window);
                            assertEquals(0, changedRgbPixels(first, stable),
                                    filter + " static cached frame must be pixel-identical");
                            assertEquals(0,
                                    pipeline.lastRender3dDiagnostics().shadows().tilesRendered());
                            assertEquals(7,
                                    pipeline.lastRender3dDiagnostics().shadows().tilesReused());
                            assertEquals(GL_NO_ERROR, glGetError());
                        } finally {
                            pipeline.close();
                        }
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

    private static Scene balancedScene(Mesh quad, Material material) {
        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.5f, 6.0f)));
        scene.add(MeshRenderer.of(quad, material,
                Transform.at(0.0f, 0.0f, 0.0f).scale(3.2f)).withoutShadows());
        scene.add(MeshRenderer.of(quad, material,
                Transform.at(0.0f, 0.15f, 1.0f).scale(0.65f)));
        scene.addLight(SceneLight.shadowedPoint(new Vector3f(-1.4f, 1.2f, 3.0f),
                new Vector3f(1, 0.3f, 0.2f), 28.0f, 8.0f));
        scene.addLight(SceneLight.shadowedPoint(new Vector3f(1.4f, 1.2f, 3.0f),
                new Vector3f(0.2f, 1, 0.35f), 28.0f, 8.0f));
        for (int index = 0; index < 4; index++) {
            float x = index % 2 == 0 ? -1.6f : 1.6f;
            float y = index < 2 ? 2.8f : -1.5f;
            scene.addLight(SceneLight.shadowedSpot(new Vector3f(x, y, 3.2f),
                    new Vector3f(-x * 0.2f, -y * 0.2f, -1.0f),
                    new Vector3f(index == 0 ? 0.3f : 0.7f,
                            index == 1 ? 0.35f : 0.7f, index >= 2 ? 1.0f : 0.5f),
                    24.0f, 9.0f, 0.15f, 0.55f));
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
            return capture(window);
        } finally {
            if (supplied == null) pipeline.close();
        }
    }

    private static ByteBuffer capture(GlfwWindow window) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(window.width() * window.height() * 4);
        glReadPixels(0, 0, window.width(), window.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
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

    private static final class MutableWindow implements RenderWindow {
        private int width;
        private int height;

        private MutableWindow(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        private void resize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
