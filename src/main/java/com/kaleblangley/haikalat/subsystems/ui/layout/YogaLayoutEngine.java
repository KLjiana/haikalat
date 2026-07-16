package com.kaleblangley.haikalat.subsystems.ui.layout;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import org.lwjgl.util.yoga.YGMeasureFunc;
import org.lwjgl.util.yoga.YGSize;
import org.lwjgl.util.yoga.Yoga;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 LWJGL Yoga 的 retained tree 布局实现。
 *
 * <p>本类拥有 Yoga config、node 和 callback。实例受创建线程约束，关闭时按当前树的
 * child-to-parent 逆深度顺序释放所有 native 资源。</p>
 */
public final class YogaLayoutEngine implements LayoutEngine {
    private static final int MAX_LAYOUT_STABILIZATION_PASSES = 2;
    private static final ClassValue<Boolean> HAS_INTRINSIC_MEASURE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("measure", MeasureContext.class);
                return method.getDeclaringClass() != UiNode.class;
            } catch (NoSuchMethodException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    };

    private final Thread ownerThread = Thread.currentThread();
    private final IntrinsicMeasurer intrinsicMeasurer;
    private final long config;
    private final IdentityHashMap<UiNode, NativeNode> nodes = new IdentityHashMap<>();
    private final ArrayList<NativeNode> creationOrder = new ArrayList<>();
    private MeasureFailure measureFailure;
    private long nextAllocationSequence;
    private boolean layoutInProgress;
    private boolean closed;

    /** 创建使用逻辑像素且不做 Yoga 像素取整的布局引擎。 */
    public YogaLayoutEngine() {
        this(IntrinsicMeasurer.nodeDefault());
    }

    /** 创建使用调用方固有尺寸服务的逻辑像素布局引擎。 */
    public YogaLayoutEngine(IntrinsicMeasurer intrinsicMeasurer) {
        this.intrinsicMeasurer = Objects.requireNonNull(intrinsicMeasurer, "intrinsicMeasurer");
        long createdConfig = Yoga.YGConfigNew();
        if (createdConfig == 0L) {
            throw new UiLayoutException("Yoga config 创建失败");
        }
        try {
            Yoga.YGConfigSetPointScaleFactor(createdConfig, 0.0f);
            Yoga.YGConfigSetUseWebDefaults(createdConfig, false);
        } catch (RuntimeException | Error failure) {
            Yoga.YGConfigFree(createdConfig);
            throw failure;
        }
        config = createdConfig;
    }

    @Override
    public void layout(List<? extends UiNode> roots, float logicalWidth, float logicalHeight) {
        assertOwnerThread();
        ensureOpen();
        if (layoutInProgress) {
            throw new IllegalStateException("Yoga layout cannot be entered recursively");
        }
        validateViewport(logicalWidth, logicalHeight);
        Objects.requireNonNull(roots, "roots");

        layoutInProgress = true;
        try {
            for (int pass = 0; pass < MAX_LAYOUT_STABILIZATION_PASSES; pass++) {
                List<TreeEntry> tree = snapshotTree(roots);
                rebuildNativeTree(tree);
                measureFailure = null;

                for (TreeEntry entry : tree) {
                    if (!entry.root()) continue;
                    long rootHandle = nodes.get(entry.node()).handle;
                    constrainRootToViewport(rootHandle, logicalWidth, logicalHeight);
                    Yoga.YGNodeCalculateLayout(rootHandle,
                            logicalWidth, logicalHeight, Yoga.YGDirectionLTR);
                    throwPendingMeasureFailure();
                    verifyTreeUnchanged(tree);
                }
                applyLayout(tree);
                if (!runPostProcessors(tree)) break;
            }
        } finally {
            measureFailure = null;
            layoutInProgress = false;
        }
    }

    private static boolean runPostProcessors(List<TreeEntry> tree) {
        boolean relayout = false;
        for (int index = tree.size() - 1; index >= 0; index--) {
            UiNode node = tree.get(index).node();
            if (!node.isClosed() && node instanceof LayoutPostProcessor processor) {
                relayout |= processor.afterLayout();
            }
        }
        return relayout;
    }

    /** 返回当前由引擎拥有的 native node 数量，仅用于同包诊断和测试。 */
    int managedNodeCount() {
        return nodes.size();
    }

    @Override
    public void close() {
        assertOwnerThread();
        if (closed) return;
        closed = true;

        RuntimeException failure = null;
        for (NativeNode node : creationOrder) {
            try {
                Yoga.YGNodeRemoveAllChildren(node.handle);
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        for (NativeNode node : reverseTreeOrder(creationOrder)) {
            try {
                release(node);
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        creationOrder.clear();
        nodes.clear();
        try {
            Yoga.YGConfigFree(config);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        if (failure != null) throw failure;
    }

    private List<TreeEntry> snapshotTree(List<? extends UiNode> roots) {
        ArrayList<TreeEntry> entries = new ArrayList<>();
        IdentityHashMap<UiNode, Boolean> visited = new IdentityHashMap<>();
        for (UiNode root : roots) {
            snapshotNode(Objects.requireNonNull(root, "root"), null, -1, 0,
                    true, entries, visited);
        }
        return List.copyOf(entries);
    }

    private void snapshotNode(UiNode node, UiNode parent, int indexInParent, int depth, boolean root,
                              List<TreeEntry> entries, Map<UiNode, Boolean> visited) {
        if (node.isClosed()) {
            throw nodeFailure(node, "cannot layout a closed node", null);
        }
        if (visited.put(node, Boolean.TRUE) != null) {
            throw nodeFailure(node, "node appears more than once in the layout forest", null);
        }
        List<UiNode> children = node.children();
        entries.add(new TreeEntry(node, parent, indexInParent, depth, root,
                node.style(), node.computedStyle(),
                node.visibility(), children));
        for (int index = 0; index < children.size(); index++) {
            snapshotNode(children.get(index), node, index, depth + 1,
                    false, entries, visited);
        }
    }

    private void rebuildNativeTree(List<TreeEntry> tree) {
        IdentityHashMap<UiNode, Boolean> live = new IdentityHashMap<>();
        for (TreeEntry entry : tree) live.put(entry.node(), Boolean.TRUE);

        for (NativeNode node : creationOrder) {
            Yoga.YGNodeRemoveAllChildren(node.handle);
        }
        ArrayList<NativeNode> stale = new ArrayList<>();
        for (NativeNode nativeNode : creationOrder) {
            if (!live.containsKey(nativeNode.node)) stale.add(nativeNode);
        }
        for (NativeNode nativeNode : reverseTreeOrder(stale)) {
            release(nativeNode);
            creationOrder.remove(nativeNode);
            nodes.remove(nativeNode.node);
        }

        for (TreeEntry entry : tree) {
            NativeNode nativeNode = nodes.computeIfAbsent(entry.node(), this::createNativeNode);
            nativeNode.depth = entry.depth();
            applyStyle(nativeNode, entry);
            configureMeasure(nativeNode, entry);
            if (entry.parent() != null) {
                NativeNode parent = nodes.get(entry.parent());
                Yoga.YGNodeInsertChild(parent.handle, nativeNode.handle,
                        entry.indexInParent());
            }
        }
    }

    private NativeNode createNativeNode(UiNode node) {
        long handle = Yoga.YGNodeNewWithConfig(config);
        if (handle == 0L) {
            throw nodeFailure(node, "Yoga node creation failed", null);
        }
        NativeNode nativeNode = new NativeNode(node, handle, nextAllocationSequence++);
        creationOrder.add(nativeNode);
        return nativeNode;
    }

    private void applyStyle(NativeNode nativeNode, TreeEntry entry) {
        long handle = nativeNode.handle;
        UiStyle style = entry.style();
        applyDimension(handle, Dimension.WIDTH, style.width());
        applyDimension(handle, Dimension.HEIGHT, style.height());
        applyDimension(handle, Dimension.MIN_WIDTH, style.minWidth());
        applyDimension(handle, Dimension.MIN_HEIGHT, style.minHeight());
        applyDimension(handle, Dimension.MAX_WIDTH, style.maxWidth());
        applyDimension(handle, Dimension.MAX_HEIGHT, style.maxHeight());
        applyInsets(handle, style.margin(), true);
        applyInsets(handle, style.padding(), false);
        Yoga.YGNodeStyleSetFlexDirection(handle, flexDirection(style.flexDirection()));
        Yoga.YGNodeStyleSetJustifyContent(handle, justify(style.justifyContent()));
        Yoga.YGNodeStyleSetAlignItems(handle, align(style.alignItems()));
        Yoga.YGNodeStyleSetAlignSelf(handle, align(style.alignSelf()));
        Yoga.YGNodeStyleSetPositionType(handle, positionType(style.positionType()));
        Yoga.YGNodeStyleSetOverflow(handle, overflow(style.overflow()));
        Yoga.YGNodeStyleSetFlexGrow(handle, style.flexGrow());
        Yoga.YGNodeStyleSetFlexShrink(handle, style.flexShrink());
        Yoga.YGNodeStyleSetGap(handle, Yoga.YGGutterAll, style.gap());
        Yoga.YGNodeStyleSetDisplay(handle,
                entry.visibility() == UiVisibility.COLLAPSED ? Yoga.YGDisplayNone : Yoga.YGDisplayFlex);

        ComputedStyle computedStyle = entry.computedStyle();
        Yoga.YGNodeStyleSetBorder(handle, Yoga.YGEdgeAll, computedStyle.borderWidth());
    }

    private void configureMeasure(NativeNode nativeNode, TreeEntry entry) {
        boolean measurable = entry.children().isEmpty()
                && HAS_INTRINSIC_MEASURE.get(entry.node().getClass());
        if (measurable && nativeNode.measureCallback == null) {
            YGMeasureFunc callback = YGMeasureFunc.create(
                    (node, width, widthMode, height, heightMode, result) ->
                            measure(nativeNode, width, widthMode, height, heightMode, result));
            try {
                Yoga.YGNodeSetMeasureFunc(nativeNode.handle, callback);
                Yoga.YGNodeSetNodeType(nativeNode.handle, Yoga.YGNodeTypeText);
                nativeNode.measureCallback = callback;
            } catch (RuntimeException | Error failure) {
                callback.free();
                throw failure;
            }
        } else if (!measurable && nativeNode.measureCallback != null) {
            Yoga.YGNodeSetMeasureFunc(nativeNode.handle, null);
            nativeNode.measureCallback.free();
            nativeNode.measureCallback = null;
            Yoga.YGNodeSetNodeType(nativeNode.handle, Yoga.YGNodeTypeDefault);
        }
        if (measurable && entry.node().isDirty(UiDirtyFlag.MEASURE)) {
            Yoga.YGNodeMarkDirty(nativeNode.handle);
        }
    }

    private void measure(NativeNode nativeNode, float width, int widthMode,
                         float height, int heightMode, YGSize output) {
        if (measureFailure != null) {
            output.set(0.0f, 0.0f);
            return;
        }
        try {
            MeasureContext.Mode mappedWidthMode = measureMode(widthMode);
            MeasureContext.Mode mappedHeightMode = measureMode(heightMode);
            float availableWidth = availableSize(width, mappedWidthMode);
            float availableHeight = availableSize(height, mappedHeightMode);
            MeasureResult measured = Objects.requireNonNull(intrinsicMeasurer.measure(
                    nativeNode.node, new MeasureContext(availableWidth, mappedWidthMode,
                            availableHeight, mappedHeightMode)), "measure result");
            output.set(constrain(measured.width(), availableWidth, mappedWidthMode),
                    constrain(measured.height(), availableHeight, mappedHeightMode));
        } catch (Throwable failure) {
            measureFailure = new MeasureFailure(nativeNode.node, failure);
            output.set(0.0f, 0.0f);
        }
    }

    private void throwPendingMeasureFailure() {
        if (measureFailure == null) return;
        MeasureFailure failure = measureFailure;
        throw nodeFailure(failure.node(), "intrinsic measure callback failed", failure.cause());
    }

    private void verifyTreeUnchanged(List<TreeEntry> tree) {
        for (TreeEntry entry : tree) {
            UiNode node = entry.node();
            if (node.isClosed()
                    || !entry.style().equals(node.style())
                    || !entry.computedStyle().equals(node.computedStyle())
                    || entry.visibility() != node.visibility()
                    || !sameIdentityOrder(entry.children(), node.children())) {
                throw nodeFailure(node, "UI tree/style changed during intrinsic measurement", null);
            }
        }
    }

    private void applyLayout(List<TreeEntry> tree) {
        IdentityHashMap<UiNode, LayoutBox> boxes = new IdentityHashMap<>();
        for (TreeEntry entry : tree) {
            NativeNode nativeNode = nodes.get(entry.node());
            float parentX = 0.0f;
            float parentY = 0.0f;
            if (entry.parent() != null) {
                LayoutBox parent = boxes.get(entry.parent());
                parentX = parent.x();
                parentY = parent.y();
            }
            LayoutBox box = new LayoutBox(
                    parentX + Yoga.YGNodeLayoutGetLeft(nativeNode.handle),
                    parentY + Yoga.YGNodeLayoutGetTop(nativeNode.handle),
                    Math.max(0.0f, Yoga.YGNodeLayoutGetWidth(nativeNode.handle)),
                    Math.max(0.0f, Yoga.YGNodeLayoutGetHeight(nativeNode.handle)));
            boxes.put(entry.node(), box);
            entry.node().applyLayout(box);
        }
    }

    private void release(NativeNode nativeNode) {
        if (nativeNode.measureCallback != null) {
            Yoga.YGNodeSetMeasureFunc(nativeNode.handle, null);
            nativeNode.measureCallback.free();
            nativeNode.measureCallback = null;
        }
        Yoga.YGNodeFree(nativeNode.handle);
    }

    private static void applyDimension(long node, Dimension dimension, UiLength length) {
        switch (dimension) {
            case WIDTH -> applyLength(length,
                    value -> Yoga.YGNodeStyleSetWidth(node, value),
                    value -> Yoga.YGNodeStyleSetWidthPercent(node, value),
                    () -> Yoga.YGNodeStyleSetWidthAuto(node));
            case HEIGHT -> applyLength(length,
                    value -> Yoga.YGNodeStyleSetHeight(node, value),
                    value -> Yoga.YGNodeStyleSetHeightPercent(node, value),
                    () -> Yoga.YGNodeStyleSetHeightAuto(node));
            case MIN_WIDTH -> applyBoundLength(length,
                    value -> Yoga.YGNodeStyleSetMinWidth(node, value),
                    value -> Yoga.YGNodeStyleSetMinWidthPercent(node, value));
            case MIN_HEIGHT -> applyBoundLength(length,
                    value -> Yoga.YGNodeStyleSetMinHeight(node, value),
                    value -> Yoga.YGNodeStyleSetMinHeightPercent(node, value));
            case MAX_WIDTH -> applyBoundLength(length,
                    value -> Yoga.YGNodeStyleSetMaxWidth(node, value),
                    value -> Yoga.YGNodeStyleSetMaxWidthPercent(node, value));
            case MAX_HEIGHT -> applyBoundLength(length,
                    value -> Yoga.YGNodeStyleSetMaxHeight(node, value),
                    value -> Yoga.YGNodeStyleSetMaxHeightPercent(node, value));
        }
    }

    private static void applyLength(UiLength length, FloatSetter points,
                                    FloatSetter percent, Runnable auto) {
        switch (length.unit()) {
            case POINTS -> points.set(length.value());
            case PERCENT -> percent.set(length.value());
            case AUTO -> auto.run();
        }
    }

    private static void applyBoundLength(UiLength length, FloatSetter points,
                                         FloatSetter percent) {
        switch (length.unit()) {
            case POINTS -> points.set(length.value());
            case PERCENT -> percent.set(length.value());
            case AUTO -> points.set(Yoga.YGUndefined);
        }
    }

    private static void applyInsets(long node, UiInsets insets, boolean margin) {
        applyInset(node, Yoga.YGEdgeLeft, insets.left(), margin);
        applyInset(node, Yoga.YGEdgeTop, insets.top(), margin);
        applyInset(node, Yoga.YGEdgeRight, insets.right(), margin);
        applyInset(node, Yoga.YGEdgeBottom, insets.bottom(), margin);
    }

    private static void applyInset(long node, int edge, UiLength length, boolean margin) {
        if (margin) {
            switch (length.unit()) {
                case POINTS -> Yoga.YGNodeStyleSetMargin(node, edge, length.value());
                case PERCENT -> Yoga.YGNodeStyleSetMarginPercent(node, edge, length.value());
                case AUTO -> Yoga.YGNodeStyleSetMarginAuto(node, edge);
            }
        } else {
            switch (length.unit()) {
                case POINTS -> Yoga.YGNodeStyleSetPadding(node, edge, length.value());
                case PERCENT -> Yoga.YGNodeStyleSetPaddingPercent(node, edge, length.value());
                case AUTO -> Yoga.YGNodeStyleSetPadding(node, edge, Yoga.YGUndefined);
            }
        }
    }

    private static int flexDirection(UiStyle.FlexDirection value) {
        return switch (value) {
            case ROW -> Yoga.YGFlexDirectionRow;
            case ROW_REVERSE -> Yoga.YGFlexDirectionRowReverse;
            case COLUMN -> Yoga.YGFlexDirectionColumn;
            case COLUMN_REVERSE -> Yoga.YGFlexDirectionColumnReverse;
        };
    }

    private static int justify(UiStyle.JustifyContent value) {
        return switch (value) {
            case FLEX_START -> Yoga.YGJustifyFlexStart;
            case CENTER -> Yoga.YGJustifyCenter;
            case FLEX_END -> Yoga.YGJustifyFlexEnd;
            case SPACE_BETWEEN -> Yoga.YGJustifySpaceBetween;
            case SPACE_AROUND -> Yoga.YGJustifySpaceAround;
            case SPACE_EVENLY -> Yoga.YGJustifySpaceEvenly;
        };
    }

    private static int align(UiStyle.AlignItems value) {
        return switch (value) {
            case AUTO -> Yoga.YGAlignAuto;
            case FLEX_START -> Yoga.YGAlignFlexStart;
            case CENTER -> Yoga.YGAlignCenter;
            case FLEX_END -> Yoga.YGAlignFlexEnd;
            case STRETCH -> Yoga.YGAlignStretch;
            case BASELINE -> Yoga.YGAlignBaseline;
            case SPACE_BETWEEN -> Yoga.YGAlignSpaceBetween;
            case SPACE_AROUND -> Yoga.YGAlignSpaceAround;
        };
    }

    private static int positionType(UiStyle.PositionType value) {
        return switch (value) {
            case RELATIVE -> Yoga.YGPositionTypeRelative;
            case ABSOLUTE -> Yoga.YGPositionTypeAbsolute;
        };
    }

    private static int overflow(UiStyle.Overflow value) {
        return switch (value) {
            case VISIBLE -> Yoga.YGOverflowVisible;
            case HIDDEN -> Yoga.YGOverflowHidden;
            case SCROLL -> Yoga.YGOverflowScroll;
        };
    }

    private static MeasureContext.Mode measureMode(int value) {
        return switch (value) {
            case Yoga.YGMeasureModeUndefined -> MeasureContext.Mode.UNDEFINED;
            case Yoga.YGMeasureModeExactly -> MeasureContext.Mode.EXACTLY;
            case Yoga.YGMeasureModeAtMost -> MeasureContext.Mode.AT_MOST;
            default -> throw new UiLayoutException("Unknown Yoga measure mode: " + value);
        };
    }

    private static float availableSize(float value, MeasureContext.Mode mode) {
        if (mode == MeasureContext.Mode.UNDEFINED) return 0.0f;
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new UiLayoutException("Yoga supplied an invalid measure constraint: " + value);
        }
        return value;
    }

    private static float constrain(float measured, float available, MeasureContext.Mode mode) {
        return switch (mode) {
            case UNDEFINED -> measured;
            case EXACTLY -> available;
            case AT_MOST -> Math.min(measured, available);
        };
    }

    private static boolean sameIdentityOrder(List<UiNode> expected, List<UiNode> actual) {
        if (expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (expected.get(index) != actual.get(index)) return false;
        }
        return true;
    }

    private static void validateViewport(float width, float height) {
        if (!Float.isFinite(width) || !Float.isFinite(height) || width < 0.0f || height < 0.0f) {
            throw new IllegalArgumentException("logical viewport must contain finite non-negative dimensions");
        }
    }

    private static void constrainRootToViewport(long root, float width, float height) {
        // 文档 root 定义窗口坐标空间；自身 width/min/max 不能把该坐标空间再次缩放。
        Yoga.YGNodeStyleSetWidth(root, width);
        Yoga.YGNodeStyleSetHeight(root, height);
        Yoga.YGNodeStyleSetMinWidth(root, width);
        Yoga.YGNodeStyleSetMinHeight(root, height);
        Yoga.YGNodeStyleSetMaxWidth(root, width);
        Yoga.YGNodeStyleSetMaxHeight(root, height);
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("YogaLayoutEngine is owned by thread "
                    + ownerThread.getName() + " but was accessed from " + Thread.currentThread().getName());
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("YogaLayoutEngine is closed");
    }

    private static UiLayoutException nodeFailure(UiNode node, String action, Throwable cause) {
        String debugName = node.debugName().isBlank() ? "<unnamed>" : node.debugName();
        String message = action + " [nodeId=" + node.id().value() + ", debugName=" + debugName + ']';
        return cause == null ? new UiLayoutException(message) : new UiLayoutException(message, cause);
    }

    private static RuntimeException appendFailure(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private static List<NativeNode> reverseTreeOrder(List<NativeNode> source) {
        ArrayList<NativeNode> ordered = new ArrayList<>(source);
        ordered.sort((left, right) -> {
            int depthOrder = Integer.compare(right.depth, left.depth);
            return depthOrder != 0
                    ? depthOrder
                    : Long.compare(right.allocationSequence, left.allocationSequence);
        });
        return ordered;
    }

    @FunctionalInterface
    private interface FloatSetter {
        void set(float value);
    }

    private enum Dimension {
        WIDTH, HEIGHT, MIN_WIDTH, MIN_HEIGHT, MAX_WIDTH, MAX_HEIGHT
    }

    private record TreeEntry(UiNode node, UiNode parent, int indexInParent, int depth, boolean root,
                             UiStyle style, ComputedStyle computedStyle,
                             UiVisibility visibility, List<UiNode> children) {
    }

    private record MeasureFailure(UiNode node, Throwable cause) {
    }

    private static final class NativeNode {
        private final UiNode node;
        private final long handle;
        private final long allocationSequence;
        private YGMeasureFunc measureCallback;
        private int depth;

        private NativeNode(UiNode node, long handle, long allocationSequence) {
            this.node = node;
            this.handle = handle;
            this.allocationSequence = allocationSequence;
        }
    }
}
