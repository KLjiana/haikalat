package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static com.kaleblangley.haikalat.integration.GlTestSupport.generatedTexture;
import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.*;

/** 验证 native id 复用不会欺骗跨提交保存的 OpenGL 状态缓存。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GlResourceLifecycleTest {
    private static final String VERTEX = """
            #version 330 core
            void main() { gl_Position = vec4(0.0); }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            out vec4 color;
            void main() { color = vec4(1.0); }
            """;

    @BeforeAll
    static void reportPerformanceConfiguration() {
        System.out.println("[GlResourceLifecycle] config objects=0 size=32x32 warmup=0 rounds=1");
    }

    @Test
    void deletionEpochRebindsReusedVaoProgramTextureAndFramebufferIds() throws Exception {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            VertexArray oldVao = new VertexArray();
            ShaderProgram oldProgram = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Texture2D oldTexture = generatedTexture();
            Framebuffer oldFramebuffer = Framebuffer.colorOnly(8, 8);
            int vaoId = oldVao.id();
            int programId = oldProgram.id();
            int textureId = oldTexture.id();
            int framebufferId = oldFramebuffer.id();

            device.execute(bindings(vaoId, programId, textureId, framebufferId));
            glUseProgram(0); // 解除 deferred program deletion，随后验证相同数字 id 的首次提交。
            oldFramebuffer.close();
            oldTexture.close();
            oldProgram.close();
            oldVao.close();

            try (VertexArray vao = reusedVao(vaoId);
                 ShaderProgram program = reusedProgram(programId);
                 Texture2D texture = reusedTexture(textureId);
                 Framebuffer framebuffer = reusedFramebuffer(framebufferId)) {
                device.resetStateStatistics();
                device.execute(bindings(vao.id(), program.id(), texture.id(), framebuffer.id()));

                assertEquals(vao.id(), glGetInteger(GL_VERTEX_ARRAY_BINDING));
                assertEquals(program.id(), glGetInteger(GL_CURRENT_PROGRAM));
                assertEquals(texture.id(), glGetInteger(GL_TEXTURE_BINDING_2D));
                assertEquals(framebuffer.id(), glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
                assertTrue(device.stateStatistics().appliedChanges() >= 4,
                        "epoch change must force the first submission to rebuild cached bindings");
                GlDebug.assertNoError("reused resource ids");
            }
        }
    }

    @Test
    void deletionEpochIsScopedToCapabilitiesIdentity() {
        try (GlfwWindow firstWindow = hiddenWindow()) {
            firstWindow.bindContext();
            GLCapabilities firstCapabilities = GL.createCapabilities();
            GlRenderDevice firstDevice = new GlRenderDevice();
            try (VertexArray firstVao = new VertexArray()) {
                CommandBuffer binding = new CommandBuffer().bindVertexArray(firstVao.id());
                firstDevice.execute(binding);

                long secondWindow = glfwCreateWindow(32, 32, "Epoch isolation", 0L, 0L);
                assertNotEquals(0L, secondWindow);
                try {
                    glfwMakeContextCurrent(secondWindow);
                    GL.createCapabilities();
                    try (VertexArray secondVao = new VertexArray()) {
                        secondVao.close();
                    }
                    GlDebug.releaseCurrentContext();
                    GL.setCapabilities(null);
                } finally {
                    glfwDestroyWindow(secondWindow);
                    firstWindow.bindContext();
                    GL.setCapabilities(firstCapabilities);
                }

                firstDevice.resetStateStatistics();
                firstDevice.execute(binding);
                assertEquals(0L, firstDevice.stateStatistics().appliedChanges());
                assertEquals(1L, firstDevice.stateStatistics().avoidedChanges());
            }
        }
    }

    @Test
    void oneDeviceCannotCarryCachedBindingsAcrossContexts() {
        try (GlfwWindow firstWindow = hiddenWindow()) {
            firstWindow.bindContext();
            GLCapabilities firstCapabilities = GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            try (VertexArray firstVao = new VertexArray()) {
                device.execute(new CommandBuffer().bindVertexArray(firstVao.id()));

                long secondWindow = glfwCreateWindow(32, 32, "Epoch context switch", 0L, 0L);
                assertNotEquals(0L, secondWindow);
                try {
                    glfwMakeContextCurrent(secondWindow);
                    GL.createCapabilities();
                    try (VertexArray secondVao = new VertexArray()) {
                        device.resetStateStatistics();
                        device.execute(new CommandBuffer().bindVertexArray(secondVao.id()));
                        assertEquals(1L, device.stateStatistics().appliedChanges(),
                                "entering another context must invalidate cached bindings");
                    }
                    GlDebug.releaseCurrentContext();
                    GL.setCapabilities(null);
                } finally {
                    glfwDestroyWindow(secondWindow);
                    firstWindow.bindContext();
                    GL.setCapabilities(firstCapabilities);
                }

                device.resetStateStatistics();
                device.execute(new CommandBuffer().bindVertexArray(firstVao.id()));
                assertEquals(1L, device.stateStatistics().appliedChanges(),
                        "returning to the original context must invalidate cached bindings");
            }
        }
    }

    @Test
    void repeatedVaoRebuildsInvalidateExactlyAtSubmissionBoundary() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            VertexArray vao = new VertexArray();
            int reusedId = vao.id();
            try {
                for (int cycle = 0; cycle < 128; cycle++) {
                    device.execute(new CommandBuffer().bindVertexArray(vao.id()));
                    vao.close();
                    vao = new VertexArray();
                    assertEquals(reusedId, vao.id(), "driver stopped reusing the VAO id at cycle " + cycle);

                    device.resetStateStatistics();
                    device.execute(new CommandBuffer().bindVertexArray(vao.id()));
                    assertEquals(vao.id(), glGetInteger(GL_VERTEX_ARRAY_BINDING));
                    assertEquals(1L, device.stateStatistics().appliedChanges(),
                            "the first submission after deletion must rebuild the VAO binding");
                }
                GlDebug.assertNoError("128 VAO rebuild cycles");
            } finally {
                vao.close();
            }
        }
    }

    @Test
    void framebufferResizeStyleSoakCannotReuseStaleBinding() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Framebuffer framebuffer = Framebuffer.colorOnly(8, 8);
            int reusedId = framebuffer.id();
            try {
                for (int cycle = 0; cycle < 100; cycle++) {
                    device.execute(new CommandBuffer().bindFramebuffer(GL_FRAMEBUFFER, framebuffer.id()));
                    framebuffer.close();
                    int extent = 8 + cycle % 9;
                    framebuffer = Framebuffer.colorOnly(extent, extent + 1);
                    assertEquals(reusedId, framebuffer.id(),
                            "driver stopped reusing the framebuffer id at cycle " + cycle);

                    device.resetStateStatistics();
                    device.execute(new CommandBuffer().bindFramebuffer(GL_FRAMEBUFFER, framebuffer.id()));
                    assertEquals(framebuffer.id(), glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
                    assertTrue(device.stateStatistics().appliedChanges() >= 1,
                            "the first submission after resize-style rebuild must rebind the framebuffer");
                }
                GlDebug.assertNoError("100 framebuffer rebuild cycles");
            } finally {
                framebuffer.close();
            }
        }
    }

    @Test
    void noCapabilitiesEpochCheckDoesNotInitializeGlfw() {
        GLCapabilities previous;
        try {
            previous = GL.getCapabilities();
        } catch (IllegalStateException ignored) {
            previous = null;
        }
        GL.setCapabilities(null);
        try {
            assertDoesNotThrow(GlDebug::contextStateEpoch);
            assertEquals(Long.MIN_VALUE, GlDebug.contextStateEpoch());
        } finally {
            if (previous != null) GL.setCapabilities(previous);
        }
    }

    private static CommandBuffer bindings(int vao, int program, int texture, int framebuffer) {
        return new CommandBuffer().useProgram(program).bindVertexArray(vao)
                .bindTexture(0, texture).bindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    }

    private static VertexArray reusedVao(int expected) {
        VertexArray value = new VertexArray();
        assertEquals(expected, value.id(), "driver did not immediately reuse the deleted VAO id");
        return value;
    }

    private static ShaderProgram reusedProgram(int expected) {
        ShaderProgram value = ShaderProgram.fromSources(VERTEX, FRAGMENT);
        assertEquals(expected, value.id(), "driver did not immediately reuse the deleted program id");
        return value;
    }

    private static Texture2D reusedTexture(int expected) throws Exception {
        Texture2D value = generatedTexture();
        assertEquals(expected, value.id(), "driver did not immediately reuse the deleted texture id");
        return value;
    }

    private static Framebuffer reusedFramebuffer(int expected) {
        Framebuffer value = Framebuffer.colorOnly(8, 8);
        assertEquals(expected, value.id(), "driver did not immediately reuse the deleted framebuffer id");
        return value;
    }
}
