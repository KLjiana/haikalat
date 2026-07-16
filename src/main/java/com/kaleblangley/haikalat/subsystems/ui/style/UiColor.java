package com.kaleblangley.haikalat.subsystems.ui.style;

/** 线性空间 RGBA 颜色。 */
public record UiColor(float red, float green, float blue, float alpha) {
    public static final UiColor TRANSPARENT = new UiColor(0.0f, 0.0f, 0.0f, 0.0f);
    public static final UiColor WHITE = new UiColor(1.0f, 1.0f, 1.0f, 1.0f);
    public static final UiColor BLACK = new UiColor(0.0f, 0.0f, 0.0f, 1.0f);

    public UiColor {
        requireUnit(red, "red");
        requireUnit(green, "green");
        requireUnit(blue, "blue");
        requireUnit(alpha, "alpha");
    }

    /** 从 0xRRGGBBAA sRGB token 构造线性 RGB；alpha 保持线性。 */
    public static UiColor fromSrgbHex(int rgba) {
        float red = ((rgba >>> 24) & 0xff) / 255.0f;
        float green = ((rgba >>> 16) & 0xff) / 255.0f;
        float blue = ((rgba >>> 8) & 0xff) / 255.0f;
        float alpha = (rgba & 0xff) / 255.0f;
        return new UiColor(srgbToLinear(red), srgbToLinear(green), srgbToLinear(blue), alpha);
    }

    /** 返回适合 ONE/ONE_MINUS_SRC_ALPHA 混合的预乘颜色。 */
    public UiColor premultiplied() {
        return new UiColor(red * alpha, green * alpha, blue * alpha, alpha);
    }

    public UiColor withAlpha(float value) {
        return new UiColor(red, green, blue, value);
    }

    /** 返回供 normalized vertex attribute 使用的 0xRRGGBBAA 预乘线性颜色。 */
    public int packedPremultipliedRgba8() {
        UiColor value = premultiplied();
        return quantize(value.red) << 24 | quantize(value.green) << 16
                | quantize(value.blue) << 8 | quantize(value.alpha);
    }

    private static float srgbToLinear(float value) {
        return value <= 0.04045f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4);
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }

    private static int quantize(float value) {
        return Math.min(255, Math.max(0, Math.round(value * 255.0f)));
    }
}
