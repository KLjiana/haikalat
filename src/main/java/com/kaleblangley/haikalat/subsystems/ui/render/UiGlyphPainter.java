package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.UiNode;

/**
 * shaped glyph 数据接入 display list 的边界。
 *
 * <p>实现可以记录一个或多个 {@link UiDisplayList#beginGlyphRun(int, int, UiBlendMode)}；返回
 * {@code false} 时，{@link UiPainter} 会使用确定性的占位字形。实现必须保持 glyph run 平衡。</p>
 */
@FunctionalInterface
public interface UiGlyphPainter {
    boolean paint(UiDisplayList displayList, UiNode node, String text,
                  UiScreenRect bounds, int premultipliedRgba8);

    /** 返回单行文本的 caret stops；实现应使用与 {@link #paint} 相同的 shaping 数据。 */
    default UiTextLineMetrics measureLine(UiNode node, String text) {
        return UiTextLineMetrics.approximate(text, node.computedStyle().fontSize() * 0.6);
    }

    /** 返回始终使用内建占位字形的实现。 */
    static UiGlyphPainter placeholder() {
        return (displayList, node, text, bounds, color) -> false;
    }
}
