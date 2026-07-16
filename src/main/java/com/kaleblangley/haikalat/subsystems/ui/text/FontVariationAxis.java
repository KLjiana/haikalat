package com.kaleblangley.haikalat.subsystems.ui.text;

import java.util.Objects;

/**
 * OpenType 可变字体轴的设计坐标范围。
 *
 * @param tag 四字符 OpenType 轴标签，例如 {@code wght}
 * @param minimum 最小设计坐标
 * @param defaultValue 字体文件声明的默认设计坐标
 * @param maximum 最大设计坐标
 */
public record FontVariationAxis(
        String tag,
        float minimum,
        float defaultValue,
        float maximum) {

    public FontVariationAxis {
        Objects.requireNonNull(tag, "tag");
        if (tag.length() != 4 || tag.chars().anyMatch(value -> value < 0x20 || value > 0x7e)) {
            throw new IllegalArgumentException("variation axis tag must contain four ASCII characters");
        }
        if (!Float.isFinite(minimum) || !Float.isFinite(defaultValue)
                || !Float.isFinite(maximum) || minimum > defaultValue || defaultValue > maximum) {
            throw new IllegalArgumentException("variation axis range must be finite and ordered");
        }
    }
}
