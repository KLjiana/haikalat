package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.event.EventPhase;
import com.kaleblangley.haikalat.subsystems.ui.event.FocusManager;
import com.kaleblangley.haikalat.subsystems.ui.event.PointerCapture;
import com.kaleblangley.haikalat.subsystems.ui.event.PointerEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 一棵普通 root 与一棵 overlay root 的单线程 UI 文档。 */
public final class UiDocument implements AutoCloseable {
    private final Panel root = new Panel();
    private final Panel overlayRoot = new Panel();
    private final ArrayDeque<Runnable> mutationQueue = new ArrayDeque<>();
    private final FocusManager focusManager = new FocusManager(this);
    private final PointerCapture pointerCapture = new PointerCapture(this);
    private int dispatchDepth;
    private boolean drainingMutations;
    private boolean closing;
    private boolean closed;
    private long lifecycleEventSequence;

    public UiDocument() {
        root.debugName("UiRoot");
        overlayRoot.debugName("UiOverlayRoot");
        overlayRoot.hitTestVisible(false);
        ((UiNode) root).initializeRoot(this);
        ((UiNode) overlayRoot).initializeRoot(this);
    }

    public Panel root() {
        ensureOpen();
        return root;
    }

    public Panel overlayRoot() {
        ensureOpen();
        return overlayRoot;
    }

    public FocusManager focusManager() {
        ensureOpen();
        return focusManager;
    }

    public PointerCapture pointerCapture() {
        ensureOpen();
        return pointerCapture;
    }

    /**
     * 沿分派开始时冻结的路径执行 capture、target 和 bubble。
     * 默认行为先在 target 执行，再跟随 bubble 逐级执行，因此组合控件能够处理其子节点命中的事件；
     * {@link UiEvent#preventDefault()} 会阻止尚未执行的默认行为，但不会单独终止传播。
     * listener 触发的树修改会在整条路径结束后按提交顺序执行。
     */
    public void dispatch(UiNode target, UiEvent event) {
        ensureOpen();
        List<UiNode> path = pathTo(target);
        event.beginDispatch(target);
        beginDispatch();
        boolean committed = false;
        try {
            for (int index = 0; index + 1 < path.size() && !event.propagationStopped(); index++) {
                UiNode current = path.get(index);
                event.enter(current, EventPhase.CAPTURE);
                current.dispatchListeners(event, true);
            }
            if (!event.propagationStopped()) {
                event.enter(target, EventPhase.TARGET);
                target.dispatchListeners(event, true);
                if (!event.propagationStopped()) target.dispatchListeners(event, false);
                if (!event.defaultPrevented()) target.dispatchDefault(event);
            }
            for (int index = path.size() - 2; index >= 0 && !event.propagationStopped(); index--) {
                UiNode current = path.get(index);
                event.enter(current, EventPhase.BUBBLE);
                current.dispatchListeners(event, false);
                if (!event.propagationStopped() && !event.defaultPrevented()) {
                    current.dispatchDefault(event);
                }
            }
            committed = true;
        } finally {
            endDispatch(committed);
        }
        if (event.releasePointerId() != null) pointerCapture.release(event.releasePointerId());
        UiNode captureRequester = event.capturePointerRequester();
        if (event.capturePointerId() != null && isOpenMember(captureRequester)) {
            pointerCapture.capture(event.capturePointerId(), captureRequester);
        }
        UiNode focusRequester = event.focusRequester();
        if (event.focusRequested() && isOpenMember(focusRequester)) {
            focusManager.requestFocus(focusRequester);
        }
    }

    public List<UiNode> paintOrder() {
        ensureOpen();
        List<UiNode> result = new ArrayList<>();
        appendVisible(root, result);
        appendVisible(overlayRoot, result);
        return List.copyOf(result);
    }

    /** 按 paint order 逆序命中最上层节点，同时服从祖先裁剪。 */
    public UiNode hitTest(double x, double y) {
        ensureOpen();
        UiNode overlay = hitTest(overlayRoot, x, y, null, 0.0, 0.0);
        return overlay != null ? overlay : hitTest(root, x, y, null, 0.0, 0.0);
    }

