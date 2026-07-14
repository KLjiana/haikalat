package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
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
                    "/shadows/directional_depth.vert", "/shadows/directional_depth.frag");
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
}
