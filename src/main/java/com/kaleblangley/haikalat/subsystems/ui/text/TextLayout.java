package com.kaleblangley.haikalat.subsystems.ui.text;

import java.util.List;
import java.util.Objects;

/**
 * 已完成换行和定位、可跨线程发布的不可变文本布局快照。
 */
public record TextLayout(
        FontGeneration fontGeneration,
        String text,
        List<TextLine> lines,
        float width,
        float height) {

    public TextLayout {
        Objects.requireNonNull(fontGeneration, "fontGeneration");
        text = Objects.requireNonNull(text, "text");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        requireFiniteNonNegative(width, "width");
        requireFiniteNonNegative(height, "height");
        int previousEnd = 0;
        for (TextLine line : lines) {
            Objects.requireNonNull(line, "line");
            if (line.startUtf16() < previousEnd || line.endUtf16() > text.length()) {
                throw new IllegalArgumentException("Text lines overlap, are unordered, or exceed source text");
            }
            if (!isCodePointBoundary(text, line.startUtf16()) || !isCodePointBoundary(text, line.endUtf16())) {
                throw new IllegalArgumentException("Text line splits a supplementary code point");
            }
            previousEnd = line.endUtf16();
        }
    }

    /** 返回所有行的 glyph 总数。 */
    public int glyphCount() {
        int count = 0;
        for (TextLine line : lines) {
            count = Math.addExact(count, line.glyphs().size());
        }
        return count;
    }

    private static boolean isCodePointBoundary(String text, int offset) {
        return offset == 0 || offset == text.length()
                || !Character.isHighSurrogate(text.charAt(offset - 1))
                || !Character.isLowSurrogate(text.charAt(offset));
    }

    private static void requireFiniteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
