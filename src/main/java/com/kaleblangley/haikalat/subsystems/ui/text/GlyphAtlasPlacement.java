package com.kaleblangley.haikalat.subsystems.ui.text;

/**
 * glyph 在某个 atlas page 中的位置；{@code x/y/width/height} 指向不含 padding 的可见区域。
 */
public record GlyphAtlasPlacement(
        int pageIndex,
        int x,
        int y,
        int width,
        int height,
        int pageWidth,
        int pageHeight,
        int padding) {

    public GlyphAtlasPlacement {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be non-negative");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Glyph placement dimensions must be positive");
        }
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException("Atlas page dimensions must be positive");
        }
        if (padding < 1) {
            throw new IllegalArgumentException("Glyph atlas padding must be at least one pixel");
        }
        if (x < padding || y < padding
                || (long) x + width + padding > pageWidth
                || (long) y + height + padding > pageHeight) {
            throw new IllegalArgumentException("Glyph and padding exceed atlas page bounds");
        }
    }

    public int allocatedX() {
        return x - padding;
    }

    public int allocatedY() {
        return y - padding;
    }

    public int allocatedWidth() {
        return Math.addExact(width, Math.multiplyExact(padding, 2));
    }

    public int allocatedHeight() {
        return Math.addExact(height, Math.multiplyExact(padding, 2));
    }

    public float u0() {
        return (float) x / pageWidth;
    }

    public float v0() {
        return (float) y / pageHeight;
    }

    public float u1() {
        return (float) (x + width) / pageWidth;
    }

    public float v1() {
        return (float) (y + height) / pageHeight;
    }

    /** 判断两个 placement 的含 padding 占用区域是否相交。 */
    public boolean overlapsAllocatedRegion(GlyphAtlasPlacement other) {
        if (other == null || pageIndex != other.pageIndex) {
            return false;
        }
        return allocatedX() < other.allocatedX() + other.allocatedWidth()
                && other.allocatedX() < allocatedX() + allocatedWidth()
                && allocatedY() < other.allocatedY() + other.allocatedHeight()
                && other.allocatedY() < allocatedY() + allocatedHeight();
    }
}
