package com.kaleblangley.haikalat.subsystems.text;

/** 字体注册表内容发生结构变化时递增的稳定 generation。 */
public record FontGeneration(long value) implements Comparable<FontGeneration> {
    public FontGeneration {
        if (value < 0L) {
            throw new IllegalArgumentException("Font generation must be non-negative");
        }
    }

    /** 返回下一代，溢出时明确失败而不是复用旧 generation。 */
    public FontGeneration next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("Font generation exhausted");
        }
        return new FontGeneration(value + 1L);
    }

    @Override
    public int compareTo(FontGeneration other) {
        return Long.compare(value, other.value);
    }
}
