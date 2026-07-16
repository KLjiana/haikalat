package com.kaleblangley.haikalat.subsystems.ui.render;

/** OpenGL 左下原点 framebuffer 坐标中的 scissor 矩形。 */
public record GlScissorRect(int x, int y, int width, int height) {
    /** 空 scissor。 */
    public static final GlScissorRect EMPTY = new GlScissorRect(0, 0, 0, 0);

    /** 校验非负坐标和尺寸。 */
    public GlScissorRect {
        if (x < 0 || y < 0 || width < 0 || height < 0
                || (long) x + width > Integer.MAX_VALUE
                || (long) y + height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("scissor coordinates and dimensions must be non-negative");
        }
    }

    /**
     * 把左上原点的逻辑裁剪转换为 OpenGL scissor。
     *
     * <p>左/上边缘向下取整，右/下边缘向上取整，然后裁到 framebuffer 范围。
     * 零面积逻辑裁剪始终保持零面积，不会因向外取整意外覆盖一个像素。</p>
     *
     * @param logical 逻辑裁剪
     * @param scaleX 水平内容缩放
     * @param scaleY 垂直内容缩放
     * @param framebufferWidth framebuffer 宽度，可为零
     * @param framebufferHeight framebuffer 高度，可为零
     * @return framebuffer scissor
     */
    public static GlScissorRect fromLogical(UiScreenRect logical,
                                             double scaleX, double scaleY,
                                             int framebufferWidth, int framebufferHeight) {
        if (logical == null) {
            throw new NullPointerException("logical");
        }
        if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY)
                || scaleX <= 0.0 || scaleY <= 0.0) {
            throw new IllegalArgumentException("content scale must be finite and positive");
        }
        if (framebufferWidth < 0 || framebufferHeight < 0) {
            throw new IllegalArgumentException("framebuffer dimensions must be non-negative");
        }

        int left = floorAndClamp(logical.x() * scaleX, framebufferWidth);
        int right = ceilAndClamp(logical.right() * scaleX, framebufferWidth);
        int top = floorAndClamp(logical.y() * scaleY, framebufferHeight);
        int bottom = ceilAndClamp(logical.bottom() * scaleY, framebufferHeight);
        int glWidth = logical.width() == 0.0 ? 0 : Math.max(0, right - left);
        int glHeight = logical.height() == 0.0 ? 0 : Math.max(0, bottom - top);
        return new GlScissorRect(left, framebufferHeight - bottom, glWidth, glHeight);
    }

    /** 返回 scissor 是否没有可见面积。 */
    public boolean isEmpty() {
        return width == 0 || height == 0;
    }

    private static int floorAndClamp(double value, int maximum) {
        if (value <= 0.0) {
            return 0;
        }
        if (value >= maximum) {
            return maximum;
        }
        return (int) Math.floor(value);
    }

    private static int ceilAndClamp(double value, int maximum) {
        if (value <= 0.0) {
            return 0;
        }
        if (value >= maximum) {
            return maximum;
        }
        return (int) Math.ceil(value);
    }
}
