package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
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
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import org.joml.Vector3f;
import org.joml.Vector4f;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiles the v0.23.2 GTAO and PBR shader contracts in a real GL context. */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GtaoShaderGlTest {
    @Test
    void gtaoAndPbrProgramsCompileTogether() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            try (ShaderProgram estimate = program("gtao-estimate.frag");
                 ShaderProgram temporal = program("gtao-temporal.frag");
                 ShaderProgram denoise = program("gtao-denoise.frag");
                 ShaderProgram upsample = program("gtao-upsample.frag");
                 ShaderProgram depth = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/gtao/gtao-depth.vert",
                         "/shaders/render3d/gtao/gtao-depth.frag");
                 ShaderProgram maskedDepth = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/gtao/gtao-masked-depth.vert",
                         "/shaders/render3d/gtao/gtao-masked-depth.frag");
                 ShaderProgram pbr = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 ShaderProgram pbrBudget = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward-shadow-budget.frag")) {
                assertFalse(estimate.isCompute());
                assertFalse(temporal.isCompute());
                assertFalse(denoise.isCompute());
                assertFalse(upsample.isCompute());
                assertFalse(depth.isCompute());
                assertFalse(maskedDepth.isCompute());
                assertFalse(pbr.isCompute());
                assertFalse(pbrBudget.isCompute());
            }
            GlDebug.checkError("gtaoAndPbrProgramsCompileTogether");
        }
    }

    @Test
    void enabledGtaoBuildsDepthAndHalfResolutionGraph() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 Mesh mesh = Mesh.from(pbrTriangle())) {
                var material = PbrMaterials.create(shader, new PbrMaterialProperties(
                        new Vector4f(0.8f, 0.3f, 0.1f, 1.0f), 0.0f, 0.6f,
                        1.0f, 1.0f, new Vector3f(), java.util.Map.of()),
                        java.util.Map.of(), fallbacks);
                try {
                    Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 3.0f));
                    Scene scene = new Scene(camera)
                            .add(new SceneObject(mesh, material, (out, frame) -> out.identity(), false))
                            .addLight(SceneLight.directional(new Vector3f(0.0f, 0.0f, -1.0f),
                                    new Vector3f(1.0f), 2.0f));
                    RenderSettings renderSettings = RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .bloomSettings(BloomSettings.disabled())
                            .build();
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            renderSettings, environment)
                            .postProcessSettings(PostProcessSettings.builder()
                                    .gtao(GtaoSettings.defaults().withEnabled(true)).build());
                    try {
                        pipeline.build();
                        var descriptions = pipeline.graph().description().passes();
                        assertTrue(descriptions.stream().anyMatch(value ->
                                value.name().equals("GtaoDepthPrepass")));
                        var estimate = descriptions.stream().filter(value ->
                                value.name().equals("GtaoEstimatePass")).findFirst().orElseThrow();
                        assertEquals((pipeline.graph().width() + 1) / 2, estimate.width());
                        assertEquals((pipeline.graph().height() + 1) / 2, estimate.height());
                        int oldWidth = pipeline.graph().width();
                        int oldHeight = pipeline.graph().height();
                        int resizedWidth = oldWidth + 16;
                        int resizedHeight = oldHeight + 12;
                        System.setProperty("haikalat.test.failGraphResizeAllocation", "true");
                        try {
                            assertThrows(IllegalStateException.class,
                                    () -> pipeline.resize(resizedWidth, resizedHeight));
                        } finally {
                            System.clearProperty("haikalat.test.failGraphResizeAllocation");
                        }
                        assertEquals(oldWidth, pipeline.graph().width());
                        assertEquals(oldHeight, pipeline.graph().height());
                        System.setProperty("haikalat.test.failGtaoResizeAllocation", "true");
                        try {
                            assertThrows(IllegalStateException.class,
                                    () -> pipeline.resize(resizedWidth, resizedHeight));
                        } finally {
                            System.clearProperty("haikalat.test.failGtaoResizeAllocation");
                        }
                        assertEquals(oldWidth, pipeline.graph().width());
                        assertEquals(oldHeight, pipeline.graph().height());
                        // A failed GTAO candidate must not make the next resize
                        // look like a no-op just because the graph was touched
                        // while preparing the previous transaction.  Retry the
                        // exact extent and verify every resource family commits
                        // together.
                        pipeline.resize(resizedWidth, resizedHeight);
                        assertEquals(resizedWidth, pipeline.graph().width());
                        assertEquals(resizedHeight, pipeline.graph().height());
                        var resizedEstimate = pipeline.graph().description().passes().stream()
                                .filter(value -> value.name().equals("GtaoEstimatePass"))
                                .findFirst().orElseThrow();
                        assertEquals((resizedWidth + 1) / 2, resizedEstimate.width());
                        assertEquals((resizedHeight + 1) / 2, resizedEstimate.height());
                        pipeline.execute(device);
                        var ambientOcclusion = pipeline.lastRender3dDiagnostics().ambientOcclusion();
                        assertTrue(ambientOcclusion.enabled());
                        assertTrue(ambientOcclusion.depthPrepassDraws() > 0);
                        assertTrue(ambientOcclusion.historyValid());
                        // A large camera displacement must reject the previous
                        // history and rebuild it without a stale disocclusion.
                        camera.setPosition(new Vector3f(0.75f, 0.0f, 3.0f));
                        pipeline.execute(device);
                        assertTrue(pipeline.lastRender3dDiagnostics().ambientOcclusion().historyValid());
                        GlDebug.checkError("enabled GTAO pipeline");
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
    void gtaoChangesPbrPixelsAcrossAntiAliasingModes() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(96, 96)
                .title("GTAO pixel A/B")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(pbrOcclusionMesh());
            ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
            PbrFallbackTextures fallbacks = new PbrFallbackTextures();
            var material = PbrMaterials.create(shader, new PbrMaterialProperties(
                    new Vector4f(0.72f, 0.42f, 0.18f, 1.0f), 0.0f, 0.72f,
                    1.0f, 1.0f, new Vector3f(), java.util.Map.of()),
                    java.util.Map.of(), fallbacks);
            Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 3.0f)))
                    .add(new SceneObject(mesh, material, (out, frame) -> out.identity()))
                    .addLight(SceneLight.directional(new Vector3f(0.2f, -0.4f, -1.0f),
                            new Vector3f(1.0f), 1.5f));
            try {
                for (AntiAliasingMode mode : AntiAliasingMode.values()) {
                    byte[] disabled = renderPbrGtaoFrame(window, scene, mode, false);
                    byte[] enabled = renderPbrGtaoFrame(window, scene, mode, true);
                    long difference = pixelDifference(disabled, enabled);
                    assertTrue(difference > 8L,
                            "GTAO must change final PBR pixels for " + mode +
                                    " (difference=" + difference + ")");
                }
                GlDebug.checkError("gtaoChangesPbrPixelsAcrossAntiAliasingModes");
            } finally {
                material.close();
                fallbacks.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void gtaoFailureDoesNotPoisonTheNextFrame() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            // The recovery test deliberately tears down a command stream; its
            // correctness check uses glGetError below and does not need vendor
            // cold-start callbacks counted as shader-policy evidence.
            Mesh mesh = Mesh.from(pbrTriangle());
            ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
            PbrFallbackTextures fallbacks = new PbrFallbackTextures();
            var material = PbrMaterials.create(shader, new PbrMaterialProperties(
                    new Vector4f(0.4f, 0.7f, 0.9f, 1.0f), 0.0f, 0.7f,
                    1.0f, 1.0f, new Vector3f(), java.util.Map.of()),
                    java.util.Map.of(), fallbacks);
            AtomicBoolean failNextFrame = new AtomicBoolean();
            Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 3.0f)))
                    .add(new SceneObject(mesh, material, (out, frame) -> {
                        if (failNextFrame.getAndSet(false)) {
                            throw new IllegalStateException("intentional GTAO frame failure");
                        }
                        out.identity();
                    }, false))
                    .addLight(SceneLight.directional(new Vector3f(0.0f, 0.0f, -1.0f),
                            new Vector3f(1.0f), 1.0f));
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality())) {
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        RenderSettings.builder()
                                .antiAliasingMode(AntiAliasingMode.NONE)
                                .toneMappingMode(ToneMappingMode.ACES)
                                .vsync(false)
                                .build(), environment)
                        .postProcessSettings(PostProcessSettings.builder()
                                .gtao(GtaoSettings.defaults().withEnabled(true)).build());
                try {
                    pipeline.build();
                    pipeline.execute(device);
                    failNextFrame.set(true);
                    assertThrows(IllegalStateException.class, () -> pipeline.execute(device));
                    pipeline.execute(device);
                    assertTrue(pipeline.lastRender3dDiagnostics().ambientOcclusion().historyValid());
                    GlDebug.checkError("gtaoFailureDoesNotPoisonTheNextFrame");
                } finally {
                    pipeline.close();
                }
            } finally {
                material.close();
                fallbacks.close();
                shader.close();
                mesh.close();
            }
        }
    }

    private ShaderProgram program(String fragment) {
        return ShaderProgram.fromResource(getClass(), "/shaders/postprocess/gtao-quad.vert",
                "/shaders/postprocess/" + fragment);
    }

    private static MeshData pbrTriangle() {
        VertexLayout layout = VertexLayout.interleaved(12 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).semantic(VertexSemantic.POSITION)
                        .offsetBytes(0).build(),
                VertexAttribute.builder().index(1).size(2).semantic(VertexSemantic.TEXCOORD_0)
                        .offsetBytes(12).build(),
                VertexAttribute.builder().index(2).size(3).semantic(VertexSemantic.NORMAL)
                        .offsetBytes(20).build(),
                VertexAttribute.builder().index(3).size(4).semantic(VertexSemantic.TANGENT)
                        .offsetBytes(32).build());
        return MeshData.of("gtao-smoke", new float[]{
                -1.2f, -1.0f, 0.0f, 0.0f, 0.0f, 0, 0, 1, 1, 0, 0, 1,
                1.2f, -1.0f, 0.0f, 1.0f, 0.0f, 0, 0, 1, 1, 0, 0, 1,
                0.0f, 1.2f, 0.0f, 0.5f, 1.0f, 0, 0, 1, 1, 0, 0, 1
        }, layout);
    }

    private static MeshData pbrOcclusionMesh() {
        VertexLayout layout = VertexLayout.interleaved(12 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).semantic(VertexSemantic.POSITION)
                        .offsetBytes(0).build(),
                VertexAttribute.builder().index(1).size(2).semantic(VertexSemantic.TEXCOORD_0)
                        .offsetBytes(12).build(),
                VertexAttribute.builder().index(2).size(3).semantic(VertexSemantic.NORMAL)
                        .offsetBytes(20).build(),
                VertexAttribute.builder().index(3).size(4).semantic(VertexSemantic.TANGENT)
                        .offsetBytes(32).build());
        return MeshData.of("gtao-pixel-occlusion", new float[]{
                // Receiver plane at z=-0.65.
                -1.5f, -1.2f, -0.65f, 0, 0, 0, 0, 1, 1, 0, 0, 1,
                 1.5f, -1.2f, -0.65f, 1, 0, 0, 0, 1, 1, 0, 0, 1,
                 1.5f,  1.2f, -0.65f, 1, 1, 0, 0, 1, 1, 0, 0, 1,
                -1.5f, -1.2f, -0.65f, 0, 0, 0, 0, 1, 1, 0, 0, 1,
                 1.5f,  1.2f, -0.65f, 1, 1, 0, 0, 1, 1, 0, 0, 1,
                -1.5f,  1.2f, -0.65f, 0, 1, 0, 0, 1, 1, 0, 0, 1,
                // Raised occluder with a depth discontinuity.
                -0.45f, -0.45f, -0.12f, 0, 0, 0, 0, 1, 1, 0, 0, 1,
                 0.45f, -0.45f, -0.12f, 1, 0, 0, 0, 1, 1, 0, 0, 1,
                 0.45f,  0.45f, -0.12f, 1, 1, 0, 0, 1, 1, 0, 0, 1,
                -0.45f, -0.45f, -0.12f, 0, 0, 0, 0, 1, 1, 0, 0, 1,
                 0.45f,  0.45f, -0.12f, 1, 1, 0, 0, 1, 1, 0, 0, 1,
                -0.45f,  0.45f, -0.12f, 0, 1, 0, 0, 1, 1, 0, 0, 1
        }, layout);
    }

    private static byte[] renderPbrGtaoFrame(GlfwWindow window, Scene scene,
                                               AntiAliasingMode mode, boolean enabled) {
        GlRenderDevice environmentDevice = new GlRenderDevice();
        try (PbrEnvironment environment = PbrEnvironmentLoader.load(environmentDevice,
                GtaoShaderGlTest.class, "/environments/pbr/studio-small.hdr",
                PbrEnvironmentSettings.testQuality())) {
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder()
                            .antiAliasingMode(mode)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build(), environment);
            if (enabled) {
                pipeline.postProcessSettings(PostProcessSettings.builder()
                        .gtao(GtaoSettings.defaults().withEnabled(true)).build());
            }
            try {
                pipeline.build();
                pipeline.execute(environmentDevice);
                ByteBuffer pixels = BufferUtils.createByteBuffer(window.width() * window.height() * 4);
                org.lwjgl.opengl.GL11.glReadPixels(0, 0, window.width(), window.height(),
                        GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                byte[] result = new byte[pixels.remaining()];
                pixels.get(result);
                return result;
            } finally {
                pipeline.close();
            }
        }
    }

    private static long pixelDifference(byte[] left, byte[] right) {
        long difference = 0L;
        for (int offset = 0; offset < left.length; offset += 4) {
            difference += Math.abs(Byte.toUnsignedInt(left[offset]) - Byte.toUnsignedInt(right[offset]));
            difference += Math.abs(Byte.toUnsignedInt(left[offset + 1]) - Byte.toUnsignedInt(right[offset + 1]));
            difference += Math.abs(Byte.toUnsignedInt(left[offset + 2]) - Byte.toUnsignedInt(right[offset + 2]));
        }
        return difference;
    }
}
