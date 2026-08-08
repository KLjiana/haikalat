package com.kaleblangley.haikalat.subsystems.text;

import java.util.List;
import java.util.Objects;

/**
 * 单个 face、ppem、方向和语言下不可变的 shaping 输出。
 */
public record TextRun(
        ShapingCacheKey key,
        List<ShapedGlyph> glyphs,
        float advanceX,
        float advanceY,
        float ascent,
        float descent) {

    public TextRun {
        key = Objects.requireNonNull(key, "key");
        glyphs = List.copyOf(Objects.requireNonNull(glyphs, "glyphs"));
        requireFinite(advanceX, "advanceX");
        requireFinite(advanceY, "advanceY");
        requireFiniteNonNegative(ascent, "ascent");
        requireFiniteNonNegative(descent, "descent");
        for (ShapedGlyph glyph : glyphs) {
            Objects.requireNonNull(glyph, "glyph");
            if (glyph.clusterEndUtf16() > key.text().length()) {
                throw new IllegalArgumentException("Glyph cluster exceeds shaping text length");
            }
            if (!isCodePointBoundary(key.text(), glyph.clusterStartUtf16())
                    || !isCodePointBoundary(key.text(), glyph.clusterEndUtf16())) {
                throw new IllegalArgumentException("Glyph cluster splits a supplementary code point");
            }
        }
    }

    /** 返回该 run 的原始文本切片。 */
    public String text() {
        return key.text();
    }

    /** 返回 shaping 使用的 face。 */
    public FontFaceId faceId() {
        return key.faceId();
    }

    /** 返回 shaping 使用的 ppem。 */
    public int ppem() {
        return key.ppem();
    }

    /** 返回 shaping 使用的字体 generation。 */
    public FontGeneration fontGeneration() {
        return key.fontGeneration();
    }

    private static boolean isCodePointBoundary(String text, int offset) {
        return offset == 0 || offset == text.length()
                || !Character.isHighSurrogate(text.charAt(offset - 1))
                || !Character.isLowSurrogate(text.charAt(offset));
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFiniteNonNegative(float value, String name) {
        requireFinite(value, name);
        if (value < 0.0f) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
