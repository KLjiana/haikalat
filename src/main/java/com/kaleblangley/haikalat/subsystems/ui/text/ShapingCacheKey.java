package com.kaleblangley.haikalat.subsystems.ui.text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * shaping 结果的完整内容键。
 *
 * <p>键直接持有不可变文本内容，避免仅使用 hash 带来的碰撞。feature 顺序在构造时规范化，
 * 因而同一个 feature set 不会因调用顺序产生重复缓存项。</p>
 */
public record ShapingCacheKey(
        FontGeneration fontGeneration,
        FontFaceId faceId,
        int ppem,
        String text,
        TextDirection direction,
        String language,
        List<OpenTypeFeature> features) {

    public ShapingCacheKey {
        Objects.requireNonNull(fontGeneration, "fontGeneration");
        Objects.requireNonNull(faceId, "faceId");
        if (ppem <= 0) {
            throw new IllegalArgumentException("ppem must be positive");
        }
        text = Objects.requireNonNull(text, "text");
        direction = Objects.requireNonNull(direction, "direction");
        language = normalizeLanguage(language);
        features = canonicalFeatures(features);
    }

    /** 使用无 OpenType feature 的便捷构造。 */
    public ShapingCacheKey(FontGeneration fontGeneration, FontFaceId faceId, int ppem,
                           String text, TextDirection direction, String language) {
        this(fontGeneration, faceId, ppem, text, direction, language, List.of());
    }

    /** 使用原始 generation 数值的便捷构造。 */
    public ShapingCacheKey(long fontGeneration, FontFaceId faceId, int ppem,
                           String text, TextDirection direction, String language,
                           List<OpenTypeFeature> features) {
        this(new FontGeneration(fontGeneration), faceId, ppem, text, direction, language, features);
    }

    private static String normalizeLanguage(String language) {
        String input = Objects.requireNonNull(language, "language").trim();
        if (input.isEmpty() || input.equalsIgnoreCase("und")) {
            return "und";
        }
        String normalized = Locale.forLanguageTag(input).toLanguageTag();
        if (normalized.equals("und")) {
            throw new IllegalArgumentException("Invalid BCP 47 language tag: " + language);
        }
        return normalized;
    }

    private static List<OpenTypeFeature> canonicalFeatures(List<OpenTypeFeature> features) {
        Objects.requireNonNull(features, "features");
        List<OpenTypeFeature> result = new ArrayList<>(features.size());
        Set<String> tags = new HashSet<>();
        for (OpenTypeFeature feature : features) {
            OpenTypeFeature nonNullFeature = Objects.requireNonNull(feature, "feature");
            if (!tags.add(nonNullFeature.tag())) {
                throw new IllegalArgumentException("Duplicate OpenType feature tag: " + nonNullFeature.tag());
            }
            result.add(nonNullFeature);
        }
        result.sort(OpenTypeFeature::compareTo);
        return List.copyOf(result);
    }
}
