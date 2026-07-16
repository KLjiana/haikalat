package com.kaleblangley.haikalat.subsystems.ui.render;

/**
 * UI 逻辑坐标中的轴对齐矩形，原点位于内容区左上角。
 *
 * <p>矩形始终保留逻辑坐标精度；嵌套裁剪应先调用 {@link #intersect(UiScreenRect)}，
 * 最后再统一转换为 framebuffer scissor，避免逐层取整造成边缘漂移。</p>
 */
public record UiScreenRect(double x, double y, double width, double height) {
    /** 空逻辑矩形。 */
    public static final UiScreenRect EMPTY = new UiScreenRect(0.0, 0.0, 0.0, 0.0);

    /** 校验有限坐标和非负尺寸。 */
    public UiScreenRect {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(width) || !Double.isFinite(height)
                || width < 0.0 || height < 0.0
                || !Double.isFinite(x + width) || !Double.isFinite(y + height)) {
            throw new IllegalArgumentException("UI rectangle must be finite with non-negative dimensions");
        }
    }

    /** 返回右边界。 */
    public double right() {
        return x + width;
    }

    /** 返回下边界。 */
    public double bottom() {
        return y + height;
    }

    /** 返回矩形是否没有可见面积。 */
    public boolean isEmpty() {
        return width == 0.0 || height == 0.0;
    }

    /**
     * 在逻辑空间求交。两个矩形不重叠时返回位于交界处的零面积矩形。
     *
     * @param other 另一个逻辑矩形
     * @return 逻辑坐标中的交集
     */
    public UiScreenRect intersect(UiScreenRect other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        double left = Math.max(x, other.x);
        double top = Math.max(y, other.y);
        double right = Math.min(right(), other.right());
        double bottom = Math.min(bottom(), other.bottom());
        return new UiScreenRect(left, top, Math.max(0.0, right - left),
                Math.max(0.0, bottom - top));
    }

    /**
     * 将当前逻辑矩形转换为 OpenGL 左下原点的 framebuffer scissor。
     *
     * @param scaleX 水平内容缩放
     * @param scaleY 垂直内容缩放
     * @param framebufferWidth framebuffer 宽度，可为零
     * @param framebufferHeight framebuffer 高度，可为零
     * @return 已向外取整并裁到 framebuffer 内的 scissor
     */
    public GlScissorRect toGlScissor(double scaleX, double scaleY,
                                     int framebufferWidth, int framebufferHeight) {
        return GlScissorRect.fromLogical(this, scaleX, scaleY,
                framebufferWidth, framebufferHeight);
    }
}
