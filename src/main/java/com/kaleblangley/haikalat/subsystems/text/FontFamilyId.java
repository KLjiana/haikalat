package com.kaleblangley.haikalat.subsystems.text;

/** 字体家族在一个字体管理器生命周期内的稳定标识。 */
public record FontFamilyId(long value) implements Comparable<FontFamilyId> {
    public FontFamilyId {
        if (value <= 0L) {
            throw new IllegalArgumentException("Font family id must be positive");
        }
    }

    @Override
    public int compareTo(FontFamilyId other) {
        return Long.compare(value, other.value);
    }
}
