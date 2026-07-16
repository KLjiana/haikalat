package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSemanticRole;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/** 固定估算行高、只常驻可见 cell 的最小虚拟列表。 */
public final class ListView extends ScrollView {
    private final CellHost cells = new CellHost();
    private final Map<Integer, UiNode> visibleCells = new LinkedHashMap<>();
    private IntFunction<? extends UiNode> cellFactory;
    private int itemCount;
    private int overscan = 2;
    private float estimatedItemHeight = 24.0f;

    public ListView() {
        super.content(cells);
        semantics(UiSemanticRole.LIST, "", "0 items");
    }

    public ListView model(int count, IntFunction<? extends UiNode> factory) {
        ensureOpen();
        if (count < 0) throw new IllegalArgumentException("item count must be non-negative");
        itemCount = count;
        cellFactory = Objects.requireNonNull(factory, "cellFactory");
        clearVisibleCells();
        semantics(UiSemanticRole.LIST, "", count + " items");
        scrollTo(scrollX(), scrollY());
        refreshViewport();
        return this;
    }

    public int itemCount() { return itemCount; }
    public int materializedItemCount() { return visibleCells.size(); }
    public List<Integer> materializedIndices() { return List.copyOf(visibleCells.keySet()); }
    public UiNode materializedCell(int index) { return visibleCells.get(index); }

    public ListView estimatedItemHeight(float value) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException("estimated item height must be finite and positive");
        }
        estimatedItemHeight = value;
        scrollTo(scrollX(), scrollY());
        refreshViewport();
        return this;
    }

    public ListView overscan(int value) {
        if (value < 0) throw new IllegalArgumentException("overscan must be non-negative");
        overscan = value;
        refreshViewport();
        return this;
    }

    /** 在 layout 或滚动变化后更新可见 cell 集合。 */
    public void refreshViewport() {
        refreshViewportInternal();
    }

    @Override
    protected double calculateMaxScrollY() {
        double contentTop = content() == null ? layoutBox().y() : content().layoutBox().y();
        double logicalBottom = contentTop + (double) itemCount * estimatedItemHeight;
        return Math.max(0.0, logicalBottom - layoutBox().bottom());
    }

    @Override
    protected void onScrollOffsetChanged() {
        refreshViewportInternal();
    }

    @Override
    protected boolean onLayoutBoundsResolved(boolean offsetChanged) {
        return refreshViewportInternal();
    }

    private boolean refreshViewportInternal() {
        if (cellFactory == null) return false;
        List<Integer> previousIndices = List.copyOf(visibleCells.keySet());
        int first = Math.max(0, (int) Math.floor(scrollY() / estimatedItemHeight) - overscan);
        int visibleCount = Math.max(1, (int) Math.ceil(layoutBox().height() / estimatedItemHeight));
        int end = Math.min(itemCount, first + visibleCount + overscan * 2);

        for (Integer index : new ArrayList<>(visibleCells.keySet())) {
            if (index < first || index >= end) {
                UiNode node = visibleCells.remove(index);
                cells.remove(node);
                node.close();
            }
        }
        for (int index = first; index < end; index++) {
            if (visibleCells.containsKey(index)) continue;
            UiNode node = Objects.requireNonNull(cellFactory.apply(index), "cellFactory result");
            node.semantics(UiSemanticRole.LIST_ITEM, "Item " + index, Integer.toString(index));
            visibleCells.put(index, node);
            cells.add(node);
        }
        cells.firstItemOffset(first * estimatedItemHeight);
        UiStyle cellStyle = UiStyle.builder()
                .width(UiLength.percent(100.0f))
                .height(UiLength.points(Math.max(estimatedItemHeight,
                        visibleCells.size() * estimatedItemHeight + 2.0f)))
                .flexShrink(0.0f)
                .flexDirection(UiStyle.FlexDirection.COLUMN)
                .build();
        boolean layoutChanged = !previousIndices.equals(List.copyOf(visibleCells.keySet()))
                || !cells.style().equals(cellStyle);
        cells.style(cellStyle);
        markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST, UiDirtyFlag.SEMANTICS);
        return layoutChanged;
    }

    private void clearVisibleCells() {
        for (UiNode node : new ArrayList<>(visibleCells.values())) {
            cells.remove(node);
            node.close();
        }
        visibleCells.clear();
    }

    /** 把局部 materialized cell 列表映射回完整逻辑列表中的起始位置。 */
    private static final class CellHost extends Panel {
        private double firstItemOffset;

        void firstItemOffset(double value) {
            if (firstItemOffset == value) return;
            firstItemOffset = value;
            markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST);
        }

        @Override
        public double childVisualOffsetY() {
            return firstItemOffset;
        }
    }
}
