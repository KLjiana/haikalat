package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/** 验证 HDR TAA history 的真实格式、resize 重建和有效状态复位。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class TaaHistoryGlTest {
    @Test
    void hdrHistoryResizeRebuildsFloatTargetAndInvalidatesAccumulation() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(32, 32)
                .title("TAA History GL Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();

            try (TaaHistory history = new TaaHistory(32, 32, RenderFormat.RGBA16F)) {
                Framebuffer originalFramebuffer = history.framebuffer();
                history.markValid();
                assertTrue(history.valid());

                history.resize(48, 40);

                FramebufferDescriptor descriptor = history.framebuffer().descriptor();
                assertTrue(originalFramebuffer.isClosed());
                assertNotSame(originalFramebuffer, history.framebuffer());
                assertFalse(history.valid());
                assertEquals(0.0f, history.historyWeight());
                assertEquals(48, descriptor.width());
                assertEquals(40, descriptor.height());
                assertEquals(GL_RGBA16F,
                        descriptor.colorAttachments().getFirst().internalFormat());
            }
        }
    }
}
