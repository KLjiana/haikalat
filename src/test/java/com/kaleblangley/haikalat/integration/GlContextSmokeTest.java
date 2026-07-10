package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
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
import com.kaleblangley.haikalat.core.mesh.VertexAttribute;
import com.kaleblangley.haikalat.core.mesh.VertexLayout;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
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
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

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
                    Files.readString(resources.resolve("instanced_projview.frag")));
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
