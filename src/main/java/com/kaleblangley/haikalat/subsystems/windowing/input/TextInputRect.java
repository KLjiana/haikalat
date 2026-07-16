package com.kaleblangley.haikalat.subsystems.windowing.input;

/**
 * 文本输入法候选窗口使用的 framebuffer 像素矩形。
 *
 * <p>该值属于 windowing 输入边界，不携带 UI tree 或 renderer 类型，因此任意上层
 * subsystem 都可以在不形成反向依赖的前提下定位原生候选窗口。</p>
 */
public record TextInputRect(double x, double y, double width, double height) {
    public static final TextInputRect EMPTY = new TextInputRect(0.0, 0.0, 0.0, 0.0);

    public TextInputRect {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(width) || !Double.isFinite(height)) {
            throw new IllegalArgumentException("Text input rectangle values must be finite");
        }
        if (width < 0.0 || height < 0.0) {
            throw new IllegalArgumentException("Text input rectangle dimensions must be non-negative");
        }
    }
}
