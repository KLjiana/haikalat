package com.kaleblangley.haikalat.subsystems.postprocess.distortion;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenDistortion;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenDistortionSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL45C;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;

/**
 * Pixel readbacks for #4 on a 32x32 fixture in a real OpenGL context.
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ScreenDistortionPassGlTest {
    private static final int SIZE = 32;

    @Test
    void distortionChangesBackgroundButRejectsForegroundAndProtectsEdges() {
        withFixture((device, target, pass, source, depthTexture) -> {
            ByteBuffer baseline = render(device, target, pass, source.id(),
                    depthTexture, List.of());
            assertRed(baseline, 0, 16, 0);
            assertRed(baseline, 16, 16, 128);
            assertRed(baseline, 31, 16, 248);

            ScreenDistortion shiftRight = contribution(1L, 0, 0.0625f, 0.8f);
            ByteBuffer distorted = render(device, target, pass, source.id(),
                    depthTexture, List.of(shiftRight));
            // Right half is behind the effect plane: roughly two pixels to
            // the right, so it reads close to baseline texel 22 (176).
            assertTrue(Math.abs(red(distorted, 20, 16) - 176) <= 3,
                    "the background must sample at the displaced UV, got "
                            + red(distorted, 20, 16));
            assertTrue(changedPixels(baseline, distorted) > 200,
                    "the visible half of the impact must be distorted");
            // Left half is foreground: the distortion is rejected exactly.
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE / 2; x++) {
                    assertEquals(red(baseline, x, y), red(distorted, x, y),
                            "foreground pixel " + x + "," + y
                                    + " must not be distorted");
                }
            }

            // At x=16 a small leftward shift stays on the background in a
            // nearest lookup but the bilinear color footprint includes x=15.
            var subpixelLeft = contribution(2L, 0, -0.01171875f, 0.8f);
            ByteBuffer subpixel = render(device, target, pass, source.id(),
                    depthTexture, List.of(subpixelLeft));
            assertRed(subpixel, 16, 16, 128);
            assertRed(subpixel, 17, 16, 133);

            // Overlap: the nearer (depth 0.6) contribution wins over the
            // farther (depth 0.95) one even though it is submitted first.
            ScreenDistortion near = contribution(3L, 0, -0.0625f, 0.6f);
            ScreenDistortion far = contribution(4L, 0, 0.0625f, 0.95f);
            ByteBuffer overlapped = render(device, target, pass, source.id(),
                    depthTexture, List.of(near, far));
            // With the foreground blocking the near layer at x=16, keep the
            // already validated far layer. At x=20 both targets are clear.
            assertEquals(red(distorted, 16, 16), red(overlapped, 16, 16),
                    "a rejected near layer must not erase a valid far layer");
            int overlap = red(overlapped, 20, 16);
            assertTrue(Math.abs(overlap - 145) <= 3,
                    "nearer contribution must win the overlap, got " + overlap);
            assertTrue(overlap < 160,
                    "farther contribution must not overwrite the nearer one");

            // UV edge fallback: a negative offset that leaves the image
            // copies the original UV instead of smearing the border.
            ScreenDistortion edge = new ScreenDistortion(5L, 0,
                    new Vector2f(0.1f, 0.5f), new Vector2f(0.3f, 0.3f),
                    new Vector2f(-0.0625f, 0.0f), 1.0f, 0.5f, 0.01f,
                    0.0f, 0.0f, 0.0f);
            ByteBuffer edged = render(device, target, pass, source.id(),
                    depthTexture, List.of(edge));
            assertRed(edged, 1, 16, 8);
            assertTrue(Math.abs(red(edged, 8, 16) - 64) > 2,
                    "a distortion that stays inside the image must still apply");
        });
    }

    @Test
    void hazeIsContinuousAcrossPixelsAndTime() {
        withFixture((device, target, pass, source, depthTexture) -> {
            ByteBuffer calm = render(device, target, pass, source.id(), depthTexture, List.of());
            ByteBuffer haze = render(device, target, pass, source.id(), depthTexture, List.of(haze(0.2f)));
            ByteBuffer next = render(device, target, pass, source.id(), depthTexture, List.of(haze(0.21f)));
            int warpedPixels = 0;
            // Stay clear of the foreground and the viewport boundary.
            for (int y = 4; y < SIZE - 4; y++) {
                for (int x = 20; x < SIZE - 4; x++) {
                    int delta = red(haze, x, y) - red(calm, x, y);
                    int left = red(haze, x - 1, y) - red(calm, x - 1, y);
                    // A cubic interpolation across 12 cells per screen height
                    // has slope <= 1.5 per cell, times at most 2 px of offset
                    // range and 8 red levels per texel, plus quantization.
                    assertTrue(Math.abs(delta - left) <= 10,
                            "haze jumps between adjacent pixels at " + x + "," + y);
                    assertEquals(red(haze, x, y), red(next, x, y), 1,
                            "a small phase advance must not flicker at " + x + "," + y);
                    if (delta != 0) warpedPixels++;
                }
            }
            assertTrue(warpedPixels > 0, "haze must still warp the background without authored drift");
        });
    }

    private static ScreenDistortion haze(float phase) {
        return new ScreenDistortion(1L, 0, new Vector2f(0.5f), new Vector2f(4f),
                new Vector2f(), 1f, 0.8f, 0.01f, 0f, 1f, phase);
    }

    private static void withFixture(Fixture fixture) {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Screen Distortion Pass GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            ScreenDistortionSettings settings = ScreenDistortionSettings.builder()
                    .enabled(true)
                    .maximumOffsetUv(0.0625f)
                    .maximumContributions(4)
                    .edgeFeatherUv(0.0f)
                    .source(List::of)
                    .build();
            try (Framebuffer target = Framebuffer.singleSampled(SIZE, SIZE);
                 Texture2D source = gradientTexture();
                 ScreenDistortionPass pass = new ScreenDistortionPass(settings)) {
                int depthTexture = depthTexture();
                try {
                    fixture.run(new GlRenderDevice(), target, pass, source, depthTexture);
                    assertEquals(GL_NO_ERROR, glGetError());
                } finally {
                    glDeleteTextures(depthTexture);
                }
            }
        }
    }

    @FunctionalInterface
    private interface Fixture {
        void run(GlRenderDevice device, Framebuffer target, ScreenDistortionPass pass,
                 Texture2D source, int depthTexture);
    }

    private static ByteBuffer render(GlRenderDevice device, Framebuffer target,
                                     ScreenDistortionPass pass, int colorTexture,
                                     int depthTexture, List<ScreenDistortion> contributions) {
        target.bind();
        var commands = device.createCommandBuffer();
        pass.recordIntoCurrentTarget(commands, colorTexture, depthTexture, contributions,
                SIZE, SIZE);
        device.execute(commands);
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static ScreenDistortion contribution(long effectId, int index,
                                                 float offsetX, float effectDepth) {
        return new ScreenDistortion(effectId, index, new Vector2f(0.5f, 0.5f),
                new Vector2f(4.0f, 4.0f), new Vector2f(offsetX, 0.0f), 1.0f,
                effectDepth, 0.01f, 0.0f, 0.0f, 0.0f);
    }

    private static Texture2D gradientTexture() {
        byte[] pixels = new byte[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int offset = (y * SIZE + x) * 4;
                pixels[offset] = (byte) (x * 8);
                pixels[offset + 1] = (byte) 64;
                pixels[offset + 2] = (byte) 128;
                pixels[offset + 3] = (byte) 255;
            }
        }
        return Texture2D.fromRgba8(SIZE, SIZE, pixels, TextureColorSpace.LINEAR);
    }

    /** Left half is in front of the effect plane, right half is behind it. */
    private static int depthTexture() {
        int texture = GL45C.glCreateTextures(GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, GL_DEPTH_COMPONENT24, SIZE, SIZE);
        GL45C.glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        GL45C.glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GL45C.glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        FloatBuffer depths = BufferUtils.createFloatBuffer(SIZE * SIZE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                depths.put(x < SIZE / 2 ? 0.5f : 0.95f);
            }
        }
        depths.flip();
        GL45C.glTextureSubImage2D(texture, 0, 0, 0, SIZE, SIZE,
                GL_DEPTH_COMPONENT, GL_FLOAT, depths);
        return texture;
    }

    private static int red(ByteBuffer pixels, int x, int y) {
        return Byte.toUnsignedInt(pixels.get((y * SIZE + x) * 4));
    }

    private static void assertRed(ByteBuffer pixels, int x, int y, int expected) {
        assertEquals(expected, red(pixels, x, y),
                "unexpected red at " + x + "," + y);
    }

    private static int changedPixels(ByteBuffer left, ByteBuffer right) {
        int changed = 0;
        for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
            int offset = pixel * 4;
            int delta = Math.abs(Byte.toUnsignedInt(left.get(offset))
                    - Byte.toUnsignedInt(right.get(offset)))
                    + Math.abs(Byte.toUnsignedInt(left.get(offset + 1))
                    - Byte.toUnsignedInt(right.get(offset + 1)))
                    + Math.abs(Byte.toUnsignedInt(left.get(offset + 2))
                    - Byte.toUnsignedInt(right.get(offset + 2)));
            if (delta > 8) changed++;
        }
        return changed;
    }
}
