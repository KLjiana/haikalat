package com.kaleblangley.haikalat.subsystems.ui.style;

/** 第一套游戏 UI 主题的强类型 token。 */
public record ThemeTokens(
        UiColor surface,
        UiColor surfaceHover,
        UiColor surfacePressed,
        UiColor accent,
        UiColor text,
        UiColor disabledText,
        UiColor border,
        float spacing,
        float radius,
        float controlHeight,
        float fontSize,
        String fontFamily
) {
    public ThemeTokens {
        if (surface == null || surfaceHover == null || surfacePressed == null || accent == null
                || text == null || disabledText == null || border == null) {
            throw new NullPointerException("theme color");
        }
        if (!Float.isFinite(spacing) || spacing < 0.0f || !Float.isFinite(radius) || radius < 0.0f
                || !Float.isFinite(controlHeight) || controlHeight <= 0.0f
                || !Float.isFinite(fontSize) || fontSize <= 0.0f) {
            throw new IllegalArgumentException("theme metrics are invalid");
        }
        if (fontFamily == null || fontFamily.isBlank()) {
            throw new IllegalArgumentException("font family must not be blank");
        }
    }
}
