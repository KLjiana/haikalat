package com.kaleblangley.haikalat.subsystems.text;

import java.util.Locale;
import java.util.Objects;

/** 应用于完整 shaping run 的 OpenType feature 值。 */
public record OpenTypeFeature(String tag, int value) implements Comparable<OpenTypeFeature> {
    public OpenTypeFeature {
        tag = normalizeTag(tag);
        if (value < 0) {
            throw new IllegalArgumentException("OpenType feature value must be non-negative");
        }
    }

    /** 创建值为 1 的启用 feature。 */
    public static OpenTypeFeature enabled(String tag) {
        return new OpenTypeFeature(tag, 1);
    }

    /** 创建值为 0 的禁用 feature。 */
    public static OpenTypeFeature disabled(String tag) {
        return new OpenTypeFeature(tag, 0);
    }

    @Override
    public int compareTo(OpenTypeFeature other) {
        int tagOrder = tag.compareTo(other.tag);
        return tagOrder != 0 ? tagOrder : Integer.compare(value, other.value);
    }

    private static String normalizeTag(String value) {
        String normalized = Objects.requireNonNull(value, "tag").toLowerCase(Locale.ROOT);
        if (normalized.length() != 4) {
            throw new IllegalArgumentException("OpenType feature tag must contain exactly four ASCII characters");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character < 0x20 || character > 0x7E) {
                throw new IllegalArgumentException("OpenType feature tag must be printable ASCII: " + value);
            }
        }
        return normalized;
    }
}
