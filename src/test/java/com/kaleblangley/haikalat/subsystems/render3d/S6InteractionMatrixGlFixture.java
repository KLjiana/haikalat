package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * Shared fixture for the S6 representative interaction pixel matrix.
 *
 * <p>This class is not a test. It owns the fixed 96x96 hidden window, the fixed
 * camera/exposure/time constants and the readback predicates. Every case in
 * {@link S6InteractionMatrixGlTest} uses these helpers so the matrix is
 * executed with one camera, one time step, one exposure and one readback
 * definition. The readbacks are diagnostic evidence only; production never
 * reads back, and no performance conclusion is drawn from them.</p>
 */
final class S6InteractionMatrixGlFixture {
    /** Representative matrix extent: small enough to stay bounded, large enough for pixels. */
    static final int SIZE = 96;
    /** Deterministic frame delta used by every case. */
    static final float DELTA_SECONDS = 1.0f / 60.0f;
    /** Fixed manual exposure; no automatic exposure participates in the matrix. */
    static final float EXPOSURE = 1.25f;
    /** Upper bound that keeps a broken pass from being read as "finite but huge". */
    static final float MAXIMUM_ABSOLUTE_COMPONENT = 1.0e6f;

    private S6InteractionMatrixGlFixture() {
    }

    static GlfwWindow window(String title) {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE).visible(false)
                .title(title).build();
    }

    /** HDR + ACES + fixed manual exposure with bloom disabled. */
    static RenderSettings hdr(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposure(EXPOSURE)
                .exposureMode(ExposureMode.MANUAL)
                .bloomSettings(BloomSettings.disabled())
                .vsync(false)
                .build();
    }

    /** Same fixed HDR settings with bloom explicitly enabled. */
    static RenderSettings hdrWithBloom(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposure(EXPOSURE)
                .exposureMode(ExposureMode.MANUAL)
                .bloomSettings(BloomSettings.builder().enabled(true).build())
                .vsync(false)
                .build();
    }

    static void execute(RenderPipeline pipeline, GlRenderDevice device, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            pipeline.execute(device, DELTA_SECONDS);
        }
    }

    /** Reads the final linear HDR image and verifies its extent matches the graph. */
    static float[] capture(RenderPipeline pipeline, String label) {
        int width = pipeline.graph().width();
        int height = pipeline.graph().height();
        float[] pixels = pipeline.captureLinearHdrRgbaFloat();
        assertEquals(width * height * 4, pixels.length,
                label + " readback must cover the graph extent " + width + "x" + height);
        return pixels;
    }

    /**
     * Requires every component to be finite and bounded, and requires the image
     * to be visibly non-uniform (not a uniform clear).
     */
    static void requireCleanFrame(float[] rgba, String label) {
        int pixels = rgba.length / 4;
        float minimumLuminance = Float.POSITIVE_INFINITY;
        float maximumLuminance = Float.NEGATIVE_INFINITY;
        Set<Integer> distinct = new HashSet<>();
        for (int pixel = 0; pixel < pixels; pixel++) {
            int offset = pixel * 4;
            for (int component = 0; component < 4; component++) {
                float value = rgba[offset + component];
                assertTrue(Float.isFinite(value),
                        label + " component " + component + " at pixel " + pixel
                                + " must be finite, was " + value);
                assertTrue(Math.abs(value) <= MAXIMUM_ABSOLUTE_COMPONENT,
                        label + " component " + component + " at pixel " + pixel
                                + " must stay bounded, was " + value);
            }
            float luminance = 0.2126f * rgba[offset] + 0.7152f * rgba[offset + 1]
                    + 0.0722f * rgba[offset + 2];
            minimumLuminance = Math.min(minimumLuminance, luminance);
            maximumLuminance = Math.max(maximumLuminance, luminance);
            distinct.add((Math.round(rgba[offset] * 512.0f) & 0x3FF)
                    | ((Math.round(rgba[offset + 1] * 512.0f) & 0x3FF) << 10)
                    | ((Math.round(rgba[offset + 2] * 512.0f) & 0x3FF) << 20));
        }
        assertTrue(distinct.size() >= 2,
                label + " must not be a uniform clear; only " + distinct.size()
                        + " distinct quantized colours");
        assertTrue(maximumLuminance - minimumLuminance > 1.0e-3f,
                label + " must carry visible contrast, range="
                        + (maximumLuminance - minimumLuminance));
    }

    /** Counts pixels whose RGBA bits differ. */
    static int differingPixels(float[] left, float[] right) {
        assertEquals(left.length, right.length, "images must share one extent");
        int changed = 0;
        for (int offset = 0; offset < left.length; offset += 4) {
            for (int component = 0; component < 4; component++) {
                if (Float.floatToIntBits(left[offset + component])
                        != Float.floatToIntBits(right[offset + component])) {
                    changed++;
                    break;
                }
            }
        }
        return changed;
    }

    static void requireSameImage(float[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message + ": extent changed");
        for (int offset = 0; offset < expected.length; offset += 4) {
            for (int component = 0; component < 4; component++) {
                if (Float.floatToIntBits(expected[offset + component])
                        != Float.floatToIntBits(actual[offset + component])) {
                    throw new AssertionError(message + ": pixel " + (offset / 4)
                            + " component " + component + " changed from "
                            + expected[offset + component] + " to "
                            + actual[offset + component]);
                }
            }
        }
    }

    static int requireDifferentImage(float[] left, float[] right, String message) {
        int changed = differingPixels(left, right);
        assertTrue(changed > 0, message + ": the two captures are byte-identical");
        return changed;
    }

    /** Emits one stable diagnostic row for the executed matrix report. */
    static void report(String group, String fact, int count) {
        System.out.println("S6-MATRIX-DIAG group=" + group + " fact=" + fact
                + " count=" + count);
    }

    /**
     * Reads the presented LDR back buffer. SMAA is an AA mode applied after
     * tone mapping, so its pixels are only visible here, not in the linear HDR
     * target.
     */
    static byte[] capturePresented(int width, int height, String label) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        byte[] result = new byte[width * height * 4];
        buffer.get(result);
        Set<Integer> distinct = new HashSet<>();
        for (int offset = 0; offset < result.length; offset += 4) {
            distinct.add((Byte.toUnsignedInt(result[offset]) << 24)
                    | (Byte.toUnsignedInt(result[offset + 1]) << 16)
                    | (Byte.toUnsignedInt(result[offset + 2]) << 8)
                    | Byte.toUnsignedInt(result[offset + 3]));
        }
        assertTrue(distinct.size() >= 2,
                label + " must not be a uniform presented clear; only " + distinct.size()
                        + " distinct colours");
        return result;
    }

    static void requireSameBytes(byte[] expected, byte[] actual, String message) {
        assertEquals(expected.length, actual.length, message + ": extent changed");
        for (int index = 0; index < expected.length; index++) {
            if (expected[index] != actual[index]) {
                throw new AssertionError(message + ": byte " + index + " changed from "
                        + Byte.toUnsignedInt(expected[index]) + " to "
                        + Byte.toUnsignedInt(actual[index]));
            }
        }
    }

    static void requireDifferentBytes(byte[] left, byte[] right, String message) {
        assertEquals(left.length, right.length, message + ": extent changed");
        int changed = 0;
        for (int index = 0; index < left.length; index++) {
            if (left[index] != right[index]) changed++;
        }
        assertTrue(changed > 0, message + ": the two presented captures are byte-identical");
        report("camera", "smaaPresentedChangedBytes", changed);
    }

    static float luminance(float[] rgba, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * rgba[offset] + 0.7152f * rgba[offset + 1]
                + 0.0722f * rgba[offset + 2];
    }
}
