package com.kaleblangley.haikalat.subsystems.text;

/**
 * HarfBuzz shaping 后的单个 glyph 数据。
 *
 * <p>cluster 范围采用相对于所属 {@link TextRun#text()} 的 UTF-16 半开范围。</p>
 */
public record ShapedGlyph(
        int glyphId,
        int clusterStartUtf16,
        int clusterEndUtf16,
        float advanceX,
        float advanceY,
        float offsetX,
        float offsetY) {

    public ShapedGlyph {
        if (glyphId < 0) {
            throw new IllegalArgumentException("glyphId must be non-negative");
        }
        if (clusterStartUtf16 < 0 || clusterEndUtf16 <= clusterStartUtf16) {
            throw new IllegalArgumentException("Glyph cluster must be a non-empty UTF-16 range");
        }
        requireFinite(advanceX, "advanceX");
        requireFinite(advanceY, "advanceY");
        requireFinite(offsetX, "offsetX");
        requireFinite(offsetY, "offsetY");
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
