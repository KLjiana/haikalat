package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.assets.TextureAssetCache;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;

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
