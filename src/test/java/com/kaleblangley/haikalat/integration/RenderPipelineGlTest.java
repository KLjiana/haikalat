package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static com.kaleblangley.haikalat.integration.GlTestSupport.generatedSrgbTexture;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** 验证 RenderGraph、抗锯齿以及完整光照阴影链路的真实像素结果。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class RenderPipelineGlTest {
    private static final String WHITE_FRAGMENT_SOURCE = """
            #version 330 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(1.0);
            }
            """;

    private static final String DEPTH_WRITE_VERTEX_SOURCE = """
            #version 330 core
            const vec2 positions[3] = vec2[](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
            void main() {
                gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
            }
            """;

    private static final String DEPTH_SAMPLE_VERTEX_SOURCE = """
            #version 330 core
            const vec2 positions[3] = vec2[](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
            out vec2 vUv;
            void main() {
                vec2 position = positions[gl_VertexID];
                vUv = position * 0.5 + 0.5;
                gl_Position = vec4(position, 0.0, 1.0);
            }
            """;

    private static final String DEPTH_SAMPLE_FRAGMENT_SOURCE = """
            #version 330 core
            in vec2 vUv;
            out vec4 FragColor;
            uniform sampler2D uDepth;
            void main() {
                float depth = texture(uDepth, vUv).r;
                FragColor = vec4(depth, depth, depth, 1.0);
            }
            """;

    private static final String PIPELINE_VERTEX_SOURCE = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            void main() {
                gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0);
            }
            """;

    private static final String PIPELINE_FRAGMENT_SOURCE = """
            #version 330 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(0.9, 0.4, 0.2, 1.0);
            }
            """;

    private static final String ALPHA_COLOR_FRAGMENT_SOURCE = """
            #version 330 core
            out vec4 FragColor;
            uniform vec4 uColor;
            void main() { FragColor = uColor; }
            """;

    private static final String HDR_FRAGMENT_SOURCE = """
            #version 330 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(4.0, 1.0, 0.25, 1.0);
            }
            """;

    private static final String TEXTURED_PIPELINE_VERTEX_SOURCE = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            out vec2 vUv;
            void main() {
                vUv = aTexCoord;
                gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0);
            }
            """;

    private static final String TEXTURED_PIPELINE_FRAGMENT_SOURCE = """
            #version 330 core
            in vec2 vUv;
            out vec4 FragColor;
            uniform sampler2D uTexture;
            void main() {
                FragColor = texture(uTexture, vUv);
            }
            """;

    private static final String INVISIBLE_CASTER_VERTEX_SOURCE = PIPELINE_VERTEX_SOURCE;

    private static final String INVISIBLE_CASTER_FRAGMENT_SOURCE = """
            #version 330 core
            void main() {
                discard;
            }
            """;

    @Test
    void shadowDepthTargetCanBeWrittenAndSampled() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            DirectionalShadowMap shadowMap = new DirectionalShadowMap(
                    new ShadowSettings(32, 10.0f, 0.1f, 30.0f));
            Framebuffer shadowTarget = Framebuffer.fromDescriptor(shadowMap.descriptor());
            Framebuffer sampleTarget = Framebuffer.colorOnly(32, 32);
            ShaderProgram projectShadowShader = ShaderProgram.fromResource(RenderPipelineGlTest.class,
                    "/shaders/shadows/directional-depth.vert", "/shaders/shadows/directional-depth.frag");
            ShaderProgram depthWriter = ShaderProgram.fromSources(DEPTH_WRITE_VERTEX_SOURCE, WHITE_FRAGMENT_SOURCE);
            ShaderProgram depthSampler = ShaderProgram.fromSources(
                    DEPTH_SAMPLE_VERTEX_SOURCE, DEPTH_SAMPLE_FRAGMENT_SOURCE);
            int vao = glGenVertexArrays();
            try {
                glBindVertexArray(vao);
                shadowTarget.bind();
                glEnable(GL_DEPTH_TEST);
                glClearDepth(1.0);
                glClear(GL_DEPTH_BUFFER_BIT);
                depthWriter.use();
                glDrawArrays(GL_TRIANGLES, 0, 3);

                FloatBuffer depth = BufferUtils.createFloatBuffer(1);
                glReadPixels(16, 16, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
                assertTrue(depth.get(0) > 0.45f && depth.get(0) < 0.55f,
                        "Expected the depth-only pass to replace the clear depth");

                sampleTarget.bind();
                glViewport(0, 0, 32, 32);
                glClearColor(0, 0, 0, 1);
                glClear(GL_COLOR_BUFFER_BIT);
                depthSampler.use().setInt("uDepth", 0);
                glBindTexture(GL_TEXTURE_2D, shadowTarget.depthAttachment());
                glDrawArrays(GL_TRIANGLES, 0, 3);

                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                int sampledDepth = Byte.toUnsignedInt(pixel.get(0));
                assertTrue(sampledDepth > 110 && sampledDepth < 145,
                        "Expected shader sampling to observe the written depth texture");
                GlDebug.checkError("shadowDepthTargetCanBeWrittenAndSampled");
            } finally {
                glBindVertexArray(0);
                glDeleteVertexArrays(vao);
                depthSampler.close();
                depthWriter.close();
                projectShadowShader.close();
                sampleTarget.close();
                shadowTarget.close();
            }
        }
    }

    @Test
    void everyAntiAliasingModeInitializesAndRendersOneFrame() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("aa-smoke"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE, PIPELINE_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            try {
                for (ToneMappingMode toneMapping : ToneMappingMode.values()) {
                    for (AntiAliasingMode mode : AntiAliasingMode.values()) {
                        RenderSettings settings = RenderSettings.builder()
                                .antiAliasingMode(mode)
                                .toneMappingMode(toneMapping)
                                .vsync(false)
                                .build();
                        RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings);
                        try {
                            pipeline.build();
                            pipeline.execute(new GlRenderDevice());
                            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                            readPipelineCenterPixel(pipeline, GL_UNSIGNED_BYTE, pixel);
                            assertTrue(Byte.toUnsignedInt(pixel.get(0)) > 80,
                                    "Expected geometry output for " + toneMapping + "/" + mode);
                            GlDebug.checkError(toneMapping + "/" + mode);
                        } finally {
                            pipeline.close();
                        }
                    }
                }
            } finally {
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void midGraySrgbTextureIsEncodedExactlyOnceInLdrAndHdrOutput() throws Exception {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Texture2D midGray = generatedSrgbTexture(128);
            ShaderProgram shader = ShaderProgram.fromSources(
                    TEXTURED_PIPELINE_VERTEX_SOURCE, TEXTURED_PIPELINE_FRAGMENT_SOURCE);
            Mesh mesh = Mesh.from(BuiltinMeshData.texturedQuad("srgb-output-roundtrip"));
            Material material = Material.builder(shader).texture("uTexture", midGray).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material,
                    (model, frame) -> model.identity().scale(4.0f)));
            try {
                for (AntiAliasingMode mode : AntiAliasingMode.values()) {
                    int ldr = renderSrgbCenterPixel(window, scene, mode, ToneMappingMode.NONE);
                    assertEquals(128, ldr, 3,
                            "LDR " + mode + " must decode and re-encode sRGB exactly once");
                }

                int hdr = renderSrgbCenterPixel(
                        window, scene, AntiAliasingMode.NONE, ToneMappingMode.ACES);
                float encoded = 128.0f / 255.0f;
                float linear = (float) Math.pow((encoded + 0.055f) / 1.055f, 2.4f);
                float mapped = com.kaleblangley.haikalat.subsystems.postprocess.ToneMappingPass
                        .acesChannel(linear, 1.0f);
                int expectedHdr = Math.round((float) Math.pow(mapped, 1.0 / 2.2) * 255.0f);
                assertEquals(expectedHdr, hdr, 4,
                        "HDR must use only the explicit ACES gamma encoding");
                GlDebug.checkError("midGraySrgbTextureIsEncodedExactlyOnceInLdrAndHdrOutput");
            } finally {
                material.close();
                mesh.close();
                shader.close();
                midGray.close();
            }
        }
    }

    @Test
    void hdrFullscreenPassesOwnBlendDepthAndCullState() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("fullscreen-state"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE, HDR_FRAGMENT_SOURCE);
            Material alphaMaterial = Material.builder(shader).blendMode(BlendMode.ALPHA).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, alphaMaterial, (model, frame) -> model.identity()));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.TAA)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build());
            try {
                pipeline.build();
                GlRenderDevice device = new GlRenderDevice();
                device.execute(device.createCommandBuffer()
                        .enableBlend(true)
                        .enableCullFace(true)
                        .enableFramebufferSrgb(true));

                pipeline.execute(device);
                ByteBuffer dirtyStatePixel = BufferUtils.createByteBuffer(4);
                readPipelineCenterPixel(pipeline, GL_UNSIGNED_BYTE, dirtyStatePixel);

                assertFalse(glIsEnabled(GL_BLEND), "Tone mapping must disable inherited material blending");
                assertFalse(glIsEnabled(GL_CULL_FACE), "Fullscreen passes must disable inherited face culling");
                assertFalse(glIsEnabled(GL_FRAMEBUFFER_SRGB),
                        "ACES already performs gamma encoding and must disable framebuffer sRGB");
                assertTrue(Byte.toUnsignedInt(dirtyStatePixel.get(0)) > 80);

                device.execute(device.createCommandBuffer()
                        .enableBlend(false)
                        .enableCullFace(false));
                pipeline.execute(device);
                ByteBuffer cleanStatePixel = BufferUtils.createByteBuffer(4);
                readPipelineCenterPixel(pipeline, GL_UNSIGNED_BYTE, cleanStatePixel);
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(Byte.toUnsignedInt(dirtyStatePixel.get(channel)),
                            Byte.toUnsignedInt(cleanStatePixel.get(channel)), 1,
                            "Dirty and clean fullscreen state must produce the same output");
                }
                GlDebug.checkError("hdrFullscreenPassesOwnBlendDepthAndCullState");
            } finally {
                pipeline.close();
                alphaMaterial.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void renderGraphRestoresDepthWritesBeforeNextFrameClear() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            ShaderProgram shader = ShaderProgram.fromSources(DEPTH_WRITE_VERTEX_SOURCE, WHITE_FRAGMENT_SOURCE);
            int vao = glGenVertexArrays();
            Framebuffer[] target = new Framebuffer[1];
            RenderGraph graph = new RenderGraph(32, 32);
            graph.addPass("DepthClear")
                    .createColor("DepthClearColor")
                    .createDepth()
                    .execute((resources, commands) -> {
                        target[0] = resources.currentTarget();
                        commands.enableDepthTest(true)
                                .bindShader(shader)
                                .bindVertexArray(vao)
                                .drawArrays(GL_TRIANGLES, 0, 3);
                    });
            try {
                GlRenderDevice device = new GlRenderDevice();
                glClearDepth(0.25);
                graph.execute(device);

                device.execute(device.createCommandBuffer().depthMask(false));
                glClearDepth(1.0);
                graph.execute(device);

                target[0].bind();
                FloatBuffer depth = BufferUtils.createFloatBuffer(1);
                glReadPixels(16, 16, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
                assertEquals(0.5f, depth.get(0), 0.02f,
                        "Depth clear must restore writes before the next frame draws");
                GlDebug.checkError("renderGraphRestoresDepthWritesBeforeNextFrameClear");
            } finally {
                graph.close();
                glDeleteVertexArrays(vao);
                shader.close();
            }
        }
    }

    @Test
    void rgba16fTargetPreservesLinearValuesAboveOne() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Framebuffer target = Framebuffer.fromDescriptor(FramebufferDescriptor.builder(32, 32)
                    .colorTexture(RenderFormat.RGBA16F)
                    .build());
            ShaderProgram writer = ShaderProgram.fromSources(DEPTH_WRITE_VERTEX_SOURCE, HDR_FRAGMENT_SOURCE);
            int vao = glGenVertexArrays();
            try {
                target.bind();
                glViewport(0, 0, 32, 32);
                glBindVertexArray(vao);
                writer.use();
                glDrawArrays(GL_TRIANGLES, 0, 3);

                FloatBuffer pixel = BufferUtils.createFloatBuffer(4);
                glReadPixels(16, 16, 1, 1, GL_RGBA, GL_FLOAT, pixel);
                assertTrue(pixel.get(0) > 3.5f,
                        "RGBA16F target must preserve unclamped linear HDR red");
                assertTrue(pixel.get(1) > 0.9f);
                GlDebug.checkError("rgba16fTargetPreservesLinearValuesAboveOne");
            } finally {
                glBindVertexArray(0);
                glDeleteVertexArrays(vao);
                writer.close();
                target.close();
            }
        }
    }

    @Test
    void acesExposureChangesFinalLdrPixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("hdr-exposure"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE, HDR_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            try {
                int low = renderExposurePixel(window, scene, 0.25f);
                int high = renderExposurePixel(window, scene, 2.0f);

                assertTrue(low >= 0 && low <= 255);
                assertTrue(high >= 0 && high <= 255);
                assertTrue(high - low > 10,
                        "Changing exposure must produce a visible LDR pixel difference");
                GlDebug.checkError("acesExposureChangesFinalLdrPixels");
            } finally {
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void hdrMsaaAndTaaResourcesSurviveResize() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("hdr-resize"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE, HDR_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            try {
                for (AntiAliasingMode mode : List.of(AntiAliasingMode.MSAA, AntiAliasingMode.TAA)) {
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            RenderSettings.builder()
                                    .antiAliasingMode(mode)
                                    .toneMappingMode(ToneMappingMode.ACES)
                                    .vsync(false)
                                    .build())
                            .postProcessSettings(PostProcessSettings.builder()
                                    .fog(FogSettings.builder().build()).build());
                    try {
                        pipeline.build();
                        pipeline.execute(new GlRenderDevice());
                        pipeline.resize(48, 40);
                        pipeline.execute(new GlRenderDevice());
                        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                        readPipelineCenterPixel(pipeline, GL_UNSIGNED_BYTE, pixel);
                        assertTrue(Byte.toUnsignedInt(pixel.get(0)) > 80,
                                "HDR resize must retain final output for " + mode);
                        Render3dDiagnostics diagnostics = pipeline.lastRender3dDiagnostics();
                        assertEquals(mode == AntiAliasingMode.MSAA,
                                diagnostics.depthResolve().executed());
                        if (mode == AntiAliasingMode.MSAA) {
                            assertTrue(diagnostics.depthResolve().sourceSamples() > 1);
                            assertEquals(1, diagnostics.depthResolve().targetSamples());
                        }
                        GlDebug.checkError("HDR resize " + mode);
                    } finally {
                        pipeline.close();
                    }
                }
            } finally {
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void bloomRendersAcrossAllHdrAaPathsAndSurvivesOddResize() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("bloom-paths"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE, HDR_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            BloomSettings bloom = BloomSettings.builder()
                    .enabled(true)
                    .intensity(0.15f)
                    .maxLevels(3)
                    .build();
            try {
                for (AntiAliasingMode mode : AntiAliasingMode.values()) {
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            RenderSettings.builder()
                                    .antiAliasingMode(mode)
                                    .toneMappingMode(ToneMappingMode.ACES)
                                    .bloomSettings(bloom)
                                    .vsync(false)
                                    .build());
                    try {
                        pipeline.build();
                        pipeline.execute(new GlRenderDevice());
                        pipeline.resize(47, 33);
                        pipeline.execute(new GlRenderDevice());
                        pipeline.resize(0, 0);
                        pipeline.execute(new GlRenderDevice());

                        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                        readPipelineCenterPixel(pipeline, GL_UNSIGNED_BYTE, pixel);
                        assertTrue(Byte.toUnsignedInt(pixel.get(0)) > 80,
                                "Bloom HDR path must render for " + mode);
                        GlDebug.checkError("Bloom HDR path " + mode);
                    } finally {
                        pipeline.close();
                    }
                }
            } finally {
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void automaticExposureRendersAcrossAllHdrAaAndBloomPaths() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("auto-exposure-paths"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE, HDR_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            try {
                for (AntiAliasingMode mode : AntiAliasingMode.values()) {
                    for (boolean bloomEnabled : List.of(false, true)) {
                        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                                RenderSettings.builder()
                                        .antiAliasingMode(mode)
                                        .toneMappingMode(ToneMappingMode.ACES)
                                        .exposureMode(ExposureMode.AUTO)
                                        .bloomSettings(BloomSettings.builder()
                                                .enabled(bloomEnabled)
                                                .maxLevels(2)
                                                .build())
                                        .vsync(false)
                                        .build());
                        try {
                            pipeline.build();
                            GlRenderDevice device = new GlRenderDevice();
                            for (int frame = 0; frame < 4; frame++) {
                                pipeline.execute(device, 1.0f / 60.0f);
                            }
                            pipeline.resize(47, 33);
                            pipeline.execute(device, 1.0f / 60.0f);
                            pipeline.resize(0, 0);
                            pipeline.execute(device, 1.0f / 60.0f);
                            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                            readPipelineCenterPixel(pipeline, GL_UNSIGNED_BYTE, pixel);
                            assertTrue(Byte.toUnsignedInt(pixel.get(0)) > 0,
                                    "Automatic exposure must render for " + mode
                                            + " bloom=" + bloomEnabled);
                            GlDebug.checkError("automatic exposure " + mode + " bloom=" + bloomEnabled);
                            pipeline.close();
                            pipeline.close();
                        } finally {
                            pipeline.close();
                        }
                    }
                }
            } finally {
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void bloomDisabledIsPixelExactAndEnabledSpreadsOnlyBrightPixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("bloom-spread"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE, HDR_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material,
                    (model, frame) -> model.identity().scale(0.25f)));
            try {
                byte[] baseline = renderBloomPixels(window, scene, BloomSettings.defaults());
                byte[] disabled = renderBloomPixels(window, scene, BloomSettings.builder()
                        .enabled(false)
                        .threshold(8.0f)
                        .softKnee(0.0f)
                        .intensity(2.0f)
                        .maxLevels(1)
                        .build());
                assertArrayEquals(baseline, disabled,
                        "Disabled Bloom must not add passes or alter final pixels");

                byte[] enabled = renderBloomPixels(window, scene, BloomSettings.builder()
                        .enabled(true)
                        .threshold(1.0f)
                        .softKnee(0.5f)
                        .intensity(0.5f)
                        .maxLevels(2)
                        .build());
                int background = rgbSum(baseline, 0);
                int largestSpread = 0;
                for (int offset = 0; offset < baseline.length; offset += 4) {
                    if (Math.abs(rgbSum(baseline, offset) - background) <= 3) {
                        largestSpread = Math.max(largestSpread,
                                rgbSum(enabled, offset) - rgbSum(baseline, offset));
                    }
                }
                assertTrue(largestSpread > 3,
                        "Bloom must spread bright energy into neighboring background pixels");
                assertTrue(Math.abs(rgbSum(enabled, 0) - background) <= 3,
                        "Bloom must not lift a distant dark background pixel");
                GlDebug.checkError("bloomDisabledIsPixelExactAndEnabledSpreadsOnlyBrightPixels");
            } finally {
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void graphResizePreservesFixedSizeShadowTarget() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            List<Integer> depthIds = new ArrayList<>();
            List<Integer> colorIds = new ArrayList<>();
            RenderGraph graph = new RenderGraph(32, 32);
            graph.addPass("FixedShadow")
                    .createDepthTexture("FixedShadowDepth")
                    .fixedSize(64, 64)
                    .clearDepthOnly()
                    .execute((resources, commands) ->
                            depthIds.add(resources.depthAttachment("FixedShadowDepth")));
            graph.addPass("WindowColor")
                    .createColor("WindowColor")
                    .execute((resources, commands) ->
                            colorIds.add(resources.colorAttachment("WindowColor")));
            try {
                GlRenderDevice device = new GlRenderDevice();
                graph.execute(device);
                graph.resize(48, 48);
                graph.execute(device);

                assertEquals(depthIds.get(0), depthIds.get(1),
                        "Window resize must not recreate a fixed-size shadow target");
                assertNotEquals(colorIds.get(0), colorIds.get(1),
                        "Window resize must recreate a window-sized target");
            } finally {
                graph.close();
            }
        }
    }

    @Test
    void pipelineIgnoresZeroSizedMinimizeAndRecoversToPositiveExtent() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("minimize-restore"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE, PIPELINE_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera());
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build());
            try {
                pipeline.build();
                int initialWidth = pipeline.graph().width();
                int initialHeight = pipeline.graph().height();
                pipeline.resize(0, 0);
                assertEquals(initialWidth, pipeline.graph().width());
                assertEquals(initialHeight, pipeline.graph().height());
                pipeline.execute(new GlRenderDevice());

                pipeline.resize(48, 40);
                assertEquals(48, pipeline.graph().width());
                assertEquals(40, pipeline.graph().height());
                pipeline.execute(new GlRenderDevice());
                GlDebug.checkError("pipeline minimize/restore");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void sceneReplacementUsesFastPathAndRebuildsOnTopologyChange() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("scene-replace"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE,
                    PIPELINE_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene oldScene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            oldScene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            Scene newScene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            newScene.add(new SceneObject(mesh, material,
                    (model, frame) -> model.identity().translation(0.25f, 0.0f, 0.0f)));
            RenderPipeline pipeline = new RenderPipeline(window, oldScene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .vsync(false).build());
            try {
                pipeline.build();
                var activeGraph = pipeline.graph();
                GlRenderDevice device = new GlRenderDevice();
                pipeline.execute(device);
                byte[] oldScenePixels = readFramebuffer(window.width(), window.height());
                pipeline.replaceScene(newScene);
                assertSame(newScene, pipeline.scene());
                assertSame(activeGraph, pipeline.graph());
                assertEquals(1L, pipeline.sceneFastPathReplacementCount());
                assertEquals(0L, pipeline.sceneGraphRebuildCount());
                pipeline.execute(device);
                byte[] newScenePixels = readFramebuffer(window.width(), window.height());
                assertTrue(changedRgbPixels(oldScenePixels, newScenePixels) > 8,
                        "fast-path replacement must not reuse renderer membership from old Scene");
                Scene shadowScene = new Scene(new Camera(new Vector3f(0, 0, 5)));
                shadowScene.add(new SceneObject(mesh, material,
                        (model, frame) -> model.identity()));
                shadowScene.addLight(SceneLight.shadowedDirectional(
                        new Vector3f(-0.3f, -1.0f, -0.4f), new Vector3f(1.0f), 1.0f));
                pipeline.replaceScene(shadowScene);
                assertSame(shadowScene, pipeline.scene());
                assertNotSame(activeGraph, pipeline.graph());
                assertEquals(1L, pipeline.sceneFastPathReplacementCount());
                assertEquals(1L, pipeline.sceneGraphRebuildCount());
                pipeline.execute(device);
                GlDebug.checkError("sceneReplacementUsesFastPathAndRebuildsOnTopologyChange");
            } finally {
                pipeline.close();
                material.close();
                mesh.close();
            }
        }
    }

    @Test
    void shadowPassDrawsOnlyObjectsMarkedAsCasters() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("shadow-caster-smoke"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE, PIPELINE_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera());
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity(), true));
            scene.add(new SceneObject(mesh, material,
                    (model, frame) -> model.identity().translate(1.0f, 0.0f, 0.0f), false));
            scene.addLight(SceneLight.shadowedDirectional(
                    new Vector3f(-0.3f, -1.0f, -0.4f), new Vector3f(1.0f), 1.0f));

            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build());
            try {
                pipeline.build();
                pipeline.execute(new GlRenderDevice());

                assertEquals(1, pipeline.lastShadowCasterDrawCount());
                assertEquals(0L, pipeline.lastVisibilityStatistics().transparentSortNanos(),
                        "opaque-only scenes must not pay transparent depth-sort accounting");
                GlDebug.checkError("shadowPassDrawsOnlyObjectsMarkedAsCasters");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void instancedShadowOptInChangesPixelsAndBatchRemainsReusableNextFrame() throws Exception {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(128, 128)
                .title("Instanced Shadow Integration")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            InstancedShadowResult disabled = renderInstancedShadowScene(window, false);
            InstancedShadowResult enabled = renderInstancedShadowScene(window, true);

            assertEquals(1, disabled.geometryInstances());
            assertEquals(1, enabled.geometryInstances());
            assertEquals(0, disabled.shadowInstances());
            assertEquals(1, enabled.shadowInstances());
            assertEquals(0, disabled.ordinaryCasterDraws());
            assertEquals(0, enabled.ordinaryCasterDraws());
            assertEquals(2L * 16L * Float.BYTES, disabled.uploadedBytes());
            assertEquals(disabled.uploadedBytes(), enabled.uploadedBytes(),
                    "Shadow and geometry passes must reuse one instance upload per frame");
            assertTrue(pixelDifference(disabled.pixels(), enabled.pixels()) > 1_000,
                    "Enabling the instanced caster must change final shadowed receiver pixels");
            GlDebug.checkError("instancedShadowOptInChangesPixelsAndBatchRemainsReusableNextFrame");
        }
    }

    @Test
    void gtaoDepthAndInstancedShadowReuseOnePreparedBatchAcrossPasses() throws Exception {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(128, 128)
                .title("GTAO Instanced Shadow Integration")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            InstancedShadowResult result = renderInstancedShadowScene(window, true, true);
            assertEquals(1, result.geometryInstances());
            assertEquals(1, result.shadowInstances());
            assertEquals(0, result.ordinaryCasterDraws());
            // Two frames, one upload per frame despite GTAO depth + shadow + geometry.
            assertEquals(2L * 16L * Float.BYTES, result.uploadedBytes());
            GlDebug.checkError("gtaoDepthAndInstancedShadowReuseOnePreparedBatchAcrossPasses");
        }
    }

    @Test
    void fullLightingAndShadowPipelineChangesFinalPixels() throws Exception {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(128, 128)
                .title("Lighting Shadow Integration")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            byte[] noLight = renderLitShadowScene(window, false, false, -0.8f,
                    new Vector3f(0.5f, 0.0f, -1.0f));
            byte[] litWithoutShadow = renderLitShadowScene(window, true, false, -0.8f,
                    new Vector3f(0.5f, 0.0f, -1.0f));
            byte[] shadowed = renderLitShadowScene(window, true, true, -0.8f,
                    new Vector3f(0.5f, 0.0f, -1.0f));
            byte[] movedCaster = renderLitShadowScene(window, true, true, 0.8f,
                    new Vector3f(0.5f, 0.0f, -1.0f));
            byte[] movedLight = renderLitShadowScene(window, true, true, -0.8f,
                    new Vector3f(-0.5f, 0.0f, -1.0f));

            assertTrue(pixelDifference(noLight, litWithoutShadow) > 10_000,
                    "Turning lighting on must change final scene pixels");
            assertTrue(pixelDifference(litWithoutShadow, shadowed) > 1_000,
                    "The complete shadow pass and lighting shader chain must darken final pixels");
            assertTrue(pixelDifference(shadowed, movedCaster) > 1_000,
                    "Moving an invisible caster must move its final-pixel shadow");
            assertTrue(pixelDifference(shadowed, movedLight) > 1_000,
                    "Changing the shadow light direction must update final-pixel shadows");
            GlDebug.checkError("fullLightingAndShadowPipelineChangesFinalPixels");
        }
    }

    private static byte[] renderLitShadowScene(GlfwWindow window, boolean lighting, boolean shadows,
                                                float casterX, Vector3f lightDirection) throws Exception {
        Path resources = Path.of("src", "demo", "resources", "shaders", "scene");
        ShaderProgram receiverShader = ShaderProgram.fromSources(
                Files.readString(resources.resolve("color-scene.vert")),
                Files.readString(resources.resolve("lit-scene.frag")));
        ShaderProgram casterShader = ShaderProgram.fromSources(
                INVISIBLE_CASTER_VERTEX_SOURCE, INVISIBLE_CASTER_FRAGMENT_SOURCE);
        Mesh receiverMesh = Mesh.from(BuiltinMeshData.coloredQuad("shadow-receiver"));
        Mesh casterMesh = Mesh.from(BuiltinMeshData.coloredQuad("invisible-shadow-caster"));
        Material receiverMaterial = Material.builder(receiverShader).build();
        Material casterMaterial = Material.builder(casterShader).build();

        Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
        scene.add(new SceneObject(receiverMesh, receiverMaterial,
                (model, frame) -> model.identity().translation(0.0f, 0.0f, -3.0f).scale(4.0f), false));
        scene.add(new SceneObject(casterMesh, casterMaterial,
                (model, frame) -> model.identity().translation(casterX, 0.0f, -1.0f).scale(0.7f), true));
        if (lighting) {
            scene.addLight(shadows
                    ? SceneLight.shadowedDirectional(lightDirection, new Vector3f(1.0f), 1.0f)
                    : SceneLight.directional(lightDirection, new Vector3f(1.0f), 1.0f));
        }

        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build());
        try {
            pipeline.build();
            pipeline.execute(new GlRenderDevice());
            ByteBuffer pixels = BufferUtils.createByteBuffer(window.width() * window.height() * 4);
            glReadPixels(0, 0, window.width(), window.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            byte[] image = new byte[pixels.remaining()];
            pixels.get(image);
            return image;
        } finally {
            pipeline.close();
            casterMaterial.close();
            receiverMaterial.close();
            casterMesh.close();
            receiverMesh.close();
            casterShader.close();
            receiverShader.close();
        }
    }

    private static InstancedShadowResult renderInstancedShadowScene(GlfwWindow window,
                                                                    boolean castShadows) throws Exception {
        return renderInstancedShadowScene(window, castShadows, false);
    }

    private static InstancedShadowResult renderInstancedShadowScene(GlfwWindow window,
                                                                    boolean castShadows,
                                                                    boolean gtao) throws Exception {
        Path resources = Path.of("src", "demo", "resources", "shaders", "scene");
        ShaderProgram receiverShader = ShaderProgram.fromSources(
                Files.readString(resources.resolve("color-scene.vert")),
                Files.readString(resources.resolve("lit-scene.frag")));
        ShaderProgram instancedShader = ShaderProgram.fromSources(
                Files.readString(Path.of("src", "demo", "resources", "shaders", "instancing",
                        "instanced-scene.vert")),
                Files.readString(Path.of("src", "demo", "resources", "shaders", "basic",
                        "vertex-color-unlit.frag")));
        Mesh receiverMesh = Mesh.from(gtao
                ? gtaoReceiverMeshData()
                : BuiltinMeshData.coloredQuad("instanced-shadow-receiver"));
        Mesh instancedMesh = Mesh.from(BuiltinMeshData.coloredQuad("instanced-shadow-caster"));
        GlRenderDevice environmentDevice = gtao ? new GlRenderDevice() : null;
        PbrEnvironment environment = gtao
                ? PbrEnvironmentLoader.load(environmentDevice, RenderPipelineGlTest.class,
                "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality())
                : null;
        Material receiverMaterial = Material.builder(receiverShader)
                .model(gtao ? MaterialModel.METALLIC_ROUGHNESS : MaterialModel.LEGACY)
                .build();
        InstancedMeshBatch batch = InstancedMeshBatch.of(instancedMesh, 1,
                BuiltinMeshData.INSTANCE_ATTRIBUTE_BASE);
        InstancedRenderer instanced = new InstancedRenderer(batch, instancedShader, castShadows);
        instanced.addInstance(frame -> new Matrix4f()
                .translation(-0.8f, 0.0f, -1.0f)
                .scale(0.7f));

        Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
        scene.add(new SceneObject(receiverMesh, receiverMaterial,
                (model, frame) -> model.identity().translation(0.0f, 0.0f, -3.0f).scale(4.0f), false));
        scene.addLight(SceneLight.shadowedDirectional(
                new Vector3f(0.5f, 0.0f, -1.0f), new Vector3f(1.0f), 1.0f));

        RenderSettings pipelineSettings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(gtao ? ToneMappingMode.ACES : ToneMappingMode.NONE)
                .vsync(false).build();
        RenderPipeline pipeline = new RenderPipeline(window, scene, instanced,
                pipelineSettings, environment);
        if (gtao) {
            pipeline.postProcessSettings(PostProcessSettings.builder()
                    .gtao(GtaoSettings.defaults().withEnabled(true)).build());
        }
        try {
            pipeline.build();
            GlRenderDevice device = new GlRenderDevice();
            instanced.beginFrame(0);
            pipeline.execute(device);
            instanced.beginFrame(1);
            pipeline.execute(device);

            ByteBuffer pixels = BufferUtils.createByteBuffer(window.width() * window.height() * 4);
            glReadPixels(0, 0, window.width(), window.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            byte[] image = new byte[pixels.remaining()];
            pixels.get(image);
            return new InstancedShadowResult(image, instanced.drawnCount(),
                    pipeline.lastInstancedShadowCasterCount(), pipeline.lastShadowCasterDrawCount(),
                    instanced.bufferStatistics().uploadedBytes());
        } finally {
            pipeline.close();
            if (environment != null) environment.close();
            instanced.close();
            receiverMaterial.close();
            instancedMesh.close();
            receiverMesh.close();
            instancedShader.close();
            receiverShader.close();
        }
    }

    private static long pixelDifference(byte[] left, byte[] right) {
        long difference = 0L;
        for (int i = 0; i < left.length; i += 4) {
            difference += Math.abs(Byte.toUnsignedInt(left[i]) - Byte.toUnsignedInt(right[i]));
            difference += Math.abs(Byte.toUnsignedInt(left[i + 1]) - Byte.toUnsignedInt(right[i + 1]));
            difference += Math.abs(Byte.toUnsignedInt(left[i + 2]) - Byte.toUnsignedInt(right[i + 2]));
        }
        return difference;
    }

    private static MeshData gtaoReceiverMeshData() {
        VertexLayout layout = VertexLayout.interleaved(12 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).semantic(VertexSemantic.POSITION)
                        .offsetBytes(0).build(),
                VertexAttribute.builder().index(1).size(2).semantic(VertexSemantic.TEXCOORD_0)
                        .offsetBytes(3 * Float.BYTES).build(),
                VertexAttribute.builder().index(2).size(3).semantic(VertexSemantic.NORMAL)
                        .offsetBytes(5 * Float.BYTES).build(),
                VertexAttribute.builder().index(3).size(4).semantic(VertexSemantic.TANGENT)
                        .offsetBytes(8 * Float.BYTES).build());
        float[] vertex = {
                -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                 0.5f, -0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                 0.5f,  0.5f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                 0.5f,  0.5f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                -0.5f,  0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f
        };
        return MeshData.of("gtao-instanced-shadow-receiver", vertex, layout);
    }

    private static int renderExposurePixel(GlfwWindow window, Scene scene, float exposure) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                RenderSettings.builder()
                        .antiAliasingMode(AntiAliasingMode.NONE)
                        .toneMappingMode(ToneMappingMode.ACES)
                        .exposure(exposure)
                        .vsync(false)
                        .build());
        try {
            pipeline.build();
            pipeline.execute(new GlRenderDevice());
            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            readPipelineCenterPixel(pipeline, GL_UNSIGNED_BYTE, pixel);
            return Byte.toUnsignedInt(pixel.get(0));
        } finally {
            pipeline.close();
        }
    }

    private static int renderSrgbCenterPixel(GlfwWindow window, Scene scene,
                                             AntiAliasingMode antiAliasingMode,
                                             ToneMappingMode toneMappingMode) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                RenderSettings.builder()
                        .antiAliasingMode(antiAliasingMode)
                        .toneMappingMode(toneMappingMode)
                        .vsync(false)
                        .build());
        try {
            pipeline.build();
            pipeline.execute(new GlRenderDevice());
            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            readPipelineCenterPixel(pipeline, GL_UNSIGNED_BYTE, pixel);
            return Byte.toUnsignedInt(pixel.get(0));
        } finally {
            pipeline.close();
        }
    }

    private static byte[] renderBloomPixels(GlfwWindow window, Scene scene, BloomSettings bloomSettings) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                RenderSettings.builder()
                        .antiAliasingMode(AntiAliasingMode.NONE)
                        .toneMappingMode(ToneMappingMode.ACES)
                        .bloomSettings(bloomSettings)
                        .vsync(false)
                        .build());
        try {
            pipeline.build();
            pipeline.execute(new GlRenderDevice());
            ByteBuffer pixels = BufferUtils.createByteBuffer(window.width() * window.height() * 4);
            glReadPixels(0, 0, window.width(), window.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            byte[] image = new byte[pixels.capacity()];
            pixels.get(image);
            return image;
        } finally {
            pipeline.close();
        }
    }

    private static int rgbSum(byte[] pixels, int offset) {
        return Byte.toUnsignedInt(pixels[offset])
                + Byte.toUnsignedInt(pixels[offset + 1])
                + Byte.toUnsignedInt(pixels[offset + 2]);
    }

    @Test
    void maskedShadowDepthUsesTextureAlphaFactorAndSameCutoff() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Framebuffer target = Framebuffer.fromDescriptor(new DirectionalShadowMap(
                    new ShadowSettings(32, 10.0f, 0.1f, 30.0f)).descriptor());
            ShaderProgram shader = ShaderProgram.fromResource(RenderPipelineGlTest.class,
                    "/shaders/shadows/masked-directional-depth.vert",
                    "/shaders/shadows/masked-directional-depth.frag");
            Mesh mesh = Mesh.from(BuiltinMeshData.texturedQuad("masked-shadow-cutoff"));
            Texture2D transparent = Texture2D.fromRgba8(1, 1,
                    new byte[]{(byte) 255, (byte) 255, (byte) 255, 0},
                    com.kaleblangley.haikalat.backend.texture.TextureColorSpace.SRGB);
            Texture2D opaque = Texture2D.fromRgba8(1, 1,
                    new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255},
                    com.kaleblangley.haikalat.backend.texture.TextureColorSpace.SRGB);
            try {
                float discarded = maskedDepth(target, shader, mesh, transparent, 1.0f, 0.5f);
                float factorDiscarded = maskedDepth(target, shader, mesh, opaque, 0.2f, 0.5f);
                float written = maskedDepth(target, shader, mesh, opaque, 1.0f, 0.5f);
                assertEquals(1.0f, discarded, 1.0e-6f);
                assertEquals(1.0f, factorDiscarded, 1.0e-6f);
                assertTrue(written > 0.45f && written < 0.55f);
                GlDebug.checkError("masked shadow cutoff");
            } finally {
                opaque.close();
                transparent.close();
                mesh.close();
                shader.close();
                target.close();
            }
        }
    }

    private static float maskedDepth(Framebuffer target, ShaderProgram shader, Mesh mesh,
                                     Texture2D texture, float factorAlpha, float cutoff) {
        target.bind();
        glViewport(0, 0, target.width(), target.height());
        glEnable(GL_DEPTH_TEST);
        glClearDepth(1.0);
        glClear(GL_DEPTH_BUFFER_BIT);
        shader.use().setMat4("uLightSpace", new Matrix4f())
                .setMat4("uModel", new Matrix4f())
                .setInt("uSkinningEnabled", 0)
                .setInt("uMorphTargetCount", 0)
                .setInt("uBaseColorMap", 0)
                .setVec4("uBaseColorFactor", 1, 1, 1, factorAlpha)
                .setFloat("uAlphaCutoff", cutoff);
        texture.bind(0);
        mesh.draw();
        FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        glReadPixels(16, 16, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        return depth.get(0);
    }

    @Test
    void overlappingAlphaDrawsAreSortedBackToFrontWithDeterministicPixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredQuad("alpha-depth-order"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE,
                    ALPHA_COLOR_FRAGMENT_SOURCE);
            Material near = Material.builder(shader).blendMode(BlendMode.ALPHA)
                    .setVec4("uColor", new org.joml.Vector4f(1, 0, 0, 0.5f)).build();
            Material far = Material.builder(shader).blendMode(BlendMode.ALPHA)
                    .setVec4("uColor", new org.joml.Vector4f(0, 0, 1, 0.5f)).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            // Deliberately submit near first; the queue must reverse these two draws.
            scene.add(new SceneObject(mesh, near, (model, frame) -> model.identity().scale(3.0f)));
            scene.add(new SceneObject(mesh, far,
                    (model, frame) -> model.identity().translation(0, 0, -1).scale(3.0f)));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .vsync(false).build());
            try {
                pipeline.build();
                pipeline.execute(new GlRenderDevice());
                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                readPipelineCenterPixel(pipeline, GL_UNSIGNED_BYTE, pixel);
                assertTrue(Byte.toUnsignedInt(pixel.get(0)) > Byte.toUnsignedInt(pixel.get(2)),
                        "near red must composite after far blue");
                assertEquals(2, pipeline.lastVisibilityStatistics().alphaDraws());
                assertTrue(pipeline.lastVisibilityStatistics().transparentSortNanos() > 0L);
                assertEquals(0, pipeline.lastVisibilityStatistics().transparentStableTies());
                Render3dDiagnostics diagnostics = pipeline.lastRender3dDiagnostics();
                assertEquals(2, diagnostics.queues().alpha());
                assertEquals(2, diagnostics.visibility().transparentVisible());
                assertTrue(diagnostics.invalidationReasons().contains("CAMERA"));
                GlDebug.checkError("overlapping alpha depth order");
            } finally {
                pipeline.close();
                far.close();
                near.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void directionalCascadeAtlasRendersTwoThreeAndFourStableTiles() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("cascade-caster-smoke"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE,
                    PIPELINE_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(SceneObject.fixed(mesh, material, new Matrix4f(), true));
            scene.addLight(SceneLight.shadowedDirectional(
                    new Vector3f(-0.3f, -1.0f, -0.4f), new Vector3f(1.0f), 1.0f));
            try {
                for (int count : List.of(2, 3, 4)) {
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                                    .vsync(false).build())
                            .directionalCascades(new DirectionalCascadeSettings(
                                    count, 1024, 0.6f, 0.08f));
                    try {
                        pipeline.build();
                        pipeline.execute(new GlRenderDevice());
                        assertEquals(count, pipeline.lastDirectionalCascadeMatrices().size());
                        assertEquals(count, pipeline.lastShadowCasterDrawCount());
                        Render3dDiagnostics firstDiagnostics = pipeline.lastRender3dDiagnostics();
                        assertEquals(count, firstDiagnostics.shadows().cascadeCasters().size());
                        assertEquals(pipeline.lastShadowCullingStatistics().directionalReferences(),
                                firstDiagnostics.shadows().cascadeCasters().stream()
                                        .mapToInt(Integer::intValue).sum());
                        // A fixed scene must reuse the published slices without
                        // re-testing all caster/view pairs on the next frame.
                        pipeline.execute(new GlRenderDevice());
                        ShadowCullingStatistics reused = pipeline.lastShadowCullingStatistics();
                        assertTrue(reused.planReused());
                        assertEquals(0, reused.casterViewTests());
                        pipeline.resize(73, 51);
                        pipeline.execute(new GlRenderDevice());
                        assertEquals(count, pipeline.lastShadowCasterDrawCount());
                        scene.camera().setPosition(new Vector3f(0.03f, 0.0f, 5.0f));
                        scene.camera().setYaw(-88.0f);
                        pipeline.execute(new GlRenderDevice());
                        assertTrue(pipeline.lastDirectionalCascadeMatrices().stream()
                                .allMatch(Matrix4f::isFinite));
                        Render3dDiagnostics diagnostics = pipeline.lastRender3dDiagnostics();
                        assertTrue(diagnostics.available());
                        assertEquals(count, diagnostics.shadows().cascadeCount());
                        assertEquals(count, diagnostics.shadows().cascadeSplits().size());
                        assertEquals(count, diagnostics.shadows().cascadeCasters().size());
                        GlDebug.checkError("directional cascades " + count);
                    } finally {
                        pipeline.close();
                    }
                }
            } finally {
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void partialShadowFailureIsRaisedDuringGpuExecutionAndRecovers() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredQuad("partial-shadow-caster"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE,
                    PIPELINE_FRAGMENT_SOURCE);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0.0f, 1.0f, 5.0f)));
            scene.add(SceneObject.fixed(mesh, material, new Matrix4f(), true));
            scene.addLight(SceneLight.shadowedDirectional(
                    new Vector3f(-0.4f, -1.0f, -0.3f), new Vector3f(1.0f), 2.0f));
            RenderSettings settings = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build();
            RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings)
                    .directionalCascades(new DirectionalCascadeSettings(4, 512, 0.6f, 0.08f));
            GlRenderDevice device = new GlRenderDevice();
            String previous = System.getProperty("haikalat.test.failShadowPassOnce");
            try {
                pipeline.build();
                pipeline.execute(device);
                Render3dDiagnostics before = pipeline.lastRender3dDiagnostics();
                byte[] beforePixels = readFramebuffer(window.width(), window.height());
                System.setProperty("haikalat.test.failShadowPassOnce", "true");
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> pipeline.execute(device));
                assertEquals("injected shadow pass failure", failure.getMessage());
                Render3dDiagnostics failed = pipeline.lastRender3dDiagnostics();
                assertEquals("gpu-execute", failed.failureStage(),
                        "partial failure must be reported after command execution");
                assertEquals(before.shadows().cascadeCasters(), failed.shadows().cascadeCasters(),
                        "failed diagnostics must retain the last successful cascade plan");
                System.clearProperty("haikalat.test.failShadowPassOnce");
                pipeline.execute(device);
                assertTrue(pipeline.lastRender3dDiagnostics().failureStage().isEmpty());
                assertEquals(0, changedRgbPixels(beforePixels,
                                readFramebuffer(window.width(), window.height())),
                        "recovery must restore the same final pixels after partial atlas failure");
                GlDebug.checkError("partialShadowFailureIsRaisedDuringGpuExecutionAndRecovers");
            } finally {
                if (previous == null) System.clearProperty("haikalat.test.failShadowPassOnce");
                else System.setProperty("haikalat.test.failShadowPassOnce", previous);
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void failedCandidateSceneBuildPreservesActiveGenerationAndPixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("candidate-rollback"));
            ShaderProgram shader = ShaderProgram.fromSources(PIPELINE_VERTEX_SOURCE,
                    PIPELINE_FRAGMENT_SOURCE);
            Material legacy = Material.builder(shader).build();
            Material unsupportedPbr = Material.builder(shader)
                    .model(MaterialModel.METALLIC_ROUGHNESS)
                    .build();
            Scene activeScene = new Scene(new Camera(new Vector3f(0, 0, 5)))
                    .add(new SceneObject(mesh, legacy, (model, frame) -> model.identity()));
            RenderPipeline pipeline = new RenderPipeline(window, activeScene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .vsync(false).build());
            try {
                GlRenderDevice device = new GlRenderDevice();
                pipeline.build();
                pipeline.execute(device);
                byte[] before = readFramebuffer(window.width(), window.height());
                RenderGraph activeGraph = pipeline.graph();

                Scene rejected = new Scene(new Camera(new Vector3f(0, 0, 5)))
                        .add(new SceneObject(mesh, unsupportedPbr,
                                (model, frame) -> model.identity()));
                assertThrows(IllegalStateException.class,
                        () -> pipeline.replaceScene(rejected));
                Render3dDiagnostics failedBuild = pipeline.lastRender3dDiagnostics();
                assertEquals("candidate-build", failedBuild.failureStage());
                assertNotEquals(failedBuild.activeGenerationId(),
                        failedBuild.candidateGenerationId());

                assertSame(activeScene, pipeline.scene());
                assertSame(activeGraph, pipeline.graph());
                pipeline.execute(device);
                assertEquals(0, changedRgbPixels(before,
                        readFramebuffer(window.width(), window.height())));
                GlDebug.checkError("failedCandidateSceneBuildPreservesActiveGenerationAndPixels");
            } finally {
                pipeline.close();
                unsupportedPbr.close();
                legacy.close();
                mesh.close();
            }
        }
    }

    private static byte[] readFramebuffer(int width, int height) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        byte[] image = new byte[pixels.capacity()];
        pixels.get(image);
        return image;
    }

    private static int changedRgbPixels(byte[] left, byte[] right) {
        assertEquals(left.length, right.length);
        int changed = 0;
        for (int offset = 0; offset < left.length; offset += 4) {
            if (left[offset] != right[offset]
                    || left[offset + 1] != right[offset + 1]
                    || left[offset + 2] != right[offset + 2]) {
                changed++;
            }
        }
        return changed;
    }

    private static void readPipelineCenterPixel(RenderPipeline pipeline, int type,
                                                java.nio.Buffer destination) {
        int centerX = pipeline.graph().width() / 2;
        int centerY = pipeline.graph().height() / 2;
        if (destination instanceof ByteBuffer bytes) {
            glReadPixels(centerX, centerY, 1, 1, GL_RGBA, type, bytes);
        } else if (destination instanceof FloatBuffer floats) {
            glReadPixels(centerX, centerY, 1, 1, GL_RGBA, type, floats);
        } else {
            throw new IllegalArgumentException("Unsupported pixel buffer: "
                    + destination.getClass().getName());
        }
    }

    private record InstancedShadowResult(byte[] pixels, int geometryInstances,
                                         int shadowInstances, int ordinaryCasterDraws,
                                         long uploadedBytes) {
    }
}
