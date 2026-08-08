package com.kaleblangley.haikalat.subsystems.ui.style;

import com.kaleblangley.haikalat.subsystems.ui.text.TextEffect;

import java.util.Objects;

/** 主题、伪状态与 inline override 解析后的不可变视觉样式。 */
public record ComputedStyle(
        UiColor background,
        UiColor foreground,
        UiColor borderColor,
        float borderWidth,
        float radius,
        float opacity,
        float fontSize,
        String fontFamily,
        TextEffect textEffect
) {
    /** Source-compatible constructor for callers that do not configure text effects. */
    public ComputedStyle(UiColor background, UiColor foreground, UiColor borderColor,
                         float borderWidth, float radius, float opacity,
                         float fontSize, String fontFamily) {
        this(background, foreground, borderColor, borderWidth, radius, opacity,
                fontSize, fontFamily, TextEffect.none());
    }

    public ComputedStyle {
        if (background == null || foreground == null || borderColor == null) {
            throw new NullPointerException("computed color");
        }
        if (!Float.isFinite(borderWidth) || borderWidth < 0.0f
                || !Float.isFinite(radius) || radius < 0.0f
                || !Float.isFinite(opacity) || opacity < 0.0f || opacity > 1.0f
                || !Float.isFinite(fontSize) || fontSize <= 0.0f) {
            throw new IllegalArgumentException("computed style metrics are invalid");
        }
        if (fontFamily == null || fontFamily.isBlank()) {
            throw new IllegalArgumentException("font family must not be blank");
        }
        textEffect = Objects.requireNonNull(textEffect, "textEffect");
    }
}
