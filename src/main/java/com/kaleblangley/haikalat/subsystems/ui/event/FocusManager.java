package com.kaleblangley.haikalat.subsystems.ui.event;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 管理单个 UI document 的唯一键盘焦点与稳定 Tab 顺序。 */
public final class FocusManager {
    private final UiDocument document;
    private UiNode focused;
    private long eventSequence;

    public FocusManager(UiDocument document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    public UiNode focused() {
        pruneInvalid();
        return focused;
    }

    public boolean requestFocus(UiNode node) {
        if (node != null && !canFocus(node)) return false;
        pruneInvalid();
        if (focused == node) return true;
        UiNode previous = focused;
        focused = node;
        long now = System.nanoTime();
        if (previous != null && !previous.isClosed() && previous.document() == document) {
            document.dispatch(previous, new FocusEvent(UiEventType.FOCUS_LOST, now,
                    ++eventSequence, node));
        }
        if (node != null && !node.isClosed() && node.document() == document) {
            document.dispatch(node, new FocusEvent(UiEventType.FOCUS_GAINED, now,
                    ++eventSequence, previous));
        }
        return true;
    }

    public boolean focusNext(boolean reverse) {
        List<UiNode> candidates = new ArrayList<>();
        for (UiNode node : document.paintOrder()) {
            if (canFocus(node)) candidates.add(node);
        }
        if (candidates.isEmpty()) return requestFocus(null);
        int index = candidates.indexOf(focused());
        int next;
        if (reverse) next = index <= 0 ? candidates.size() - 1 : index - 1;
        else next = index < 0 || index + 1 >= candidates.size() ? 0 : index + 1;
        return requestFocus(candidates.get(next));
    }

    public void clearSubtree(UiNode root) {
        if (focused != null && isDescendantOrSelf(focused, root)) requestFocus(null);
    }

    private boolean canFocus(UiNode node) {
        return node.document() == document && !node.isClosed() && node.focusable() && node.enabled()
                && node.visibility() == UiVisibility.VISIBLE;
    }

    private void pruneInvalid() {
        if (focused != null && !canFocus(focused)) focused = null;
    }

    private static boolean isDescendantOrSelf(UiNode node, UiNode root) {
        for (UiNode current = node; current != null; current = current.parent()) {
            if (current == root) return true;
        }
        return false;
    }
}
