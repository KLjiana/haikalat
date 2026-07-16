package com.kaleblangley.haikalat.subsystems.ui.render;

/** 纹理归一化坐标矩形。坐标允许超出 0～1，以支持重复和自定义采样。 */
public record UiUvRect(float u0, float v0, float u1, float v1) {
    /** 覆盖完整纹理的 UV。 */
    public static final UiUvRect FULL = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);

    /** 校验有限 UV。 */
    public UiUvRect {
        if (!Float.isFinite(u0) || !Float.isFinite(v0)
                || !Float.isFinite(u1) || !Float.isFinite(v1)) {
            throw new IllegalArgumentException("UV coordinates must be finite");
        }
    }
}
