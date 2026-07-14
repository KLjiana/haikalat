package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static com.kaleblangley.haikalat.integration.GlTestSupport.readCenterDepth;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;

/** 验证 CommandBuffer 的状态折叠、边界顺序和跨帧 StateCache 行为。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class CommandStateGlTest {
    private static final String FULLSCREEN_VERTEX_SOURCE = """
            #version 330 core
            const vec2 positions[3] = vec2[](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
            void main() {
                gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
            }
            """;

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
            try (ShaderProgram red = ShaderProgram.fromSources(FULLSCREEN_VERTEX_SOURCE, halfRed);
                 ShaderProgram blue = ShaderProgram.fromSources(FULLSCREEN_VERTEX_SOURCE, halfBlue);
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
}

