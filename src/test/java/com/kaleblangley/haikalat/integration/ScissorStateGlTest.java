package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;

/** 验证 typed scissor 的像素语义、状态缓存和 RenderGraph pass 基线。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ScissorStateGlTest {
    @Test
    void typedScissorIsFlushedBeforeClearAndCustomBoundary() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();

            device.execute(device.createCommandBuffer()
                    .bindDefaultFramebuffer()
                    .viewport(0, 0, 32, 32)
                    .enableScissor(false)
                    .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                    .clear(true, false));
            device.execute(device.createCommandBuffer()
                    .scissor(0, 0, 8, 8)
                    .enableScissor(false)
                    .enableScissor(true)
                    .clearColor(1.0f, 0.0f, 0.0f, 1.0f)
                    .clear(true, false)
                    .custom(() -> assertTrue(glIsEnabled(GL_SCISSOR_TEST),
                            "custom must observe the final pending scissor state")));

            assertColor(4, 4, 255, 0, 0);
            assertColor(16, 16, 0, 0, 0);
            device.execute(device.createCommandBuffer().enableScissor(false));
            GlDebug.checkError("typedScissorIsFlushedBeforeClearAndCustomBoundary");
        }
    }

    @Test
    void repeatedScissorSkipsAndInvalidateForcesReapply() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            var commands = device.createCommandBuffer()
                    .scissor(2, 3, 4, 5)
                    .enableScissor(true);

            device.resetStateStatistics();
            device.execute(commands);
            assertEquals(2L, device.stateStatistics().appliedChanges());
            device.execute(commands);
            assertEquals(2L, device.stateStatistics().appliedChanges());
            assertEquals(2L, device.stateStatistics().avoidedChanges());

            device.invalidateState();
            device.execute(commands);
            assertEquals(4L, device.stateStatistics().appliedChanges());
            device.execute(device.createCommandBuffer().enableScissor(false));
            GlDebug.checkError("repeatedScissorSkipsAndInvalidateForcesReapply");
        }
    }

    @Test
    void renderGraphPassBaselineDisablesLeakedScissor() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (RenderGraph graph = new RenderGraph(32, 32)) {
                graph.addPass("Clipped")
                        .writeToBackbuffer()
                        .noClear()
                        .execute((resources, commands) -> commands
                                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                                .clear(true, false)
                                .scissor(0, 0, 8, 8)
                                .enableScissor(true)
                                .clearColor(1.0f, 0.0f, 0.0f, 1.0f)
                                .clear(true, false));
                graph.addPass("Following")
                        .dependsOn("Clipped")
                        .writeToBackbuffer()
                        .noClear()
                        .execute((resources, commands) -> commands
                                .clearColor(0.0f, 0.0f, 1.0f, 1.0f)
                                .clear(true, false));
                graph.compile();
                graph.execute(new GlRenderDevice());

                assertColor(4, 4, 0, 0, 255);
                assertColor(16, 16, 0, 0, 255);
                assertFalse(glIsEnabled(GL_SCISSOR_TEST),
                        "the following pass baseline must leave scissor disabled");
                GlDebug.checkError("renderGraphPassBaselineDisablesLeakedScissor");
            }
        }
    }

    private static void assertColor(int x, int y, int red, int green, int blue) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        assertAll(
                () -> assertEquals(red, Byte.toUnsignedInt(pixel.get(0)), 2, "red"),
                () -> assertEquals(green, Byte.toUnsignedInt(pixel.get(1)), 2, "green"),
                () -> assertEquals(blue, Byte.toUnsignedInt(pixel.get(2)), 2, "blue"));
    }
}
