package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;

/** Immutable fill/border/gradient data recorded with one SDF primitive. */
public record UiSdfDecoration(UiSdfPaint fill, UiSdfPaint border, float borderWidth,
                               UiGradient gradient, UiShadowStyle shadow) {
    public UiSdfDecoration {
        if (fill == null || border == null) throw new NullPointerException("SDF paint");
        if (!Float.isFinite(borderWidth) || borderWidth < 0.0f) {
            throw new IllegalArgumentException("borderWidth must be finite and non-negative");
        }
        if (shadow == null) throw new NullPointerException("shadow");
    }

    public static UiSdfDecoration solid(UiColor fill) {
        return new UiSdfDecoration(UiSdfPaint.solid(fill),
                UiSdfPaint.solid(UiColor.TRANSPARENT), 0.0f, null, UiShadowStyle.NONE);
    }

    public UiSdfDecoration withBorder(UiColor color, float width) {
        return new UiSdfDecoration(fill, UiSdfPaint.solid(color), width, gradient, shadow);
    }

    public UiSdfDecoration withGradient(UiGradient value) {
        return new UiSdfDecoration(fill, border, borderWidth, value, shadow);
    }
}
