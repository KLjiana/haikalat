package com.kaleblangley.haikalat.subsystems.ui.style;

/** 四边长度集合。 */
public record UiInsets(UiLength left, UiLength top, UiLength right, UiLength bottom) {
    public static final UiInsets ZERO = points(0.0f);

    public UiInsets {
        if (left == null || top == null || right == null || bottom == null) {
            throw new NullPointerException("inset length");
        }
    }

    public static UiInsets points(float value) {
        UiLength length = UiLength.points(value);
        return new UiInsets(length, length, length, length);
    }

    public static UiInsets points(float horizontal, float vertical) {
        UiLength h = UiLength.points(horizontal);
        UiLength v = UiLength.points(vertical);
        return new UiInsets(h, v, h, v);
    }
}
