package com.kaleblangley.haikalat.subsystems.ui.layout;

/** 逻辑像素坐标中的布局矩形，原点位于左上。 */
public record LayoutBox(float x, float y, float width, float height) {
    public static final LayoutBox EMPTY = new LayoutBox(0.0f, 0.0f, 0.0f, 0.0f);

    public LayoutBox {
        if (!Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(width) || !Float.isFinite(height)
                || width < 0.0f || height < 0.0f) {
            throw new IllegalArgumentException("layout box must contain finite non-negative dimensions");
        }
    }

    public float right() {
        return x + width;
    }

    public float bottom() {
        return y + height;
    }

    public boolean contains(double px, double py) {
        return px >= x && px < right() && py >= y && py < bottom();
    }

    public LayoutBox intersect(LayoutBox other) {
        float left = Math.max(x, other.x);
        float top = Math.max(y, other.y);
        float right = Math.min(right(), other.right());
        float bottom = Math.min(bottom(), other.bottom());
        return new LayoutBox(left, top, Math.max(0.0f, right - left),
                Math.max(0.0f, bottom - top));
    }
}
