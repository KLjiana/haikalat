package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.lwjgl.BufferUtils;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

/** 为真实 OpenGL 回归测试提供共享的隐藏窗口和底层资源夹具。 */
final class GlTestSupport {
    private GlTestSupport() {
    }

    /** @return 一个 32×32、默认不可见的 GLFW 测试窗口 */
    static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder()
                .dimensions(32, 32)
                .title("GL Smoke Test")
                .visible(false)
                .build();
    }

    /** @return 当前 framebuffer 中心位置的深度值 */
    static float readCenterDepth() {
        FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        glReadPixels(16, 16, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        return depth.get(0);
    }

    /** @return 由当前上下文创建、可用于生命周期测试的最小纹理 */
    static Texture2D generatedTexture() throws ReflectiveOperationException {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        return texture(id);
    }

    /** @return 以 sRGB 8-bit 存储的 1×1 中性灰测试纹理 */
    static Texture2D generatedSrgbTexture(int encodedValue) throws ReflectiveOperationException {
        if (encodedValue < 0 || encodedValue > 255) {
            throw new IllegalArgumentException("encodedValue must be within [0, 255]");
        }
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        ByteBuffer pixel = BufferUtils.createByteBuffer(4)
                .put((byte) encodedValue)
                .put((byte) encodedValue)
                .put((byte) encodedValue)
                .put((byte) 0xFF)
                .flip();
        glTexImage2D(GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8,
                1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        Constructor<Texture2D> constructor = Texture2D.class.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, TextureColorSpace.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, 1, 1,
                org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8, TextureColorSpace.SRGB);
    }

    private static Texture2D texture(int id) throws ReflectiveOperationException {
        Constructor<Texture2D> constructor =
                Texture2D.class.getDeclaredConstructor(int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, 1, 1, GL_RGBA);
    }
}
