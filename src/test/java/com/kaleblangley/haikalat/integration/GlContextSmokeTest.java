package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;

/**
 * Opt-in GL smoke test. Runs only with -Dhaikalat.glSmoke=true because it creates a hidden GLFW window.
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GlContextSmokeTest {
    @Test
    void hiddenWindowCanClearAndReadBackPixel() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(32, 32)
                .title("GL Smoke Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();

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
}
