package com.kaleblangley.haikalat.subsystems.postprocess.vrs;

import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingRate;

import java.util.Objects;

/**
 * Pure tile map for a selective shading-rate image.
 *
 * <p>The implementation's rate image has one texel per {@code texelWidth} x
 * {@code texelHeight} pixel tile.  This plan fills a centred rectangular block
 * of tiles with palette index {@code 0} (the fine rate) and the remaining tiles
 * with index {@code 1} (the coarse rate).  The mapping is computed on the CPU
 * once per extent and uploaded when the pass or a resize candidate is created,
 * so no per-frame compute dispatch or read-back exists.</p>
 *
 * <p>The fine block always contains at least one tile on each axis and the
 * coarse area always contains at least one tile, otherwise the request would
 * not be selective and the plan is rejected.  A target smaller than two rate
 * image texels on both axes therefore cannot host a selective request.</p>
 */
final class VariableRateShadingPlan {
    /** Defensive bound: an 8K frame with 16x16 texels needs 540x270 tiles. */
    static final int MAX_TILES = 1_048_576;
    static final byte FINE_PALETTE_INDEX = 0;
    static final byte COARSE_PALETTE_INDEX = 1;

    private final int targetWidth;
    private final int targetHeight;
    private final int texelWidth;
    private final int texelHeight;
    private final int tilesX;
    private final int tilesY;
    private final int fineTileStartX;
    private final int fineTileEndX;
    private final int fineTileStartY;
    private final int fineTileEndY;
    private final int[] palette;
    private final byte[] tileIndices;

    private VariableRateShadingPlan(int targetWidth, int targetHeight, int texelWidth,
                                    int texelHeight, int tilesX, int tilesY,
                                    int fineTileStartX, int fineTileEndX,
                                    int fineTileStartY, int fineTileEndY,
                                    int[] palette, byte[] tileIndices) {
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.texelWidth = texelWidth;
        this.texelHeight = texelHeight;
        this.tilesX = tilesX;
        this.tilesY = tilesY;
        this.fineTileStartX = fineTileStartX;
        this.fineTileEndX = fineTileEndX;
        this.fineTileStartY = fineTileStartY;
        this.fineTileEndY = fineTileEndY;
        this.palette = palette;
        this.tileIndices = tileIndices;
    }

    /**
     * Builds a selective tile map.
     *
     * @param width        consumer framebuffer width in pixels
     * @param height       consumer framebuffer height in pixels
     * @param settings     validated request
     * @param texelWidth   queried rate-image texel width in pixels
     * @param texelHeight  queried rate-image texel height in pixels
     * @param paletteSize  queried palette capacity
     * @return an immutable plan
     */
    static VariableRateShadingPlan create(int width, int height,
                                          VariableRateShadingSettings settings,
                                          int texelWidth, int texelHeight, int paletteSize) {
        if (width <= 0 || height <= 0 || texelWidth <= 0 || texelHeight <= 0) {
            throw new IllegalArgumentException("shading-rate extents must be positive");
        }
        if (paletteSize < 2) {
            throw new IllegalArgumentException(
                    "the shading-rate palette needs at least two entries");
        }
        Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("a plan requires an active shading-rate request");
        }        int tilesX = ceilDiv(width, texelWidth);
        int tilesY = ceilDiv(height, texelHeight);
        long tileCount = (long) tilesX * tilesY;
        if (tileCount > MAX_TILES) {
            throw new IllegalArgumentException("shading-rate tile map is too large: " + tileCount);
        }

        int fineTilesX = clamp(Math.round(tilesX * settings.fineRegionFraction()), 1, tilesX);
        int fineTilesY = clamp(Math.round(tilesY * settings.fineRegionFraction()), 1, tilesY);
        int fineStartX = (tilesX - fineTilesX) / 2;
        int fineEndX = fineStartX + fineTilesX;
        int fineStartY = (tilesY - fineTilesY) / 2;
        int fineEndY = fineStartY + fineTilesY;

        byte[] indices = new byte[(int) tileCount];
        boolean hasFine = false;
        boolean hasCoarse = false;
        for (int tileY = 0; tileY < tilesY; tileY++) {
            boolean fineRow = tileY >= fineStartY && tileY < fineEndY;
            for (int tileX = 0; tileX < tilesX; tileX++) {
                boolean fine = fineRow && tileX >= fineStartX && tileX < fineEndX;
                byte index = fine ? FINE_PALETTE_INDEX : COARSE_PALETTE_INDEX;
                indices[tileY * tilesX + tileX] = index;
                hasFine |= fine;
                hasCoarse |= !fine;
            }
        }
        if (!hasFine || !hasCoarse) {
            throw new IllegalArgumentException("a " + width + "x" + height
                    + " target at " + texelWidth + "x" + texelHeight
                    + " texels cannot host a selective fine/coarse tile split");
        }
        int[] palette = new int[]{
                settings.fineRate().glValue(),
                settings.coarseRate().glValue()};
        return new VariableRateShadingPlan(width, height, texelWidth, texelHeight,
                tilesX, tilesY, fineStartX, fineEndX, fineStartY, fineEndY, palette, indices);
    }

    private static int ceilDiv(int value, int divisor) {
        return (int) (((long) value + divisor - 1L) / divisor);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    int targetWidth() {
        return targetWidth;
    }

    int targetHeight() {
        return targetHeight;
    }

    int texelWidth() {
        return texelWidth;
    }

    int texelHeight() {
        return texelHeight;
    }

    int tilesX() {
        return tilesX;
    }

    int tilesY() {
        return tilesY;
    }

    int fineTileStartX() {
        return fineTileStartX;
    }

    int fineTileEndX() {
        return fineTileEndX;
    }

    int fineTileStartY() {
        return fineTileStartY;
    }

    int fineTileEndY() {
        return fineTileEndY;
    }

    int[] palette() {
        return palette.clone();
    }

    byte tileIndex(int tileX, int tileY) {
        if (tileX < 0 || tileY < 0 || tileX >= tilesX || tileY >= tilesY) {
            throw new IllegalArgumentException("tile index outside the shading-rate plan");
        }
        return tileIndices[tileY * tilesX + tileX];
    }

    /** @return a copy of the row-major palette-index map */
    byte[] tileIndices() {
        return tileIndices.clone();
    }

    /** @return whether the map contains both a fine and a coarse tile */
    boolean selective() {
        boolean hasFine = false;
        boolean hasCoarse = false;
        for (byte index : tileIndices) {
            if (index == FINE_PALETTE_INDEX) hasFine = true;
            if (index == COARSE_PALETTE_INDEX) hasCoarse = true;
        }
        return hasFine && hasCoarse;
    }

    /** @return whether the given pixel column lies in the fine tile block */
    boolean finePixelColumn(int pixelX) {
        return pixelX >= 0 && pixelX < targetWidth
                && pixelX / texelWidth >= fineTileStartX && pixelX / texelWidth < fineTileEndX;
    }

    /** @return whether the given pixel row lies in the fine tile block */
    boolean finePixelRow(int pixelY) {
        return pixelY >= 0 && pixelY < targetHeight
                && pixelY / texelHeight >= fineTileStartY && pixelY / texelHeight < fineTileEndY;
    }

    /** @return whether the given pixel lies inside the full-rate region */
    boolean finePixel(int pixelX, int pixelY) {
        return finePixelColumn(pixelX) && finePixelRow(pixelY);
    }

    /** @return whether the requested coarse footprint is available on this palette size */
    static boolean supports(VariableRateShadingRate rate, int paletteSize) {
        return paletteSize >= 2 && rate != null && !rate.fullRate();
    }
}
