package com.kaleblangley.haikalat.subsystems.ui.event;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 一份 document 局部的 pointer capture 表。 */
public final class PointerCapture {
    private final UiDocument document;
    private final Map<Integer, UiNode> captured = new HashMap<>();

    public PointerCapture(UiDocument document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    public void capture(int pointerId, UiNode node) {
        validate(pointerId, node);
        captured.put(pointerId, node);
    }

    public UiNode target(int pointerId) {
        if (pointerId < 0) throw new IllegalArgumentException("pointerId must be non-negative");
        UiNode node = captured.get(pointerId);
        if (node != null && (node.isClosed() || node.document() != document)) {
            captured.remove(pointerId);
            return null;
        }
        return node;
    }

    public boolean release(int pointerId) {
        if (pointerId < 0) throw new IllegalArgumentException("pointerId must be non-negative");
        return captured.remove(pointerId) != null;
    }

    public void releaseAll() { captured.clear(); }

    public void releaseSubtree(UiNode root) {
        captured.entrySet().removeIf(entry -> isDescendantOrSelf(entry.getValue(), root));
    }

    private void validate(int pointerId, UiNode node) {
        if (pointerId < 0) throw new IllegalArgumentException("pointerId must be non-negative");
        Objects.requireNonNull(node, "node");
        if (node.isClosed() || node.document() != document) {
            throw new IllegalArgumentException("captured node must be an open member of this document");
        }
    }

    private static boolean isDescendantOrSelf(UiNode node, UiNode root) {
        for (UiNode current = node; current != null; current = current.parent()) {
            if (current == root) return true;
        }
        return false;
    }
}
