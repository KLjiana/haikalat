package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.assets.TextureAssetCache;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.buffer.PackedInstanceBuffer;
import com.kaleblangley.haikalat.core.mesh.PackedInstanceLayout;
import com.kaleblangley.haikalat.core.upload.UploadSystem;
import com.kaleblangley.haikalat.demo.stress.GeneratedStressPrimitive;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.GlRenderThread;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import com.kaleblangley.haikalat.util.DirectBuffers;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glIsEnabled;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;

/**
 * Opt-in GL smoke test. Runs only with -Dhaikalat.glSmoke=true because it creates a hidden GLFW window.
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GlContextSmokeTest {
    private static final String VERTEX_SOURCE = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            void main() {
                gl_Position = vec4(aPos, 1.0);
            }
            """;

    private static final String FRAGMENT_SOURCE = """
            #version 330 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(1.0);
            }
            """;

    private static final String COMPUTE_SOURCE = """
            #version 460 core
            layout(local_size_x = 1) in;
            layout(std430, binding = 0) buffer Result {
                uint value;
            };
            uniform uint uInput;
            void main() {
                value = uInput + 1u;
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

    private static final String INVISIBLE_CASTER_VERTEX_SOURCE = """
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

    private static final String INVISIBLE_CASTER_FRAGMENT_SOURCE = """
            #version 330 core
            void main() {
                discard;
            }
            """;

    @Test
    void hiddenWindowCanClearAndReadBackPixel() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(32, 32)
                .title("GL Smoke Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            glViewport(0, 0, 32, 32);
            glClearColor(0.25f, 0.5f, 0.75f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);

            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);

            int red = Byte.toUnsignedInt(pixel.get(0));
            int green = Byte.toUnsignedInt(pixel.get(1));
            int blue = Byte.toUnsignedInt(pixel.get(2));
            assertTrue(red > 40 && green > 90 && blue > 150,
                    "Expected readback pixel to reflect the clear color");
        }
    }

    @Test
    void computeProgramWritesThroughNamedStorageBlock() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (ShaderProgram shader = ShaderProgram.fromComputeSource(COMPUTE_SOURCE);
                 GlBuffer result = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(Integer.BYTES)) {
                shader.bindStorageBlock("Result", 0).setUInt("uInput", 41);
                GlRenderDevice device = new GlRenderDevice();
                device.execute(device.createCommandBuffer()
                        .bindShader(shader)
                        .bindStorageBuffer(0, result, 0, Integer.BYTES)
                        .dispatchCompute(1, 1, 1)
                        .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT));

                ByteBuffer readback = BufferUtils.createByteBuffer(Integer.BYTES);
                result.read(0, readback);
                assertTrue(shader.isCompute());
                assertTrue(shader.hasStage(ShaderStage.COMPUTE));
                assertEquals(42, readback.getInt(0));
                GlDebug.checkError("computeProgramWritesThroughNamedStorageBlock");
            }
        }
    }

    @Test
    void indexedProceduralCubeWithCompactSsboProducesPixelsWithoutVbo() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            GeneratedStressPrimitive cube = GeneratedStressPrimitive.CUBE;
            ByteBuffer packedData = PackedInstanceLayout.allocate(1);
            PackedInstanceLayout.pack(packedData, 0,
                    0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f,
                    PackedInstanceLayout.packRgba8(1.0f, 0.25f, 0.1f, 1.0f));
            try (ShaderProgram shader = ShaderProgram.fromResource(GlContextSmokeTest.class,
                    cube.indexedSsboShaderResource(), "/demo/vertex_color_unlit.frag");
                 GlBuffer elementBuffer = GlBuffer.elementArrayBuffer(GL_STATIC_DRAW)
                         .upload(DirectBuffers.copyOf(cube.indices()));
                 VertexArray vao = new VertexArray();
                 PackedInstanceBuffer instances = PackedInstanceBuffer.immutable(packedData, 1);
                 Framebuffer target = Framebuffer.singleSampled(32, 32)) {
                vao.bindElementBuffer(elementBuffer);
                shader.bindStorageBlock("PackedInstances", 0);

                GlRenderDevice device = new GlRenderDevice();
                device.execute(device.createCommandBuffer()
                        .bindFramebuffer(target)
                        .viewport(0, 0, 32, 32)
                        .clearColor(0, 0, 0, 1)
                        .clear(true, true)
                        .enableDepthTest(true)
                        .enableCullFace(true)
                        .bindShader(shader)
                        .setUniformMat4(shader, "uProjView", new org.joml.Matrix4f())
                        .setUniformVec2(shader, "uTimeRotation", 1.0f, 0.0f)
                        .bindStorageBuffer(0, instances.buffer(), 0, instances.bindingSizeBytes())
                        .bindVertexArray(vao.id())
                        .drawElementsInstanced(GL_TRIANGLES, cube.indexCount(),
                                cube.indexType(), 0L, 1));

                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                assertTrue(Byte.toUnsignedInt(pixel.get(0)) > 100,
                        "Indexed compact cube must produce a non-empty center pixel");
                GlDebug.checkError("indexedProceduralCubeWithCompactSsboProducesPixelsWithoutVbo");
            }
        }
    }

    @Test
    void dynamicCompactSsboRingSlotsMeetDriverOffsetAlignment() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            ByteBuffer packedData = PackedInstanceLayout.allocate(7);
            try (PackedInstanceBuffer instances = PackedInstanceBuffer.dynamic(packedData, 7)) {
                assertEquals(0L, instances.slotStrideBytes() % instances.offsetAlignment());
                for (int slot = 0; slot < 3; slot++) {
                    instances.beginFrame();
                    assertEquals(0L, instances.bindingOffsetBytes() % instances.offsetAlignment());
                    assertEquals(7L * PackedInstanceLayout.STRIDE_BYTES,
                            instances.lastSynchronizedBytes());
                    instances.finishFrame();
                    glFinish();
                }

                ByteBuffer oneChangedInstance = PackedInstanceLayout.allocate(1);
                PackedInstanceLayout.pack(oneChangedInstance, 0,
                        1, 2, 3, 0.5f, 1, 0,
                        PackedInstanceLayout.packRgba8(1, 1, 1, 1));
                instances.updateRange(4, oneChangedInstance, 1);
                instances.beginFrame();
                assertEquals(PackedInstanceLayout.STRIDE_BYTES, instances.lastSynchronizedBytes(),
                        "A one-instance change must not rewrite the complete ring slot");
                instances.finishFrame();
                glFinish();
            }
        }
    }

    @Test
    void renderDeviceKeepsStateCacheAcrossCommandBuffers() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();

            GlRenderDevice device = new GlRenderDevice();
            var commands = device.createCommandBuffer()
                    .bindDefaultFramebuffer()
                    .viewport(0, 0, 32, 32)
                    .clearColor(0.1f, 0.2f, 0.3f, 1.0f)
                    .enableBlend(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .enableCullFace(false);

            device.execute(commands);
            long firstApplied = device.stateStatistics().appliedChanges();
            device.execute(commands);

            assertEquals(firstApplied, device.stateStatistics().appliedChanges(),
                    "Repeated command buffers must not reapply identical GL state");
            assertTrue(device.stateStatistics().avoidedChanges() >= 7L);
        }
    }

    @Test
    void pendingPipelineStateCollapsesAndCustomIsAFullBarrier() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();

            GlRenderDevice device = new GlRenderDevice();
            device.execute(device.createCommandBuffer()
                    .enableBlend(false)
                    .enableBlend(true)
                    .enableBlend(false)
                    .enableDepthTest(false)
                    .enableDepthTest(true)
                    .custom(() -> {
                        assertFalse(glIsEnabled(GL_BLEND));
                        assertTrue(glIsEnabled(GL_DEPTH_TEST));
                    }));

            assertEquals(2L, device.stateStatistics().appliedChanges(),
                    "Only final blend/depth values may reach StateCache before the barrier");

            device.execute(device.createCommandBuffer()
                    .enableBlend(true)
                    .custom(() -> glDisable(GL_BLEND))
                    .enableBlend(true)
                    .custom(() -> assertTrue(glIsEnabled(GL_BLEND),
                            "Explicit state after custom must be re-applied")));
            GlDebug.checkError("pendingPipelineStateCollapsesAndCustomIsAFullBarrier");
        }
    }

    @Test
    void depthMaskIsFlushedBeforeDepthClear() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (Framebuffer target = Framebuffer.singleSampled(32, 32)) {
                GlRenderDevice device = new GlRenderDevice();
                glClearDepth(0.25);
                device.execute(device.createCommandBuffer()
                        .bindFramebuffer(target)
                        .viewport(0, 0, 32, 32)
                        .depthMask(true)
                        .clear(false, true));
                assertEquals(0.25f, readCenterDepth(), 0.01f);

                glClearDepth(0.75);
                device.execute(device.createCommandBuffer()
                        .bindFramebuffer(target)
                        .depthMask(true)
                        .depthMask(false)
                        .clear(false, true));
                assertEquals(0.25f, readCenterDepth(), 0.01f,
                        "Final depthMask=false must take effect before glClear");

                device.execute(device.createCommandBuffer().depthMask(true));
                GlDebug.checkError("depthMaskIsFlushedBeforeDepthClear");
            }
        }
    }

    @Test
    void transparentStateDoesNotFoldAcrossDrawOrRenderGraphPass() {
        String halfRed = """
                #version 330 core
                out vec4 FragColor;
                void main() { FragColor = vec4(1.0, 0.0, 0.0, 0.5); }
                """;
        String halfBlue = """
                #version 330 core
                out vec4 FragColor;
                void main() { FragColor = vec4(0.0, 0.0, 1.0, 0.5); }
                """;
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (ShaderProgram red = ShaderProgram.fromSources(DEPTH_WRITE_VERTEX_SOURCE, halfRed);
                 ShaderProgram blue = ShaderProgram.fromSources(DEPTH_WRITE_VERTEX_SOURCE, halfBlue);
                 VertexArray vao = new VertexArray();
                 RenderGraph graph = new RenderGraph(32, 32)) {
                graph.addPass("Opaque")
                        .writeToBackbuffer()
                        .noClear()
                        .execute((resources, commands) -> commands
                                .clearColor(0, 0, 0, 1)
                                .clear(true, true)
                                .enableDepthTest(false)
                                .enableCullFace(false)
                                .enableBlend(true)
                                .enableBlend(false)
                                .bindShader(red)
                                .bindVertexArray(vao.id())
                                .drawArrays(GL_TRIANGLES, 0, 3));
                graph.addPass("Transparent")
                        .dependsOn("Opaque")
                        .writeToBackbuffer()
                        .noClear()
                        .execute((resources, commands) -> commands
                                .blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
                                .enableBlend(true)
                                .bindShader(blue)
                                .bindVertexArray(vao.id())
                                .drawArrays(GL_TRIANGLES, 0, 3));
                graph.compile();
                graph.execute(new GlRenderDevice());

                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                int redChannel = Byte.toUnsignedInt(pixel.get(0));
                int blueChannel = Byte.toUnsignedInt(pixel.get(2));
                assertTrue(redChannel > 105 && redChannel < 150,
                        "Opaque pass must write full red before transparent blending");
                assertTrue(blueChannel > 105 && blueChannel < 150,
                        "Transparent pass must blend blue after the opaque draw");
                GlDebug.checkError("transparentStateDoesNotFoldAcrossDrawOrRenderGraphPass");
            }
        }
    }

    @Test
    void renderThreadReportsInitAndFrameFailuresAfterCleanup() {
        try (GlfwWindow window = hiddenWindow()) {
            AtomicBoolean initCleanup = new AtomicBoolean();
            GlRenderThread initFailure = new GlRenderThread(window.handle(),
                    RenderSettings.builder().vsync(false).build(), commands -> {
                    })
                    .onInit(() -> {
                        throw new IllegalStateException("init failure");
                    })
                    .onCleanup(() -> initCleanup.set(true));

            assertThrows(CompletionException.class, () -> initFailure.start().join());
            assertTrue(initCleanup.get());
            assertEquals(GlRenderThread.State.FAILED, initFailure.state());

            AtomicBoolean frameCleanup = new AtomicBoolean();
            GlRenderThread frameFailure = new GlRenderThread(window.handle(),
                    RenderSettings.builder().vsync(false).build(), commands -> {
                        throw new IllegalStateException("frame failure");
                    })
                    .onCleanup(() -> frameCleanup.set(true));

            assertThrows(CompletionException.class, () -> frameFailure.start().join());
            assertTrue(frameCleanup.get());
            assertEquals(GlRenderThread.State.FAILED, frameFailure.state());
        }
    }

    @Test
    void renderThreadShutdownTimesOutThenCompletesAndCloseIsRepeatable() throws Exception {
        try (GlfwWindow window = hiddenWindow()) {
            CountDownLatch enteredFrame = new CountDownLatch(1);
            CountDownLatch releaseFrame = new CountDownLatch(1);
            AtomicInteger cleanups = new AtomicInteger();
            GlRenderThread thread = new GlRenderThread(window.handle(),
                    RenderSettings.builder().vsync(false).build(), commands -> {
                        enteredFrame.countDown();
                        boolean released = false;
                        while (!released) {
                            try {
                                released = releaseFrame.await(10, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ignored) {
                                // shutdown interrupts after timing out; keep waiting until the test releases the frame.
                            }
                        }
                    })
                    .onCleanup(cleanups::incrementAndGet);

            var completion = thread.start();
            assertTrue(enteredFrame.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> thread.shutdown(Duration.ofMillis(10)));
            releaseFrame.countDown();
            completion.join();
            thread.close();
            thread.close();

            assertEquals(1, cleanups.get());
            assertEquals(GlRenderThread.State.TERMINATED, thread.state());
            assertFalse(thread.isAlive());
        }
    }

    @Test
    void glResourcesRejectUseAfterClose() throws Exception {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Framebuffer framebuffer = Framebuffer.colorOnly(16, 16);
            assertFalse(framebuffer.isClosed());
            assertTrue(framebuffer.colorAttachment() > 0);
            framebuffer.close();
            assertTrue(framebuffer.isClosed());
            assertThrows(GlException.class, framebuffer::colorAttachment);

            ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
            shader.close();
            assertTrue(shader.isClosed());
            assertThrows(GlException.class, shader::use);

            Texture2D texture = generatedTexture();
            texture.close();
            assertTrue(texture.isClosed());
            assertThrows(GlException.class, () -> texture.bind(0));
        }
    }

    @Test
    void textureAssetCacheClosesTexturesAndRejectsReuseAfterClose() throws Exception {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            TextureAssetCache cache = new TextureAssetCache(ref -> {
                try {
                    return generatedTexture();
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            Texture2D texture = cache.get("generated");

            cache.close();

            assertTrue(texture.isClosed());
            assertThrows(IllegalStateException.class, () -> cache.get("generated"));
        }
    }

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
            ShaderProgram projectShadowShader = ShaderProgram.fromResource(GlContextSmokeTest.class,
                    "/shadows/directional_depth.vert", "/shadows/directional_depth.frag");
            ShaderProgram depthWriter = ShaderProgram.fromSources(DEPTH_WRITE_VERTEX_SOURCE, FRAGMENT_SOURCE);
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
            Scene scene = new Scene(new Camera());
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            try {
                for (AntiAliasingMode mode : AntiAliasingMode.values()) {
                    RenderSettings settings = RenderSettings.builder()
                            .antiAliasingMode(mode)
                            .vsync(false)
                            .build();
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings);
                    try {
                        pipeline.build();
                        pipeline.execute(new GlRenderDevice());
                        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                        glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                        assertTrue(Byte.toUnsignedInt(pixel.get(0)) > 0,
                                "Expected non-empty output for AA mode " + mode);
                        GlDebug.checkError("AA mode " + mode);
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
    void graphResizePreservesFixedSizeShadowTarget() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            List<Integer> depthIds = new ArrayList<>();
            RenderGraph graph = new RenderGraph(32, 32);
            graph.addPass("FixedShadow")
                    .createDepthTexture("FixedShadowDepth")
                    .fixedSize(64, 64)
                    .clearDepthOnly()
                    .execute((resources, commands) ->
                            depthIds.add(resources.depthAttachment("FixedShadowDepth")));
            try {
                GlRenderDevice device = new GlRenderDevice();
                graph.execute(device);
                graph.resize(48, 48);
                graph.execute(device);

                assertEquals(depthIds.get(0), depthIds.get(1),
                        "Window resize must not recreate a fixed-size shadow target");
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
                pipeline.resize(0, 0);
                assertEquals(32, pipeline.graph().width());
                assertEquals(32, pipeline.graph().height());
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
    void minimalAndAsyncInstanceAttributeContractProducesPixels() throws Exception {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Path resources = Path.of("src", "demo", "resources", "demo");
            ShaderProgram shader = ShaderProgram.fromSources(
                    Files.readString(resources.resolve("instanced_projview.vert")),
                    Files.readString(resources.resolve("vertex_color_unlit.frag")));
            VertexLayout layout = VertexLayout.interleaved(6 * Float.BYTES,
                    VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                    VertexAttribute.builder().index(1).size(3).type(GL_FLOAT)
                            .offsetBytes(3L * Float.BYTES).build());
            Mesh mesh = Mesh.from(MeshData.of("attribute-2-smoke", new float[]{
                    -0.8f, -0.8f, 0.0f, 1.0f, 0.0f, 0.0f,
                    0.8f, -0.8f, 0.0f, 0.0f, 1.0f, 0.0f,
                    0.0f, 0.8f, 0.0f, 0.0f, 0.0f, 1.0f
            }, layout));
            InstancedMeshBatch batch = InstancedMeshBatch.of(mesh, 1, 3);
            Framebuffer target = Framebuffer.singleSampled(32, 32);
            try {
                GlRenderDevice device = new GlRenderDevice();
                var commands = device.createCommandBuffer();
                commands.bindFramebuffer(target)
                        .viewport(0, 0, 32, 32)
                        .clearColor(0, 0, 0, 1)
                        .clear(true, true)
                        .bindShader(shader)
                        .setUniformMat4(shader, "uProjView", new org.joml.Matrix4f())
                        .drawInstancedBatch(batch, List.of(new org.joml.Matrix4f()));
                device.execute(commands);

                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                assertTrue(Byte.toUnsignedInt(pixel.get(0))
                                + Byte.toUnsignedInt(pixel.get(1))
                                + Byte.toUnsignedInt(pixel.get(2)) > 0,
                        "Attribute-3 instance matrices must produce visible batch geometry");
                GlDebug.checkError("minimalAndAsyncInstanceAttributeContractProducesPixels");
            } finally {
                target.close();
                batch.close();
                mesh.close();
                shader.close();
            }
        }
    }

    @Test
    void asyncUploadBufferDrivesInstancedDrawPixels() throws Exception {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Path resources = Path.of("src", "demo", "resources", "demo");
            ShaderProgram shader = ShaderProgram.fromSources(
                    Files.readString(resources.resolve("async_instanced.vert")),
                    Files.readString(resources.resolve("vertex_color_unlit.frag")));
            shader.bindUniformBlock("AsyncInstances", 1);
            Mesh mesh = Mesh.from(BuiltinMeshData.named(BuiltinMeshData.QUAD));
            GlBuffer matrices = GlBuffer.uniformBuffer(GL_DYNAMIC_DRAW).allocate(16L * 16 * Float.BYTES);
            Framebuffer target = Framebuffer.singleSampled(32, 32);
            UploadSystem uploads = new UploadSystem();
            AtomicBoolean published = new AtomicBoolean();
            try {
                FloatBuffer matrixData = BufferUtils.createFloatBuffer(16);
                new org.joml.Matrix4f().get(matrixData);
                matrixData.position(16).flip();
                uploads.uploadFloats(matrices, 0, matrixData, () -> published.set(true));
                uploads.flush();

                GlRenderDevice device = new GlRenderDevice();
                var commands = device.createCommandBuffer();
                commands.bindFramebuffer(target)
                        .viewport(0, 0, 32, 32)
                        .clearColor(0, 0, 0, 1)
                        .clear(true, true)
                        .bindShader(shader)
                        .setUniformMat4(shader, "uProjView", new org.joml.Matrix4f())
                        .bindUniformBuffer(1, matrices, 0, 16L * 16 * Float.BYTES)
                        .bindMesh(mesh)
                        .drawMeshInstanced(mesh, 1);
                device.execute(commands);

                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                assertTrue(published.get(), "Frame metadata must publish after the matrix upload");
                assertTrue(Byte.toUnsignedInt(pixel.get(0))
                                + Byte.toUnsignedInt(pixel.get(1))
                                + Byte.toUnsignedInt(pixel.get(2)) > 0,
                        "The upload-managed UBO must drive visible instance geometry");
                GlDebug.checkError("asyncUploadBufferDrivesInstancedDrawPixels");
            } finally {
                uploads.close();
                target.close();
                matrices.close();
                mesh.close();
                shader.close();
            }
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
        Path resources = Path.of("src", "demo", "resources", "demo");
        ShaderProgram receiverShader = ShaderProgram.fromSources(
                Files.readString(resources.resolve("color_scene.vert")),
                Files.readString(resources.resolve("lit_scene.frag")));
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

    private static long pixelDifference(byte[] left, byte[] right) {
        long difference = 0L;
        for (int i = 0; i < left.length; i += 4) {
            difference += Math.abs(Byte.toUnsignedInt(left[i]) - Byte.toUnsignedInt(right[i]));
            difference += Math.abs(Byte.toUnsignedInt(left[i + 1]) - Byte.toUnsignedInt(right[i + 1]));
            difference += Math.abs(Byte.toUnsignedInt(left[i + 2]) - Byte.toUnsignedInt(right[i + 2]));
        }
        return difference;
    }

    private static float readCenterDepth() {
        FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        glReadPixels(16, 16, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        return depth.get(0);
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder()
                .dimensions(32, 32)
                .title("GL Smoke Test")
                .visible(false)
                .build();
    }

    private static Texture2D generatedTexture() throws ReflectiveOperationException {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        return texture(id);
    }

    private static Texture2D texture(int id) throws ReflectiveOperationException {
        Constructor<Texture2D> ctor = Texture2D.class.getDeclaredConstructor(int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(id, 1, 1, GL_RGBA);
    }
}
