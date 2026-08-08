package com.kaleblangley.haikalat.subsystems.text;

import java.util.List;
import java.util.Objects;

/** 不可变的单行文本布局结果。 */
public record TextLine(
        int startUtf16,
        int endUtf16,
        float baselineY,
        float width,
        float ascent,
        float descent,
        List<PositionedGlyph> glyphs) {

    public TextLine {
        if (startUtf16 < 0 || endUtf16 < startUtf16) {
            throw new IllegalArgumentException("Invalid line UTF-16 range");
        }
        requireFinite(baselineY, "baselineY");
        requireFiniteNonNegative(width, "width");
        requireFiniteNonNegative(ascent, "ascent");
        requireFiniteNonNegative(descent, "descent");
        glyphs = List.copyOf(Objects.requireNonNull(glyphs, "glyphs"));
        for (PositionedGlyph glyph : glyphs) {
            Objects.requireNonNull(glyph, "glyph");
            if (glyph.clusterStartUtf16() < startUtf16 || glyph.clusterEndUtf16() > endUtf16) {
                throw new IllegalArgumentException("Positioned glyph lies outside line source range");
            }
        }
    }

    /** 返回行高。 */
    public float height() {
        return ascent + descent;
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
