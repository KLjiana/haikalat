package com.kaleblangley.haikalat.subsystems.text;

import java.util.Objects;

/** 应用显式注册的字体家族；不依赖当前操作系统的字体搜索顺序。 */
public final class FontFamily {
    private final FontFamilyId id;
    private final String name;

    FontFamily(FontFamilyId id, String name) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
    }

    public FontFamilyId id() {
        return id;
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof FontFamily other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "FontFamily[id=" + id.value() + ", name=" + name + ']';
    }
}
