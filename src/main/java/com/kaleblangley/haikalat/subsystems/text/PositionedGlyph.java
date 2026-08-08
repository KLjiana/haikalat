package com.kaleblangley.haikalat.subsystems.text;

import java.util.Objects;

/** 布局完成后可直接记录为 paint primitive 的 glyph 位置。 */
public record PositionedGlyph(
        FontFaceId faceId,
        int ppem,
        int glyphId,
        int clusterStartUtf16,
        int clusterEndUtf16,
        float x,
        float y,
        float advanceX,
        float advanceY,
        GlyphKey glyphKey) {

    /** 使用 UI 默认 hinting/raster 模式创建并缓存 atlas key。 */
    public PositionedGlyph(FontFaceId faceId, int ppem, int glyphId,
                           int clusterStartUtf16, int clusterEndUtf16,
                           float x, float y, float advanceX, float advanceY) {
        this(faceId, ppem, glyphId, clusterStartUtf16, clusterEndUtf16,
                x, y, advanceX, advanceY,
                new GlyphKey(faceId, glyphId, ppem,
                        GlyphHinting.NORMAL, GlyphRasterMode.GRAYSCALE));
    }

    public PositionedGlyph {
        Objects.requireNonNull(faceId, "faceId");
        Objects.requireNonNull(glyphKey, "glyphKey");
        if (ppem <= 0) {
            throw new IllegalArgumentException("ppem must be positive");
        }
        if (glyphId < 0) {
            throw new IllegalArgumentException("glyphId must be non-negative");
        }
        if (!glyphKey.faceId().equals(faceId) || glyphKey.glyphId() != glyphId
                || glyphKey.ppem() != ppem) {
            throw new IllegalArgumentException("glyphKey must describe the positioned glyph");
        }
        if (clusterStartUtf16 < 0 || clusterEndUtf16 <= clusterStartUtf16) {
            throw new IllegalArgumentException("Glyph cluster must be a non-empty UTF-16 range");
        }
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(advanceX, "advanceX");
        requireFinite(advanceY, "advanceY");
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
