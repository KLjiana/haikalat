package com.kaleblangley.haikalat.backend.vrs;

import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_IMAGE_BINDING_NV;
import static org.lwjgl.opengl.NVShadingRateImage.glBindShadingRateImageNV;
import static org.lwjgl.opengl.NVShadingRateImage.glGetShadingRateImagePaletteNV;
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
 * <p>Only viewport 0 is touched.  The sample order is never modified, and no
 * implicit image barrier is issued: the rate image is produced by ordinary
 * texture uploads, which are already ordered before the draw.  A future
 * implementation that writes the image with image stores must call
 * {@code glShadingRateImageBarrierNV} before shading.</p>
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer values = stack.mallocInt(count);
            glGetShadingRateImagePaletteNV(VIEWPORT, first, values);
            int[] result = new int[count];
            for (int index = 0; index < count; index++) {
                result[index] = values.get(index);
            }
            return result;
        }
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
        int[] previousPalette = readPalette(0, palette.length);
        writePalette(palette);
        glBindShadingRateImageNV(textureId);
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
                || capabilities.glGetShadingRateImagePaletteNV == 0L) {
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
