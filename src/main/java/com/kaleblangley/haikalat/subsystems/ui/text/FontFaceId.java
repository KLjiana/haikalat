package com.kaleblangley.haikalat.subsystems.ui.text;

/** 字体 face 在一个字体管理器生命周期内的稳定标识。 */
public record FontFaceId(long value) implements Comparable<FontFaceId> {
    public FontFaceId {
        if (value <= 0L) {
            throw new IllegalArgumentException("Font face id must be positive");
        }
    }

    @Override
    public int compareTo(FontFaceId other) {
        return Long.compare(value, other.value);
    }
}
