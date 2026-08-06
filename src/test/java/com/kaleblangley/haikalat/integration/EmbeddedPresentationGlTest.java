package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.core.presentation.PresentationResult;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.core.presentation.OwnedPresentationTarget;
import com.kaleblangley.haikalat.runtime.HaikalatRuntime;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceCatalog;
import com.kaleblangley.haikalat.subsystems.resources.ResourceSource;
import com.kaleblangley.haikalat.subsystems.scene.SceneAssetService;
import com.kaleblangley.haikalat.subsystems.scene.SceneVersion;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.*;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class EmbeddedPresentationGlTest {
    @TempDir
    Path directory;

    @Test
    void ownedPresentationTargetDeletesExactlyItsOwnResources() {
        try (GlfwWindow context = hiddenWindow()) {
            context.bindContext();
            GL.createCapabilities();
            OwnedPresentationTarget owner = OwnedPresentationTarget.create(
                    8, 8, RenderFormat.RGBA8, true, 1);
            PresentationTarget target = owner.target();
            int framebuffer = target.drawFramebufferId();
            int color = target.color().orElseThrow().textureId();
            int depth = target.depth().orElseThrow().textureId();
            assertTrue(glIsFramebuffer(framebuffer));
            assertTrue(glIsTexture(color));
            assertTrue(glIsTexture(depth));

            owner.close();
            owner.close();

            assertFalse(glIsFramebuffer(framebuffer));
            assertFalse(glIsTexture(color));
            assertFalse(glIsTexture(depth));
            assertThrows(IllegalStateException.class, owner::target);
        }
    }

    private static final String VERTEX = """
            #version 460 core
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
    private static final String FRAGMENT = """
            #version 460 core
            out vec4 color;
            void main() {
                color = vec4(0.95, 0.15, 0.05, 1.0);
            }
            """;

    @Test
    void embeddedPipelineUsesNonZeroColorDepthReplacementAndRestoresHostState() {
        try (GlfwWindow context = hiddenWindow()) {
            context.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            RawHostTarget first = RawHostTarget.create(32, 32, 1);
            RawHostTarget second = RawHostTarget.create(24, 20, 2);
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("embedded-target"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new com.kaleblangley.haikalat.subsystems.render3d.Camera(
                    new Vector3f(0, 0, -50)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            RenderSettings settings = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.NONE)
                    .vsync(false)
                    .build();
            RenderPipeline pipeline = new RenderPipeline(first.target, scene, null, settings);
            GlRenderDevice device = new GlRenderDevice();
            HaikalatRuntime runtime = HaikalatRuntime.createEmbedded(device);
            ExternalCamera camera = ExternalCamera.of(
                    new Matrix4f().lookAt(0, 0, 3, 0, 0, 0, 0, 1, 0),
                    new Matrix4f().perspective((float) Math.toRadians(45),
                            1.0f, 0.1f, 100.0f),
                    new Vector3f(0, 0, 3), 0.5f);
            try {
                pipeline.build();

                first.clear(0.05f, 0.25f, 0.75f, 0.0f);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
                glViewport(3, 4, 17, 19);
                glEnable(GL_SCISSOR_TEST);
                glScissor(2, 1, 11, 13);
                glDisable(GL_DEPTH_TEST);
                glClearColor(0.11f, 0.22f, 0.33f, 0.44f);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, first.framebuffer);
                glReadBuffer(GL_NONE);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
                int originalProgram = glGetInteger(GL_CURRENT_PROGRAM);

                PresentationResult occluded = runtime.execute(() ->
                        pipeline.execute(device, camera, first.target, 1.0f / 60.0f));
                assertEquals(PresentationResult.RENDERED, occluded);
                assertEquals(0, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
                assertEquals(0, glGetInteger(GL_READ_FRAMEBUFFER_BINDING));
                assertEquals(originalProgram, glGetInteger(GL_CURRENT_PROGRAM));
                assertArrayEquals(new int[]{3, 4, 17, 19}, int4(GL_VIEWPORT));
                assertArrayEquals(new int[]{2, 1, 11, 13}, int4(GL_SCISSOR_BOX));
                assertTrue(glIsEnabled(GL_SCISSOR_TEST));
                assertFalse(glIsEnabled(GL_DEPTH_TEST));
                assertArrayEquals(new float[]{0.11f, 0.22f, 0.33f, 0.44f},
                        float4(GL_COLOR_CLEAR_VALUE), 0.0001f);
                assertEquals(GL_NONE, readBufferOf(first.framebuffer),
                        "borrowed host FBO read-buffer selection must be restored");
                int[] blockedPixel = first.readCenter();
                assertTrue(blockedPixel[2] > blockedPixel[0] * 2,
                        "host depth=0 must keep the host-blue pixel in front of Haikalat geometry");
                assertTrue(first.maximumRedMinusBlue() < 0,
                        "host depth=0 must occlude every Haikalat triangle pixel");

                first.clear(0.05f, 0.25f, 0.75f, 1.0f);
                pipeline.execute(device, camera, first.target, 1.0f / 60.0f);
                assertEquals(1, pipeline.lastVisibilityStatistics().forwardVisible(),
                        "external camera must drive scene visibility");
                assertTrue(first.maximumRedMinusBlue() > 80,
                        "host depth=1 must allow Haikalat geometry to write host color");

                second.clear(0.02f, 0.03f, 0.04f, 1.0f);
                assertEquals(PresentationResult.RENDERED,
                        pipeline.execute(device, camera, second.target, 1.0f / 60.0f));
                assertEquals(24, pipeline.graph().width());
                assertEquals(20, pipeline.graph().height());
                assertTrue(second.readCenter()[0] > 40);
                assertTrue(glIsFramebuffer(first.framebuffer));
                assertTrue(glIsTexture(first.color));
                assertTrue(glIsTexture(first.depthStencil));

                PresentationTarget minimized = PresentationTarget.builder(0, 0)
                        .framebuffer(second.framebuffer)
                        .generation(3)
                        .build();
                assertEquals(PresentationResult.SKIPPED_ZERO_EXTENT,
                        pipeline.execute(device, camera, minimized, 1.0f / 60.0f));
                assertEquals(24, pipeline.graph().width());
                assertEquals(20, pipeline.graph().height());
                GlDebug.checkError("embedded presentation");
            } finally {
                runtime.close();
                pipeline.close();
                assertTrue(glIsFramebuffer(first.framebuffer),
                        "pipeline must not delete borrowed host framebuffer");
                assertTrue(glIsTexture(first.color),
                        "pipeline must not delete borrowed host color");
                assertTrue(glIsTexture(first.depthStencil),
                        "pipeline must not delete borrowed host depth/stencil");
                material.close();
                shader.close();
                mesh.close();
                second.close();
                first.close();
            }
        }
    }

    @Test
    void embeddedRuntimeRendersTheSameSerializedScenePlanIntoBorrowedTarget() throws Exception {
        Files.writeString(directory.resolve("embedded.scene.json"), """
                {"format":"haikalat.scene/1","version":1,
                 "camera":{"node":"camera","projection":{
                   "type":"perspective","fovYDegrees":60,"near":0.1,"far":100}},
                 "nodes":[{"id":"camera","transform":{"translation":[0,0,3]}}]}
                """, StandardCharsets.UTF_8);
        ResourceCatalog catalog = ResourceCatalog.builder()
                .mount("host", ResourceSource.directory("host", directory))
                .build();
        AssetId sceneId = AssetId.of("host", "embedded.scene.json");

        try (GlfwWindow context = hiddenWindow()) {
            context.bindContext();
            GL.createCapabilities();
            RawHostTarget host = RawHostTarget.create(20, 16, 30);
            GlRenderDevice device = new GlRenderDevice();
            try (SceneAssetService assets = new SceneAssetService(catalog);
                 GltfRuntimeLibrary library = GltfRuntimeLibrary.create()) {
                var plan = assets.loadPlan(sceneId).join();
                try (SceneVersion version = SceneVersion.build(plan, library);
                     HaikalatRuntime runtime = HaikalatRuntime.createEmbedded(device)) {
                    Mesh probeMesh = Mesh.from(BuiltinMeshData.coloredTriangle(
                            "serialized-embedded-target"));
                    ShaderProgram probeShader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
                    Material probeMaterial = Material.builder(probeShader).build();
                    version.scene().add(new SceneObject(probeMesh, probeMaterial,
                            (model, frame) -> model.identity()));
                    RenderSettings settings = RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build();
                    RenderPipeline pipeline = new RenderPipeline(host.target,
                            version.scene(), null, settings);
                    try {
                        pipeline.build();
                        ExternalCamera camera = ExternalCamera.of(
                                new Matrix4f().lookAt(0, 0, 3, 0, 0, 0, 0, 1, 0),
                                new Matrix4f().perspective((float) Math.toRadians(60),
                                        20.0f / 16.0f, 0.1f, 100.0f),
                                new Vector3f(0, 0, 3), 0.25f);
                        host.clear(1.0f, 0.0f, 1.0f, 1.0f);
                        assertEquals(PresentationResult.RENDERED, runtime.execute(() ->
                                pipeline.render(device, camera, host.target, 1.0f / 60.0f)));
                        int[] pixel = host.readCenter();
                        assertTrue(pixel[0] < 240 || pixel[2] < 240,
                                "serialized scene output must replace the host magenta clear");
                        assertTrue(glIsFramebuffer(host.framebuffer));
                        assertTrue(glIsTexture(host.color));
                        assertEquals(sceneId, plan.sceneId());
                        GlDebug.checkError("embedded serialized scene plan");
                    } finally {
                        pipeline.close();
                        probeMaterial.close();
                        probeShader.close();
                        probeMesh.close();
                    }
                }
            } finally {
                assertTrue(glIsFramebuffer(host.framebuffer));
                assertTrue(glIsTexture(host.color));
                host.close();
            }
        }
    }

    @Test
    void hdrVfxBloomToneMappingAndFxaaShareHostTargetAndExternalCamera() {
        try (GlfwWindow context = hiddenWindow()) {
            context.bindContext();
            GL.createCapabilities();
            RawHostTarget host = RawHostTarget.create(32, 32, 8);
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("embedded-hdr"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new com.kaleblangley.haikalat.subsystems.render3d.Camera());
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            RenderSettings settings = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.FXAA)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .bloomSettings(BloomSettings.builder()
                            .enabled(true).threshold(0.25f).softKnee(0.5f)
                            .intensity(0.2f).maxLevels(2).build())
                    .vsync(false)
                    .build();
            ExternalCamera camera = ExternalCamera.of(
                    new Matrix4f().lookAt(0, 0, 3, 0, 0, 0, 0, 1, 0),
                    new Matrix4f().perspective((float) Math.toRadians(45),
                            1.0f, 0.1f, 100.0f),
                    new Vector3f(0, 0, 3), 0.25f);
            AtomicReference<ExternalCamera> seenCamera = new AtomicReference<>();
            AtomicReference<PresentationTarget> seenTarget = new AtomicReference<>();
            RenderPipeline pipeline = new RenderPipeline(host.target, scene, null, settings)
                    .hdrVfxWithCamera((resources, commands, frameCamera) -> {
                        seenCamera.set(frameCamera);
                        seenTarget.set(resources.framePresentationTarget());
                    });
            try {
                pipeline.build();
                host.clear(0.0f, 0.0f, 0.0f, 1.0f);
                assertEquals(PresentationResult.RENDERED,
                        pipeline.execute(new GlRenderDevice(), camera, host.target, 1.0f / 60.0f));
                assertSame(camera, seenCamera.get());
                assertSame(host.target, seenTarget.get());
                assertTrue(host.maximumRedMinusBlue() > 20,
                        "HDR VFX/Bloom/tone mapping/FXAA chain must reach host color");
                GlDebug.checkError("embedded HDR VFX postprocess");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
                host.close();
            }
        }
    }

    @Test
    void hostStateAndBorrowedResourcesSurvivePipelineFailure() {
        try (GlfwWindow context = hiddenWindow()) {
            context.bindContext();
            GL.createCapabilities();
            RawHostTarget host = RawHostTarget.create(16, 16, 11);
            Scene scene = new Scene(new com.kaleblangley.haikalat.subsystems.render3d.Camera());
            RenderSettings settings = RenderSettings.builder()
                    .toneMappingMode(ToneMappingMode.ACES)
                    .antiAliasingMode(AntiAliasingMode.NONE)
                    .vsync(false)
                    .build();
            ExternalCamera camera = ExternalCamera.of(new Matrix4f(), new Matrix4f(),
                    new Vector3f(), 0.0f);
            RenderPipeline pipeline = new RenderPipeline(host.target, scene, null, settings)
                    .hdrVfxWithCamera((resources, commands, frameCamera) -> {
                        throw new IllegalStateException("intentional embedded failure");
                    });
            GlRenderDevice device = new GlRenderDevice();
            try {
                pipeline.build();
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
                glViewport(5, 6, 7, 8);
                glDisable(GL_SCISSOR_TEST);
                glEnable(GL_CULL_FACE);

                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> pipeline.execute(device, camera, host.target, 0.016f));
                assertEquals("intentional embedded failure", failure.getMessage());
                assertEquals(0, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
                assertEquals(0, glGetInteger(GL_READ_FRAMEBUFFER_BINDING));
                assertArrayEquals(new int[]{5, 6, 7, 8}, int4(GL_VIEWPORT));
                assertFalse(glIsEnabled(GL_SCISSOR_TEST));
                assertTrue(glIsEnabled(GL_CULL_FACE));
                assertTrue(glIsFramebuffer(host.framebuffer));
                assertTrue(glIsTexture(host.color));
                assertTrue(glIsTexture(host.depthStencil));
            } finally {
                pipeline.close();
                host.close();
            }
        }
    }

    private static int[] int4(int name) {
        java.nio.IntBuffer values = BufferUtils.createIntBuffer(4);
        glGetIntegerv(name, values);
        return new int[]{values.get(0), values.get(1), values.get(2), values.get(3)};
    }

    private static float[] float4(int name) {
        java.nio.FloatBuffer values = BufferUtils.createFloatBuffer(4);
        glGetFloatv(name, values);
        return new float[]{values.get(0), values.get(1), values.get(2), values.get(3)};
    }

    private static int readBufferOf(int framebuffer) {
        int previous = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        int value = glGetInteger(GL_READ_BUFFER);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, previous);
        return value;
    }

    private static final class RawHostTarget implements AutoCloseable {
        final int framebuffer;
        final int color;
        final int depthStencil;
        final int width;
        final int height;
        final PresentationTarget target;

        private RawHostTarget(int framebuffer, int color, int depthStencil,
                              int width, int height, long generation) {
            this.framebuffer = framebuffer;
            this.color = color;
            this.depthStencil = depthStencil;
            this.width = width;
            this.height = height;
            ExternalAttachment colorAttachment = ExternalAttachment.borrowedColor(
                    color, RenderFormat.RGBA8, width, height);
            ExternalAttachment depthAttachment = ExternalAttachment.borrowedDepthStencil(
                    depthStencil, width, height);
            target = PresentationTarget.borrowed(framebuffer, colorAttachment,
                    depthAttachment, width, height, generation);
        }

        static RawHostTarget create(int width, int height, long generation) {
            int color = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(color, 1, GL_RGBA8, width, height);
            glTextureParameteri(color, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameteri(color, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            int depth = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(depth, 1, GL_DEPTH24_STENCIL8, width, height);
            glTextureParameteri(depth, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameteri(depth, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            int framebuffer = glCreateFramebuffers();
            glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, color, 0);
            glNamedFramebufferTexture(framebuffer, GL_DEPTH_STENCIL_ATTACHMENT, depth, 0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE,
                    glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER));
            return new RawHostTarget(framebuffer, color, depth, width, height, generation);
        }

        void clear(float red, float green, float blue, float depth) {
            glClearNamedFramebufferfv(framebuffer, GL_COLOR, 0,
                    new float[]{red, green, blue, 1.0f});
            glClearNamedFramebufferfi(framebuffer, GL_DEPTH_STENCIL, 0, depth, 0);
        }

        int[] readCenter() {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            glReadPixels(width / 2, height / 2, 1, 1,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            return new int[]{
                    Byte.toUnsignedInt(pixel.get(0)),
                    Byte.toUnsignedInt(pixel.get(1)),
                    Byte.toUnsignedInt(pixel.get(2)),
                    Byte.toUnsignedInt(pixel.get(3))};
        }

        int maximumRedMinusBlue() {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            int maximum = Integer.MIN_VALUE;
            for (int offset = 0; offset < pixels.capacity(); offset += 4) {
                maximum = Math.max(maximum,
                        Byte.toUnsignedInt(pixels.get(offset))
                                - Byte.toUnsignedInt(pixels.get(offset + 2)));
            }
            return maximum;
        }

        @Override
        public void close() {
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(depthStencil);
            glDeleteTextures(color);
        }
    }
}
