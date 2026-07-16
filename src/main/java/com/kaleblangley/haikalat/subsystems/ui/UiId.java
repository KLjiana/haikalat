package com.kaleblangley.haikalat.subsystems.ui;

/** UI 文档内外都不会复用的稳定节点标识。 */
public record UiId(long value) implements Comparable<UiId> {
    public UiId {
        if (value <= 0L) {
            throw new IllegalArgumentException("UI id must be positive");
        }
    }

    @Override
    public int compareTo(UiId other) {
        return Long.compare(value, other.value);
    }
}
