package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.event.PointerEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEvent;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutPostProcessor;

import java.util.Objects;

/** 双轴滚动且强制裁剪内容的基础视图。 */
public class ScrollView extends Panel implements LayoutPostProcessor {
    private static final double SCROLL_EPSILON = 1.0e-6;

    private UiNode content;
    private double scrollX;
    private double scrollY;
    private double wheelScale = 32.0;
    private boolean boundsResolved;

    public ScrollView() { clipChildren(true); }

    public UiNode content() { return content; }
    public ScrollView content(UiNode value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "content");
        if (content == value) return this;
        if (content != null) remove(content);
        content = value;
        boundsResolved = false;
        add(value);
        return this;
    }
    public double scrollX() { return scrollX; }
    public double scrollY() { return scrollY; }

    /** 返回当前布局下允许的最大水平滚动偏移；首次布局前返回零。 */
    public double maxScrollX() {
        return boundsResolved ? calculateMaxScrollX() : 0.0;
    }

    /** 返回当前布局下允许的最大垂直滚动偏移；首次布局前返回零。 */
    public double maxScrollY() {
        return boundsResolved ? calculateMaxScrollY() : 0.0;
    }

    public ScrollView scrollTo(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("scroll offsets must be finite");
        }
        double nextX = Math.max(0.0, x);
        double nextY = Math.max(0.0, y);
        if (boundsResolved) {
            nextX = Math.min(nextX, calculateMaxScrollX());
            nextY = Math.min(nextY, calculateMaxScrollY());
        }
        if (setScrollOffset(nextX, nextY)) onScrollOffsetChanged();
        return this;
    }
    public ScrollView wheelScale(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("wheel scale must be finite and positive");
        }
        wheelScale = value;
        return this;
    }

    @Override public double childVisualOffsetX() { return -scrollX; }
    @Override public double childVisualOffsetY() { return -scrollY; }

    /** 普通滚动内容相对视口右边缘的可滚动距离。 */
    protected double calculateMaxScrollX() {
        if (content == null) return 0.0;
        LayoutBox viewport = layoutBox();
        LayoutBox contentBox = content.layoutBox();
        return Math.max(0.0, contentBox.right() - viewport.right());
    }

    /** 普通滚动内容相对视口下边缘的可滚动距离。 */
    protected double calculateMaxScrollY() {
        if (content == null) return 0.0;
        LayoutBox viewport = layoutBox();
        LayoutBox contentBox = content.layoutBox();
        return Math.max(0.0, contentBox.bottom() - viewport.bottom());
    }

    /** 滚动位置发生变化后的控件扩展点。 */
    protected void onScrollOffsetChanged() {
    }

    /** 最终视口尺寸可用后的控件扩展点；返回是否需要重新布局。 */
    protected boolean onLayoutBoundsResolved(boolean offsetChanged) {
        return false;
    }

    @Override
    public final boolean afterLayout() {
        boundsResolved = true;
        boolean changed = setScrollOffset(
                Math.min(scrollX, calculateMaxScrollX()),
                Math.min(scrollY, calculateMaxScrollY()));
        return onLayoutBoundsResolved(changed);
    }

    @Override
    protected void handleDefaultEvent(UiEvent event) {
        if (event.type() == com.kaleblangley.haikalat.subsystems.ui.event.UiEventType.SCROLL
                && event instanceof PointerEvent pointer && enabled()) {
            double previousX = scrollX;
            double previousY = scrollY;
            scrollTo(scrollX - pointer.scrollX() * wheelScale,
                    scrollY - pointer.scrollY() * wheelScale);
            if (Math.abs(scrollX - previousX) > SCROLL_EPSILON
                    || Math.abs(scrollY - previousY) > SCROLL_EPSILON) {
                event.preventDefault();
            }
        }
    }

    private boolean setScrollOffset(double x, double y) {
        if (Double.compare(scrollX, x) == 0 && Double.compare(scrollY, y) == 0) return false;
        scrollX = x;
        scrollY = y;
        markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST);
        return true;
    }
}
