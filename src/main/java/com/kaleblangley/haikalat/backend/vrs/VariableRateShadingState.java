package com.kaleblangley.haikalat.backend.vrs;

import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_IMAGE_BINDING_NV;
import static org.lwjgl.opengl.NVShadingRateImage.glBindShadingRateImageNV;
import static org.lwjgl.opengl.NVShadingRateImage.glGetShadingRateImagePaletteNV;
import static org.lwjgl.opengl.NVShadingRateImage.glShadingRateImageBarrierNV;
import static org.lwjgl.opengl.NVShadingRateImage.glShadingRateImagePaletteNV;

/**
 * Minimal host-state adapter for {@code GL_NV_shading_rate_image}.
 *
 * <p>The adapter owns no texture.  {@link #begin} captures the previously bound
 * shading-rate image and the palette entries it is about to replace, applies
 * the caller's image and palette, and returns a snapshot whose
 * {@link Snapshot#restore()} puts the exact host state back.  Because both the
 * rate image binding and the palette are context (or draw-framebuffer) state
 * that a pass cannot inspect safely at command-record time, the capture must
 * happen while commands execute; {@code CommandBuffer} therefore records a
 * begin/end pair that {@code CommandExecutor} maps onto this adapter.</p>
 *
 * <p>Only viewport 0 is touched.  The sample order is never modified.  After
 * binding the image the adapter calls
 * {@code glShadingRateImageBarrierNV(true)}: the NV raster path requires an
 * explicit ordering point even when the image was produced by ordinary texture
 * uploads.  The synchronizing form is used for the first correctness version;
 * the image is static after creation, so the wait normally has no outstanding
 * upload to drain.</p>
 */
public final class VariableRateShadingState {
    private static final int VIEWPORT = 0;

    private VariableRateShadingState() {
    }

    /** @return the currently bound shading-rate image name for viewport 0's draw framebuffer */
    public static int boundImage() {
        requireEntryPoints();
        return glGetInteger(GL_SHADING_RATE_IMAGE_BINDING_NV);
    }

    /**
     * Reads one section of the viewport-0 palette.
     *
     * <p>The NV query takes a single palette entry per call, so reading a range
     * must issue one call per entry; passing a larger buffer would only fill the
     * first element and leave the rest uninitialised.</p>
     *
     * @param first first palette index
     * @param count number of entries to read
     * @return the palette entries
     */
    public static int[] palette(int first, int count) {
        requireEntryPoints();
        if (first < 0 || count < 0) {
            throw new IllegalArgumentException("palette range must be non-negative");
        }
        if (count == 0) return new int[0];
        return readPalette(first, count);
    }

