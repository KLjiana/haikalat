package com.kaleblangley.haikalat.subsystems.ui.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 无 GL/native 依赖的确定性 shelf atlas 分配器。
 *
 * <p>每个 glyph 四周保留相同 padding。分配器会先按 page、再按 shelf 创建顺序执行
 * first-fit；已有 page 全满后才创建新 page。</p>
 */
public final class GlyphAtlasAllocator {
    private final int pageWidth;
    private final int pageHeight;
    private final int padding;
    private final int maximumPages;
    private final List<Page> pages = new ArrayList<>();

    public GlyphAtlasAllocator(int pageWidth, int pageHeight, int padding, int maximumPages) {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException("Atlas page dimensions must be positive");
        }
        if (padding < 1) {
            throw new IllegalArgumentException("Glyph atlas padding must be at least one pixel");
        }
        if (maximumPages <= 0) {
            throw new IllegalArgumentException("maximumPages must be positive");
        }
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.padding = padding;
        this.maximumPages = maximumPages;
    }

    public int pageWidth() {
        return pageWidth;
    }

    public int pageHeight() {
        return pageHeight;
    }

    public int padding() {
        return padding;
    }

    public int maximumPages() {
        return maximumPages;
    }

    public int pageCount() {
        return pages.size();
    }

    /**
     * 分配区域；page 上限耗尽时抛出明确异常。
     */
    public GlyphAtlasPlacement allocate(int glyphWidth, int glyphHeight) {
        return tryAllocate(glyphWidth, glyphHeight).orElseThrow(() -> new GlyphAtlasFullException(
                "No atlas space for " + glyphWidth + 'x' + glyphHeight + " glyph in "
                        + maximumPages + " page(s) of " + pageWidth + 'x' + pageHeight));
    }

    /**
     * 尝试分配区域；尺寸不可能放入 page 或达到 page 上限时返回 empty。
     */
    public Optional<GlyphAtlasPlacement> tryAllocate(int glyphWidth, int glyphHeight) {
        requirePositive(glyphWidth, "glyphWidth");
        requirePositive(glyphHeight, "glyphHeight");
        long allocatedWidth = (long) glyphWidth + 2L * padding;
        long allocatedHeight = (long) glyphHeight + 2L * padding;
        if (allocatedWidth > pageWidth || allocatedHeight > pageHeight) {
            return Optional.empty();
        }

        int paddedWidth = (int) allocatedWidth;
        int paddedHeight = (int) allocatedHeight;
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            Placement placement = pages.get(pageIndex).tryAllocate(paddedWidth, paddedHeight);
            if (placement != null) {
                return Optional.of(toPublicPlacement(pageIndex, placement, glyphWidth, glyphHeight));
            }
        }
        if (pages.size() >= maximumPages) {
            return Optional.empty();
        }
        Page page = new Page(pageWidth, pageHeight);
        pages.add(page);
        Placement placement = page.tryAllocate(paddedWidth, paddedHeight);
        if (placement == null) {
            throw new IllegalStateException("Fresh atlas page rejected a prevalidated glyph allocation");
        }
        return Optional.of(toPublicPlacement(pages.size() - 1, placement, glyphWidth, glyphHeight));
    }

    /** 丢弃全部 page 布局；下一次分配重新从 page 0 开始。 */
    public void clear() {
        pages.clear();
    }

    /**
     * 清空一个已存在 page 的 shelf 布局，同时保留稳定 page index。
     * 调用方必须先保证该 page 没有仍被使用的 placement。
     */
    public void clearPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            throw new IndexOutOfBoundsException("Atlas page index " + pageIndex
                    + " outside [0, " + pages.size() + ")");
        }
        pages.set(pageIndex, new Page(pageWidth, pageHeight));
    }

    private GlyphAtlasPlacement toPublicPlacement(int pageIndex, Placement placement,
                                                   int glyphWidth, int glyphHeight) {
        return new GlyphAtlasPlacement(pageIndex,
                placement.x() + padding,
                placement.y() + padding,
                glyphWidth,
                glyphHeight,
                pageWidth,
                pageHeight,
                padding);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static final class Page {
        private final int width;
        private final int height;
        private final List<Shelf> shelves = new ArrayList<>();
        private int nextShelfY;

        private Page(int width, int height) {
            this.width = width;
            this.height = height;
        }

        private Placement tryAllocate(int allocationWidth, int allocationHeight) {
            for (Shelf shelf : shelves) {
                if (allocationHeight <= shelf.height && shelf.cursorX + allocationWidth <= width) {
                    int x = shelf.cursorX;
                    shelf.cursorX += allocationWidth;
                    return new Placement(x, shelf.y);
                }
            }
            if (nextShelfY + allocationHeight > height) {
                return null;
            }
            Shelf shelf = new Shelf(nextShelfY, allocationHeight, allocationWidth);
            shelves.add(shelf);
            nextShelfY += allocationHeight;
            return new Placement(0, shelf.y);
        }
    }

    private static final class Shelf {
        private final int y;
        private final int height;
        private int cursorX;

        private Shelf(int y, int height, int cursorX) {
            this.y = y;
            this.height = height;
            this.cursorX = cursorX;
        }
    }

    private record Placement(int x, int y) {
    }
}
