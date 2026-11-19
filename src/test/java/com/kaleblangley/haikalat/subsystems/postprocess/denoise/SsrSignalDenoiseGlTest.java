package com.kaleblangley.haikalat.subsystems.postprocess.denoise;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalDenoiseSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;

/**
 * Real-GL acceptance for the S7 / #34 reflection-signal chain.
 *
 * <p>The denoiser consumes the SSR <em>contribution</em> in RGB and the usable
 * hit confidence in alpha, then a separate composite applies
 * {@code base + contribution}. This case drives the real
 * {@link TemporalDenoisePass} with a moving reflection patch in the frozen
 * velocity/previous-depth reprojection mode and a uniform base colour:</p>
 *
 * <ul>
 *   <li>the patch moves one pixel right; its old column becomes
 *       zero-confidence and must be written as an exact zero contribution, so
 *       a 0.9-weight history cannot leave a ghost (the retired behaviour would
 *       retain about 2.7 of the previous 3.0);</li>
 *   <li>the moved column reprojects its accepted history through the shared
 *       velocity and stays at the signal value, proving the motion pair is
 *       consumed;</li>
 *   <li>the composite reproduces the base colour bit-for-bit wherever the
 *       confidence is zero and adds the contribution elsewhere.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SsrSignalDenoiseGlTest {
    private static final int SIZE = 24;
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(60.0);
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(
            FIELD_OF_VIEW, 1.0f, 0.1f, 100.0f);
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f(PROJECTION).invert();
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final float VIEW_DEPTH = 2.0f;
    private static final float DEVICE_DEPTH = deviceDepth(VIEW_DEPTH);
    private static final float CONTRIBUTION = 3.0f;
    private static final float BASE_RED = 0.5f;
    private static final float BASE_GREEN = 0.25f;
    private static final float BASE_BLUE = 0.125f;
    private static final int PATCH_ROW_START = 8;
    private static final int PATCH_ROW_END = 11;

    @Test
    void revealedReflectionWritesExactZeroAndCompositesBackToTheBase() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(64, 64)
                .title("SSR Signal Denoise GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (TemporalDenoisePass pass = new TemporalDenoisePass(
                    TemporalDenoiseSettings.defaults(), SIZE, SIZE);
                 Texture2D depth = floatTexture(1, constantDepth());
                 Texture2D normal = floatTexture(4, constantNormal());
                 Texture2D previousDepth = floatTexture(1, constantPreviousDepth());
                 Texture2D velocity = floatTexture(4, constantVelocity());
                 Texture2D frame0 = floatTexture(4, patch(4, 7));
                 Texture2D frame1 = floatTexture(4, patch(5, 8));
                 Texture2D base = floatTexture(4, baseColour());
                 Framebuffer composite = Framebuffer.mrt(SIZE, SIZE, GL_RGBA32F)) {
                runFrame(pass, device, frame0, depth, normal, velocity, previousDepth);
                float[] first = readAttachment(pass.readFramebuffer(), 0);
                for (int y = PATCH_ROW_START; y <= PATCH_ROW_END; y++) {
                    assertEquals(CONTRIBUTION, first[offset(4, y)], 1.0e-6f,
                            "frame 0 must expose the initial reflection patch");
                }

                runFrame(pass, device, frame1, depth, normal, velocity, previousDepth);
                float[] second = readAttachment(pass.readFramebuffer(), 0);
                for (int y = PATCH_ROW_START; y <= PATCH_ROW_END; y++) {
                    assertEquals(0.0f, second[offset(4, y)], 0.0f,
                            "the revealed (now zero-confidence) column must be exactly zero "
                                    + "and must not retain ghost history at row " + y);
                    assertEquals(0.0f, second[offset(4, y) + 3], 0.0f);
                    assertTrue(second[offset(8, y)] > CONTRIBUTION * 0.97f,
                            "the moved column must reproject its history and keep the signal: "
                                    + second[offset(8, y)]);
                    assertEquals(1.0f, second[offset(8, y) + 3], 1.0e-3f,
                            "the kept signal must keep its confidence");
                }
                assertEquals(0.0f, second[offset(2, 2)], 0.0f,
                        "an always-zero-confidence pixel must stay exactly zero");

                composite.bind();
                glViewport(0, 0, SIZE, SIZE);
                CommandBuffer commands = device.createCommandBuffer();
                pass.recordComposite(commands, base.id(), pass.readColorTexture(),
                        SIZE, SIZE);
                device.execute(commands);
                composite.bind();
                glViewport(0, 0, SIZE, SIZE);
                float[] composed = readAttachment(composite, 0);
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        int offset = offset(x, y);
                        float expectedRed = BASE_RED + second[offset];
                        float expectedGreen = BASE_GREEN + second[offset + 1];
                        float expectedBlue = BASE_BLUE + second[offset + 2];
                        assertEquals(expectedRed, composed[offset], 1.0e-5f,
                                "composite must be base + contribution at " + x + "," + y);
                        assertEquals(expectedGreen, composed[offset + 1], 1.0e-5f);
                        assertEquals(expectedBlue, composed[offset + 2], 1.0e-5f);
                        if (second[offset + 3] == 0.0f) {
                            assertEquals(BASE_RED, composed[offset], 0.0f,
                                    "a zero-confidence pixel must be the byte-exact base at "
                                            + x + "," + y);
                            assertEquals(BASE_GREEN, composed[offset + 1], 0.0f);
                            assertEquals(BASE_BLUE, composed[offset + 2], 0.0f);
                        }
                    }
                }
                GlDebug.checkError("SSR signal denoise/composite");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static void runFrame(TemporalDenoisePass pass, GlRenderDevice device,
                                 Texture2D noisy, Texture2D depth, Texture2D normal,
                                 Texture2D velocity, Texture2D previousDepth) {
        pass.beginFrame();
        CommandBuffer commands = device.createCommandBuffer();
        pass.record(commands, new TemporalDenoiseInputs(
                noisy.id(), depth.id(), normal.id(), velocity.id(), previousDepth.id(),
                INVERSE_PROJECTION, IDENTITY, PROJECTION));
        device.execute(commands);
        pass.frameSucceeded();
    }

    private static float[] patch(int startX, int endX) {
        float[] colour = new float[SIZE * SIZE * 4];
        for (int y = PATCH_ROW_START; y <= PATCH_ROW_END; y++) {
            for (int x = startX; x <= endX; x++) {
                int offset = offset(x, y);
                colour[offset] = CONTRIBUTION;
                colour[offset + 1] = CONTRIBUTION;
                colour[offset + 2] = CONTRIBUTION;
                colour[offset + 3] = 1.0f;
            }
        }
        return colour;
    }

    private static float[] baseColour() {
        float[] colour = new float[SIZE * SIZE * 4];
        for (int index = 0; index < SIZE * SIZE; index++) {
            colour[index * 4] = BASE_RED;
            colour[index * 4 + 1] = BASE_GREEN;
            colour[index * 4 + 2] = BASE_BLUE;
            colour[index * 4 + 3] = 1.0f;
        }
        return colour;
    }

    private static float[] constantDepth() {
        float[] depth = new float[SIZE * SIZE];
        Arrays.fill(depth, DEVICE_DEPTH);
        return depth;
    }

    private static float[] constantPreviousDepth() {
        float[] depth = new float[SIZE * SIZE];
        Arrays.fill(depth, VIEW_DEPTH);
        return depth;
    }

    /** Octahedral encoding of the constant (0, 0, 1) normal. */
    private static float[] constantNormal() {
        float[] normal = new float[SIZE * SIZE * 4];
        for (int index = 0; index < SIZE * SIZE; index++) {
            normal[index * 4 + 3] = 1.0f;
        }
        return normal;
    }

    /** The surface moved one pixel right last frame: previous = current - 1 px. */
    private static float[] constantVelocity() {
        float[] velocity = new float[SIZE * SIZE * 4];
        for (int index = 0; index < SIZE * SIZE; index++) {
            velocity[index * 4] = -1.0f / SIZE;
        }
        return velocity;
    }

    private static float deviceDepth(float viewDepth) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, -viewDepth, 1.0f).mul(PROJECTION);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static int offset(int x, int y) {
        return (y * SIZE + x) * 4;
    }

    private static Texture2D floatTexture(int channels, float[] data) {
        int format = channels == 1 ? GL_R32F : GL_RGBA32F;
        Texture2D texture = Texture2D.createEmpty(SIZE, SIZE, format);
        ByteBuffer upload = BufferUtils.createByteBuffer(
                SIZE * SIZE * channels * Float.BYTES);
        upload.asFloatBuffer().put(data);
        texture.uploadRegion(0, 0, SIZE, SIZE, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static float[] readAttachment(Framebuffer framebuffer, int attachment) {
        framebuffer.bind();
        glViewport(0, 0, framebuffer.width(), framebuffer.height());
        glReadBuffer(GL_COLOR_ATTACHMENT0 + attachment);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(
                framebuffer.width() * framebuffer.height() * 4);
        glReadPixels(0, 0, framebuffer.width(), framebuffer.height(), GL_RGBA, GL_FLOAT,
                pixels);
        float[] result = new float[framebuffer.width() * framebuffer.height() * 4];
        pixels.get(result);
        return result;
    }
}