    private static int[] readPalette(int first, int count) {
        int[] result = new int[count];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.mallocInt(1);
            for (int index = 0; index < count; index++) {
                value.clear();
                glGetShadingRateImagePaletteNV(VIEWPORT, first + index, value);
                result[index] = value.get(0);
            }
        }
        return result;
    }

    /**
     * Applies a shading-rate image and palette while remembering the exact state
     * that must be restored.
     *
     * @param textureId {@code R8UI} shading-rate image texture name
     * @param palette   palette entries starting at index zero
     * @return a snapshot that must be restored after the shaded draw
     */
    public static Snapshot begin(int textureId, int[] palette) {
        requireEntryPoints();
        if (textureId <= 0) {
            throw new IllegalArgumentException("shading-rate image texture name must be positive");
        }
        if (palette == null || palette.length < 2) {
            throw new IllegalArgumentException(
                    "a shading-rate palette needs at least a fine and a coarse entry");
        }
        for (int rate : palette) {
            if (!knownRate(rate)) {
                throw new IllegalArgumentException(
                        "unsupported NV shading-rate palette value: " + rate);
            }
        }
        int previousImage = glGetInteger(GL_SHADING_RATE_IMAGE_BINDING_NV);
        int[] previousPalette = sanitizePaletteForRestore(readPalette(0, palette.length));
        glBindShadingRateImageNV(textureId);
        writePalette(palette);
        // TexSubImage uploads are already ordered before the draw, but the
        // shading-rate image is read through a raster path that NV requires
        // applications to order explicitly. Use the synchronizing form for the
        // first correctness version; the image is static after creation, so the
        // wait normally has no outstanding upload to drain.
        glShadingRateImageBarrierNV(true);
        return new Snapshot(previousImage, previousPalette);
    }

    private static void writePalette(int[] palette) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer values = stack.mallocInt(palette.length);
            values.put(palette).flip();
            glShadingRateImagePaletteNV(VIEWPORT, 0, values);
        }
    }

    private static boolean knownRate(int value) {
        for (VariableRateShadingRate rate : VariableRateShadingRate.values()) {
            if (rate.glValue() == value) return true;
        }
        return false;
    }

    /**
     * Replaces uninitialised/invalid captured palette entries with the defined
     * full-rate entry.
     *
     * <p>Some drivers return zero for palette entries the application has never
     * set. Zero is not a valid shading-rate enumerant, so writing it back during
     * restore generates {@code GL_INVALID_ENUM}. The failure-path and normal-path
     * restore must never introduce an illegal enum, therefore an unknown entry is
     * restored to the defined one-invocation-per-pixel default instead of the raw
     * captured value.</p>
     *
     * @param captured palette entries returned by the driver
     * @return a copy safe to pass to {@code glShadingRateImagePaletteNV}
     */
    static int[] sanitizePaletteForRestore(int[] captured) {
        int[] result = captured.clone();
        int fullRate = VariableRateShadingRate.ONE_BY_ONE.glValue();
        for (int index = 0; index < result.length; index++) {
            if (!knownRate(result[index])) {
                result[index] = fullRate;
            }
        }
        return result;
    }

    /**
     * Cheap per-command guard: the full extension enumeration only runs when a
     * pipeline generation is assembled, while every begin/end scope uses the
     * already-resolved LWJGL entry points.
     */
    private static void requireEntryPoints() {
        if (!com.kaleblangley.haikalat.backend.GlDebug.hasCurrentContext()) {
            throw new IllegalStateException(
                    "GL_NV_shading_rate_image requires a current OpenGL context");
        }
        org.lwjgl.opengl.GLCapabilities capabilities =
                org.lwjgl.opengl.GL.getCapabilities();
        if (!capabilities.GL_NV_shading_rate_image
                || capabilities.glBindShadingRateImageNV == 0L
                || capabilities.glShadingRateImagePaletteNV == 0L
                || capabilities.glGetShadingRateImagePaletteNV == 0L
                || capabilities.glShadingRateImageBarrierNV == 0L) {
            throw new IllegalStateException(
                    "GL_NV_shading_rate_image entry points are unavailable in the current context");
        }
    }

    /**
     * Host state captured before a variable-rate draw.
     *
     * <p>{@link #restore()} is idempotent and must run on the same context that
     * executed {@link #begin}.  The executor restores outstanding snapshots on
     * every failure path so a failed frame cannot leak a rate image or palette
     * into the next frame.</p>
     */
    public static final class Snapshot implements AutoCloseable {
        private final int previousImage;
        private final int[] previousPalette;
        private boolean restored;

        private Snapshot(int previousImage, int[] previousPalette) {
            this.previousImage = previousImage;
            this.previousPalette = previousPalette;
        }

        /** @return the shading-rate image bound before this scope */
        public int previousImage() {
            return previousImage;
        }

        /** @return the palette entries replaced by this scope */
        public int[] previousPalette() {
            return previousPalette.clone();
        }

        /** @return whether the previous state has been restored */
        public boolean isRestored() {
            return restored;
        }

        /** Restores the exact binding and palette captured by {@link #begin}. */
        public void restore() {
            if (restored) return;
            glBindShadingRateImageNV(previousImage);
            writePalette(previousPalette);
            restored = true;
        }
        @Override
        public void close() {
            restore();
        }
    }
}
