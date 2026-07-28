package com.kaleblangley.haikalat.subsystems.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Package-private parent/child storage and tree invariants for {@link UiNode}.
 *
 * <p>Child order is the stable insertion order of the backing list. Published child
 * views are immutable snapshots and are invalidated only when the list changes.</p>
 */
final class UiTreeLinks {
    private final UiNode owner;
    private final List<UiNode> children = new ArrayList<>();
    private List<UiNode> childrenSnapshot = List.of();
    private UiDocument document;
    private UiNode parent;
    private boolean root;

    UiTreeLinks(UiNode owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    UiDocument document() {
        return document;
    }

    UiNode parent() {
        return parent;
    }

    boolean isRoot() {
        return root;
    }

    List<UiNode> children() {
        if (childrenSnapshot == null) childrenSnapshot = List.copyOf(children);
        return childrenSnapshot;
    }

    List<UiNode> mutableChildren() {
        return children;
    }

    void prepareAdd(UiNode child) {
        UiTreeLinks childLinks = child.treeLinks();
        if (childLinks.root) {
            throw new IllegalArgumentException("UI document roots cannot be reparented");
        }
        if (childLinks.parent != null) {
            throw new IllegalArgumentException("UI node " + child.id()
                    + " already has parent " + childLinks.parent.id());
        }
        for (UiNode ancestor = owner; ancestor != null; ancestor = ancestor.treeLinks().parent) {
            if (ancestor == child) {
                throw new IllegalArgumentException("UI reparent would form a cycle at " + child.id());
            }
        }
        if (document != null) childLinks.attachTo(document);
    }

    void appendPrepared(UiNode child) {
        child.treeLinks().parent = owner;
        children.add(child);
        childrenSnapshot = null;
    }

    void requireChild(UiNode child) {
        if (child.treeLinks().parent != owner) {
            throw new IllegalArgumentException("UI node " + child.id()
                    + " is not a child of " + owner.id());
        }
    }

    void remove(UiNode child) {
        children.remove(child);
        childrenSnapshot = null;
        child.treeLinks().parent = null;
    }

    void initializeRoot(UiDocument ownerDocument) {
        root = true;
        attachTo(ownerDocument);
    }

    void attachTo(UiDocument ownerDocument) {
        Objects.requireNonNull(ownerDocument, "ownerDocument");
        if (document != null && document != ownerDocument) {
            throw new IllegalArgumentException("UI node " + owner.id()
                    + " belongs to another document");
        }
        document = ownerDocument;
        for (UiNode child : children) child.treeLinks().attachTo(ownerDocument);
    }

    void closeChildren() {
        for (int index = children.size() - 1; index >= 0; index--) {
            children.get(index).closeSubtree();
        }
        children.clear();
        childrenSnapshot = List.of();
        parent = null;
    }
}
