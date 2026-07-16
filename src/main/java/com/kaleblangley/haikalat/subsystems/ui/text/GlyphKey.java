package com.kaleblangley.haikalat.subsystems.ui.text;

import java.util.Objects;

/**
 * glyph 光栅与 atlas 查找的完整稳定键。
 */
public record GlyphKey(
        FontFaceId faceId,
        int glyphId,
        int ppem,
        GlyphHinting hinting,
        GlyphRasterMode rasterMode) {

    public GlyphKey {
        Objects.requireNonNull(faceId, "faceId");
        if (glyphId < 0) {
            throw new IllegalArgumentException("glyphId must be non-negative");
        }
        if (ppem <= 0) {
            throw new IllegalArgumentException("ppem must be positive");
        }
        Objects.requireNonNull(hinting, "hinting");
        Objects.requireNonNull(rasterMode, "rasterMode");
    }

    /** 使用正常 hinting 和灰度 coverage 的便捷构造。 */
    public GlyphKey(FontFaceId faceId, int glyphId, int ppem) {
        this(faceId, glyphId, ppem, GlyphHinting.NORMAL, GlyphRasterMode.GRAYSCALE);
    }
}
