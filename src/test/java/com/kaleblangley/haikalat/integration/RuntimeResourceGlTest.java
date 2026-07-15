package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.assets.TextureAssetCache;
import com.kaleblangley.haikalat.runtime.GlRenderThread;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.kaleblangley.haikalat.integration.GlTestSupport.generatedTexture;
import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL21.GL_SRGB8;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL30.*;

/** 验证渲染线程关闭协议和 OpenGL 资源生命周期。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class RuntimeResourceGlTest {
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

    private static final String TEXTURE_SAMPLE_VERTEX_SOURCE = """
            #version 330 core
            const vec2 positions[3] = vec2[](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
            out vec2 vUv;
            void main() {
                vec2 position = positions[gl_VertexID];
                vUv = position * 0.5 + 0.5;
                gl_Position = vec4(position, 0.0, 1.0);
            }
            """;

    private static final String TEXTURE_SAMPLE_FRAGMENT_SOURCE = """
            #version 330 core
            in vec2 vUv;
            out vec4 FragColor;
            uniform sampler2D uTexture;
            void main() {
                FragColor = texture(uTexture, vUv);
            }
            """;

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
                                // shutdown 超时后会中断线程；测试主动释放当前帧前继续等待。
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
    void srgbTextureUsesSrgbStorageAndDecodesDuringSampling() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Texture2D linear = Texture2D.fromResource(RuntimeResourceGlTest.class,
                    "/wall.png", false, TextureColorSpace.LINEAR);
            Texture2D srgb = Texture2D.fromResource(RuntimeResourceGlTest.class,
                    "/wall.png", false, TextureColorSpace.SRGB);
            Framebuffer target = Framebuffer.colorOnly(32, 32);
            ShaderProgram shader = ShaderProgram.fromSources(
                    TEXTURE_SAMPLE_VERTEX_SOURCE, TEXTURE_SAMPLE_FRAGMENT_SOURCE);
            int vao = glGenVertexArrays();
            try {
                assertTrue(linear.format() == GL_RGB8 || linear.format() == GL_RGBA8);
                assertTrue(srgb.format() == GL_SRGB8 || srgb.format() == GL_SRGB8_ALPHA8);
                assertEquals(TextureColorSpace.LINEAR, linear.colorSpace());
                assertEquals(TextureColorSpace.SRGB, srgb.colorSpace());

                long linearSum = sampledRgbSum(linear, target, shader, vao);
                long srgbSum = sampledRgbSum(srgb, target, shader, vao);
                assertTrue(srgbSum + 1_000 < linearSum,
                        "sRGB sampling must decode encoded color values into lower linear values");
                GlDebug.checkError("srgbTextureUsesSrgbStorageAndDecodesDuringSampling");
            } finally {
                glDeleteVertexArrays(vao);
                shader.close();
                target.close();
                srgb.close();
                linear.close();
            }
        }
    }

    private static long sampledRgbSum(Texture2D texture, Framebuffer target,
                                      ShaderProgram shader, int vao) {
        target.bind();
        glViewport(0, 0, target.width(), target.height());
        glDisable(GL_FRAMEBUFFER_SRGB);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        shader.use().setInt("uTexture", 0);
        texture.bind(0);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);

        ByteBuffer pixels = BufferUtils.createByteBuffer(target.width() * target.height() * 4);
        glReadPixels(0, 0, target.width(), target.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        long sum = 0L;
        for (int index = 0; index < pixels.capacity(); index += 4) {
            sum += Byte.toUnsignedInt(pixels.get(index));
            sum += Byte.toUnsignedInt(pixels.get(index + 1));
            sum += Byte.toUnsignedInt(pixels.get(index + 2));
        }
        return sum;
    }
}
