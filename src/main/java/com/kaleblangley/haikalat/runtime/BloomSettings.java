package com.kaleblangley.haikalat.runtime;

/**
 * Bloom 后处理的不可变配置。
 *
 * @param enabled    是否启用 Bloom；默认关闭
 * @param threshold  线性 HDR 亮度提取阈值
 * @param softKnee   阈值附近的柔和过渡比例，范围为 0～1
 * @param intensity  tone mapping 合成时的 Bloom 强度
 * @param maxLevels  最大降采样层数，范围为 1～8
 */
public record BloomSettings(
        boolean enabled,
        float threshold,
        float softKnee,
        float intensity,
        int maxLevels
) {
    public BloomSettings {
        if (!Float.isFinite(threshold) || threshold < 0.0f) {
            throw new IllegalArgumentException("bloom threshold must be finite and non-negative");
        }
        if (!Float.isFinite(softKnee) || softKnee < 0.0f || softKnee > 1.0f) {
            throw new IllegalArgumentException("bloom softKnee must be within [0, 1]");
        }
        if (!Float.isFinite(intensity) || intensity < 0.0f) {
            throw new IllegalArgumentException("bloom intensity must be finite and non-negative");
        }
        if (maxLevels < 1 || maxLevels > 8) {
            throw new IllegalArgumentException("bloom maxLevels must be within [1, 8]");
        }
    }

    /** @return 默认关闭的 Bloom 配置 */
    public static BloomSettings defaults() {
        return new BloomSettings(false, 1.0f, 0.5f, 0.08f, 3);
    }

    /** @return 使用默认参数且明确关闭的 Bloom 配置 */
    public static BloomSettings disabled() {
        return defaults();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Bloom 配置构建器。 */
    public static final class Builder {
        private boolean enabled;
        private float threshold = 1.0f;
        private float softKnee = 0.5f;
        private float intensity = 0.08f;
        private int maxLevels = 3;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder threshold(float value) {
            threshold = value;
            return this;
        }

        public Builder softKnee(float value) {
            softKnee = value;
            return this;
        }

        public Builder intensity(float value) {
            intensity = value;
            return this;
        }

        public Builder maxLevels(int value) {
            maxLevels = value;
            return this;
        }

        public BloomSettings build() {
            return new BloomSettings(enabled, threshold, softKnee, intensity, maxLevels);
        }
    }
}
