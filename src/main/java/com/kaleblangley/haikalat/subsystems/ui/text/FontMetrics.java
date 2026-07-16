package com.kaleblangley.haikalat.subsystems.ui.text;

/** 指定 ppem 下以逻辑像素表示的 FreeType 字体度量。 */
public record FontMetrics(int ppem, float ascent, float descent, float lineHeight, float maximumAdvance) {
    public FontMetrics {
        if (ppem <= 0) {
            throw new IllegalArgumentException("ppem must be positive");
        }
        requireNonNegative(ascent, "ascent");
        requireNonNegative(descent, "descent");
        requireNonNegative(lineHeight, "lineHeight");
        requireNonNegative(maximumAdvance, "maximumAdvance");
        if (lineHeight == 0.0f) {
            throw new IllegalArgumentException("lineHeight must be positive");
        }
    }

    private static void requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
