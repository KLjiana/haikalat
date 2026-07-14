package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.assets.TextureAssetCache;
import com.kaleblangley.haikalat.runtime.GlRenderThread;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

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
}

