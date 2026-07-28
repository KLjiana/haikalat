package com.kaleblangley.haikalat.subsystems.ui;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Package-private storage for UI dirty domains. */
final class UiDirtyState {
    private final EnumSet<UiDirtyFlag> flags = EnumSet.allOf(UiDirtyFlag.class);

    boolean contains(UiDirtyFlag flag) {
        return flags.contains(Objects.requireNonNull(flag, "flag"));
    }

    void add(UiDirtyFlag... values) {
        for (UiDirtyFlag value : values) {
            flags.add(Objects.requireNonNull(value, "flag"));
        }
    }

    void remove(UiDirtyFlag... values) {
        for (UiDirtyFlag value : values) {
            flags.remove(Objects.requireNonNull(value, "flag"));
        }
    }

    Set<UiDirtyFlag> snapshot() {
        return Set.copyOf(flags);
    }
}
