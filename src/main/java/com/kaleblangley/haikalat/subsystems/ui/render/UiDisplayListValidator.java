package com.kaleblangley.haikalat.subsystems.ui.render;

/** Central validation policy for mutable/frozen UI display-list storage. */
final class UiDisplayListValidator {
    private UiDisplayListValidator() {
    }

    static void validateComplete(boolean glyphRunOpen, int clipDepth,
                                 int transformDepth, int layerDepth) {
        if (glyphRunOpen) {
            throw new IllegalStateException("glyph run is still active");
        }
        if (clipDepth != 0) {
            throw new IllegalStateException("clip stack is not balanced: depth=" + clipDepth);
        }
        if (transformDepth != 0) {
            throw new IllegalStateException("transform stack is not balanced: depth="
                    + transformDepth);
        }
        if (layerDepth != 0) {
            throw new IllegalStateException("layer stack is not balanced: depth=" + layerDepth);
        }
    }

    static void ensureMutable(boolean frozen) {
        if (frozen) {
            throw new IllegalStateException("frozen UI display list is immutable");
        }
    }

    static void ensureRecordable(boolean frozen, boolean glyphRunOpen) {
        ensureMutable(frozen);
        if (glyphRunOpen) {
            throw new IllegalStateException("finish or abort the active glyph run first");
        }
    }

    static void requireResource(int id, String name) {
        if (id < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    static void checkIndex(int index, int count, String kind) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(kind + " index: " + index);
        }
    }
}
