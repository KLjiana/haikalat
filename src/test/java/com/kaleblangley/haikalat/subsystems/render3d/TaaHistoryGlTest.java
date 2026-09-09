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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/** Verifies the color+depth TAA history double buffer, commit and resize protocol. */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class TaaHistoryGlTest {
    @Test
    void hdrHistoryResizeRebuildsColorAndDepthTargetsAndInvalidatesAccumulation() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(32, 32)
                .title("TAA History GL Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();

            try (TaaHistory history = new TaaHistory(32, 32, RenderFormat.RGBA16F)) {
                Framebuffer read = history.readFramebuffer();
                Framebuffer write = history.writeFramebuffer();
                assertNotSame(read, write, "double buffered history must expose distinct slots");
                assertEquals(2, read.colorAttachmentCount());
                assertEquals(GL_RGBA16F,
                        read.descriptor().colorAttachments().get(0).internalFormat());
                assertEquals(GL_R32F,
                        read.descriptor().colorAttachments().get(1).internalFormat());
                assertFalse(history.valid());

                history.prepareFrame();
                history.commitSuccessfulFrame();
                assertTrue(history.valid());
                assertNotSame(read, history.readFramebuffer(),
                        "commit must swap the read slot to the written candidate");

                history.resize(48, 40);
                FramebufferDescriptor descriptor = history.readFramebuffer().descriptor();
                assertTrue(read.isClosed());
                assertFalse(history.valid());
                assertEquals(48, descriptor.width());
                assertEquals(40, descriptor.height());
                assertEquals(GL_R32F,
                        descriptor.colorAttachments().get(1).internalFormat());
            }
        }
    }

    @Test
    void failedFrameDoesNotPublishCandidate() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(16, 16)
                .title("TAA History Failure GL Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();

            try (TaaHistory history = new TaaHistory(16, 16, RenderFormat.RGBA16F)) {
                int originalRead = history.readFramebuffer().colorAttachment(0);
                history.prepareFrame();
                history.discardFrame();
                assertFalse(history.valid());
                assertEquals(originalRead, history.readFramebuffer().colorAttachment(0));
                history.prepareFrame();
                history.commitSuccessfulFrame();
                assertTrue(history.valid());
                assertNotEquals(originalRead, history.readFramebuffer().colorAttachment(0));
            }
        }
    }
}
