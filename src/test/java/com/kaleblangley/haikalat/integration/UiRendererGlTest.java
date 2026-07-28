package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.ui.render.UiBatcher;
import com.kaleblangley.haikalat.subsystems.ui.render.UiBlendMode;
import com.kaleblangley.haikalat.subsystems.ui.render.UiDisplayList;
import com.kaleblangley.haikalat.subsystems.ui.render.UiRenderSnapshot;
import com.kaleblangley.haikalat.subsystems.ui.render.UiRenderer;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.render.UiSdfDecoration;
import com.kaleblangley.haikalat.subsystems.ui.render.UiSdfShape;
import com.kaleblangley.haikalat.subsystems.ui.render.UiUvRect;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGetBoolean;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glIsEnabled;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB;
import static org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glTextureStorage2D;
import static org.lwjgl.opengl.GL45.glTextureSubImage2D;

/** UI renderer 的真实默认 framebuffer、混合、裁剪和 persistent ring 回归。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class UiRendererGlTest {
    @Test
    void multipleBatchesKeepTheirOwnVertexOffsetsAndClipRectangles() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            clear(device, 0.0f, 0.0f, 0.0f);

            UiDisplayList list = new UiDisplayList()
                    .addSolidQuad(new UiScreenRect(1, 1, 6, 6), 0xff0000ff,
                            UiBlendMode.PREMULTIPLIED_ALPHA)
                    .pushClip(new UiScreenRect(10, 10, 5, 5))
                    .addSolidQuad(new UiScreenRect(8, 8, 10, 10), 0x00ff00ff,
                            UiBlendMode.PREMULTIPLIED_ALPHA)
                    .popClip()
                    .addSolidQuad(new UiScreenRect(24, 24, 6, 6), 0x0000ffff,
                            UiBlendMode.PREMULTIPLIED_ALPHA);

            try (UiRenderer renderer = new UiRenderer(16)) {
                var commands = device.createCommandBuffer();
                renderer.record(snapshot(4, list), commands);
                device.execute(commands);

                assertColor(pixel(3, 28), 255, 0, 0);
                assertColor(pixel(12, 19), 0, 255, 0);
                assertColor(pixel(9, 22), 0, 0, 0);
                assertColor(pixel(27, 5), 0, 0, 255);
                assertEquals(3, renderer.lastDrawCalls());
                GlDebug.assertNoError("UiRenderer multi-batch offsets and clips");
            }
        }
    }

    @Test
    void monitorContentScaleDoesNotDistortFramebufferScissor() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            clear(device, 0.0f, 0.0f, 0.0f);
            UiDisplayList list = new UiDisplayList()
                    .pushClip(new UiScreenRect(0, 0, 16, 32))
                    .addSolidQuad(new UiScreenRect(0, 0, 32, 32), 0xffffffff,
                            UiBlendMode.PREMULTIPLIED_ALPHA)
                    .popClip();
            UiRenderSnapshot snapshot = UiRenderSnapshot.capture(5,
                    32, 32, 32, 32, 1.25, 1.25, list, new UiBatcher());

            try (UiRenderer renderer = new UiRenderer(16)) {
                var commands = device.createCommandBuffer();
                renderer.record(snapshot, commands);
                device.execute(commands);

                assertColor(pixel(8, 16), 255, 255, 255);
                assertColor(pixel(18, 16), 0, 0, 0);
                GlDebug.assertNoError("UiRenderer framebuffer scale independent of monitor DPI");
            }
        }
    }

    @Test
    void solidQuadBlendsThroughTypedStateAndLeavesScissorDisabled() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            clear(device, 0.0f, 0.0f, 1.0f);

            UiDisplayList list = new UiDisplayList()
                    .pushClip(new UiScreenRect(0, 0, 16, 32))
                    .addSolidQuad(new UiScreenRect(0, 0, 32, 32), 0x80000080,
                            UiBlendMode.PREMULTIPLIED_ALPHA)
                    .popClip();
            UiRenderSnapshot snapshot = snapshot(1, list);

            try (UiRenderer renderer = new UiRenderer(64)) {
                var commands = device.createCommandBuffer();
                renderer.record(snapshot, commands);
                device.execute(commands);

                int[] blended = pixel(4, 16);
                int[] untouched = pixel(24, 16);
                assertTrue(blended[0] > 80 && blended[2] > 80 && blended[1] < 10,
                        "left pixel should contain premultiplied red over blue");
                assertTrue(untouched[0] < 10 && untouched[1] < 10 && untouched[2] > 240,
                        "scissor must preserve the right half");
                assertEquals(1, renderer.lastDrawCalls());
                assertEquals(1, renderer.lastQuadCount());
                assertUiState();
                GlDebug.assertNoError("UiRenderer solid typed path");
            }
        }
    }

    @Test
    void texturedQuadUsesStraightAlphaTextureAndPremultipliesInShader() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            clear(device, 0.0f, 0.0f, 0.0f);
            int texture = glCreateTextures(GL_TEXTURE_2D);
            try {
                glTextureStorage2D(texture, 1, GL_RGBA8, 1, 1);
                ByteBuffer rgba = BufferUtils.createByteBuffer(4)
                        .put((byte) 0).put((byte) 255).put((byte) 0).put((byte) 128).flip();
                glTextureSubImage2D(texture, 0, 0, 0, 1, 1,
                        GL_RGBA, GL_UNSIGNED_BYTE, rgba);
                UiDisplayList list = new UiDisplayList()
                        .addTexturedQuad(new UiScreenRect(0, 0, 32, 32), UiUvRect.FULL,
                                texture, 0, 0xffffffff, UiBlendMode.PREMULTIPLIED_ALPHA);

                try (UiRenderer renderer = new UiRenderer(16)) {
                    var commands = device.createCommandBuffer();
                    renderer.record(snapshot(2, list), commands);
                    device.execute(commands);

                    int[] center = pixel(16, 16);
                    assertTrue(center[1] > 80 && center[0] < 10 && center[2] < 10,
                            "straight-alpha green texture should become a visible premultiplied pixel");
                    assertUiState();
                    GlDebug.assertNoError("UiRenderer textured path");
                }
            } finally {
                glDeleteTextures(texture);
            }
        }
    }

    @Test
    void sdfRoundedRectProducesAnalyticCoverageAndKeepsQuadPathState() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            clear(device, 0.0f, 0.0f, 0.0f);
            UiDisplayList list = new UiDisplayList()
                    .addSdfShape(new UiScreenRect(2, 2, 28, 28),
                            UiSdfShape.roundedRect(8.0f),
                            UiSdfDecoration.solid(UiColor.fromSrgbHex(0xff0000ff)),
                            UiBlendMode.PREMULTIPLIED_ALPHA);

            try (UiRenderer renderer = new UiRenderer(16)) {
                var commands = device.createCommandBuffer();
                renderer.record(snapshot(9, list), commands);
                device.execute(commands);

                int[] center = pixel(16, 16);
                int[] corner = pixel(2, 2);
                int[] edge = pixel(16, 3);
                assertTrue(center[0] > 240 && center[1] < 10 && center[2] < 10,
                        "SDF center should be filled red");
                assertTrue(corner[0] < 10 && corner[1] < 10 && corner[2] < 10,
                        "rounded corner should remain transparent");
                assertTrue(edge[0] > 100,
                        "SDF edge should have analytic anti-aliased coverage");
                assertEquals(1, renderer.lastDrawCalls());
                assertUiState();
                GlDebug.assertNoError("UiRenderer SDF rounded rect path");
            }
        }
    }

    @Test
    void uint16LimitSplitsOneLargeBatchWithoutRecreatingFrameResources() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            int quads = UiRenderer.MAX_QUADS_PER_DRAW + 1;
            UiDisplayList list = new UiDisplayList(8, quads);
            for (int index = 0; index < quads; index++) {
                list.addSolidQuad(new UiScreenRect(0, 0, 1, 1), 0xffffffff,
                        UiBlendMode.PREMULTIPLIED_ALPHA);
            }

            try (UiRenderer renderer = new UiRenderer(quads)) {
                var first = device.createCommandBuffer();
                UiRenderSnapshot snapshot = snapshot(3, list);
                renderer.record(snapshot, first);
                device.execute(first);
                assertEquals(2, renderer.lastDrawCalls());

                var second = device.createCommandBuffer();
                renderer.record(snapshot, second);
                device.execute(second);
                assertEquals(2, renderer.lastDrawCalls());
                GlDebug.assertNoError("UiRenderer uint16 split and reuse");
            }
        }
    }

    private static UiRenderSnapshot snapshot(long sequence, UiDisplayList list) {
        return UiRenderSnapshot.capture(sequence, 32, 32, 32, 32,
                1.0, 1.0, list, new UiBatcher());
    }

    private static void clear(GlRenderDevice device, float red, float green, float blue) {
        device.execute(device.createCommandBuffer()
                .bindDefaultFramebuffer()
                .viewport(0, 0, 32, 32)
                .enableFramebufferSrgb(false)
                .enableScissor(false)
                .enableBlend(false)
                .depthMask(true)
                .clearColor(red, green, blue, 1.0f)
                .clear(true, false));
    }

    private static void assertUiState() {
        assertEquals(0, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
        assertTrue(glIsEnabled(GL_FRAMEBUFFER_SRGB));
        assertTrue(glIsEnabled(GL_BLEND));
        assertEquals(GL_ONE, glGetInteger(GL_BLEND_SRC_RGB));
        assertEquals(GL_ONE_MINUS_SRC_ALPHA, glGetInteger(GL_BLEND_DST_RGB));
        assertFalse(glIsEnabled(GL_DEPTH_TEST));
        assertFalse(glGetBoolean(GL_DEPTH_WRITEMASK));
        assertFalse(glIsEnabled(GL_CULL_FACE));
        assertFalse(glIsEnabled(GL_SCISSOR_TEST));
    }

    private static int[] pixel(int x, int y) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        return new int[]{Byte.toUnsignedInt(pixel.get(0)), Byte.toUnsignedInt(pixel.get(1)),
                Byte.toUnsignedInt(pixel.get(2)), Byte.toUnsignedInt(pixel.get(3))};
    }

    private static void assertColor(int[] pixel, int red, int green, int blue) {
        assertEquals(red, pixel[0], 2, "red");
        assertEquals(green, pixel[1], 2, "green");
        assertEquals(blue, pixel[2], 2, "blue");
    }
}
