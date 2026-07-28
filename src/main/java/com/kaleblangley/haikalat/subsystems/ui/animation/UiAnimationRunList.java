package com.kaleblangley.haikalat.subsystems.ui.animation;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Reusable owner-thread run storage.
 *
 * <p>Index-based removal keeps update loops free of iterator allocations while preserving
 * declaration order. Capacity is retained between frames.</p>
 */
final class UiAnimationRunList<E> {
    private final ArrayList<E> values = new ArrayList<>();

    void add(E value) {
        values.add(Objects.requireNonNull(value, "value"));
    }

    E get(int index) {
        return values.get(index);
    }

    E removeAt(int index) {
        return values.remove(index);
    }

    int size() {
        return values.size();
    }

    void clear() {
        values.clear();
    }
}