    /** 返回叠加所有祖先 scroll/transform 后的逻辑可视矩形。 */
    public LayoutBox visualLayoutBox(UiNode node) {
        ensureOpen();
        if (node == null || node.document() != this || node.isClosed()) {
            throw new IllegalArgumentException("visual layout node must be an open document member");
        }
        double offsetX = 0.0;
        double offsetY = 0.0;
        for (UiNode parent = node.parent(); parent != null; parent = parent.parent()) {
            offsetX += parent.childVisualOffsetX();
            offsetY += parent.childVisualOffsetY();
        }
        LayoutBox box = node.layoutBox();
        return new LayoutBox((float) (box.x() + offsetX), (float) (box.y() + offsetY),
                box.width(), box.height());
    }

    public List<UiNode> pathTo(UiNode node) {
        ensureOpen();
        if (node == null || node.document() != this) {
            throw new IllegalArgumentException("UI event target does not belong to this document");
        }
        ArrayDeque<UiNode> path = new ArrayDeque<>();
        for (UiNode current = node; current != null; current = current.parent()) {
            path.addFirst(current);
        }
        return List.copyOf(path);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closing = true;
        RuntimeException failure = null;
        try {
            ((UiNode) overlayRoot).closeSubtree();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            ((UiNode) root).closeSubtree();
        } catch (RuntimeException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        mutationQueue.clear();
        pointerCapture.releaseAll();
        closed = true;
        closing = false;
        if (failure != null) throw failure;
    }

    public void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("UiDocument is closed");
        }
    }

    boolean isClosing() {
        return closing;
    }

    boolean dispatching() {
        return dispatchDepth > 0;
    }

    void beginDispatch() {
        ensureOpen();
        dispatchDepth++;
    }

    void endDispatch(boolean commitMutations) {
        if (--dispatchDepth < 0) {
            dispatchDepth = 0;
            throw new IllegalStateException("unbalanced UI event dispatch");
        }
        if (dispatchDepth != 0) return;
        if (!commitMutations) {
            mutationQueue.clear();
            return;
        }
        if (drainingMutations) return;
        drainingMutations = true;
        try {
            int guard = 0;
            while (!mutationQueue.isEmpty()) {
                if (++guard > 10_000) {
                    throw new IllegalStateException("UI mutation queue exceeded safety limit");
                }
                mutationQueue.removeFirst().run();
            }
        } catch (RuntimeException | Error failure) {
            mutationQueue.clear();
            throw failure;
        } finally {
            drainingMutations = false;
        }
    }

    void enqueueMutation(Runnable mutation) {
        mutationQueue.addLast(mutation);
    }

    void beforeSubtreeDetached(UiNode subtree) {
        UiNode captured = pointerCapture.target(PointerEvent.MOUSE_POINTER_ID);
        if (captured != null && isDescendantOrSelf(captured, subtree)) {
            dispatch(captured, new PointerEvent(UiEventType.POINTER_CANCEL, System.nanoTime(),
                    ++lifecycleEventSequence, KeyModifiers.NONE, PointerEvent.MOUSE_POINTER_ID,
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, 0));
        }
        pointerCapture.releaseSubtree(subtree);
        focusManager.clearSubtree(subtree);
    }

    private static void appendVisible(UiNode node, List<UiNode> output) {
        if (node.visibility() != UiVisibility.VISIBLE) return;
        output.add(node);
        for (UiNode child : node.children()) appendVisible(child, output);
    }

    private static UiNode hitTest(UiNode node, double x, double y, LayoutBox inheritedClip,
                                  double offsetX, double offsetY) {
        if (node.visibility() != UiVisibility.VISIBLE) return null;
        LayoutBox raw = node.layoutBox();
        LayoutBox box = new LayoutBox((float) (raw.x() + offsetX), (float) (raw.y() + offsetY),
                raw.width(), raw.height());
        LayoutBox clip = inheritedClip;
        if (node.clipChildren()) {
            clip = clip == null ? box : clip.intersect(box);
        }
        if (clip != null && !clip.contains(x, y)) return null;
        List<UiNode> children = node.children();
        double childOffsetX = offsetX + node.childVisualOffsetX();
        double childOffsetY = offsetY + node.childVisualOffsetY();
        for (int index = children.size() - 1; index >= 0; index--) {
            UiNode hit = hitTest(children.get(index), x, y, clip, childOffsetX, childOffsetY);
            if (hit != null) return hit;
        }
        return node.hitTestVisible() && box.contains(x, y) ? node : null;
    }

    private static boolean isDescendantOrSelf(UiNode node, UiNode root) {
        for (UiNode current = node; current != null; current = current.parent()) {
            if (current == root) return true;
        }
        return false;
    }

    private boolean isOpenMember(UiNode node) {
        return node != null && !node.isClosed() && node.document() == this;
    }
}
