package com.kaleblangley.haikalat.runtime;

/** 全 GPU 自动曝光的不可变配置。 */
public final class AutoExposureSettings {
    private final float minExposure;
    private final float maxExposure;
    private final float keyValue;
    private final float brightenSpeed;
    private final float darkenSpeed;

    private AutoExposureSettings(Builder builder) {
        minExposure = builder.minExposure;
        maxExposure = builder.maxExposure;
        keyValue = builder.keyValue;
        brightenSpeed = builder.brightenSpeed;
        darkenSpeed = builder.darkenSpeed;
    }

    /** @return 使用 18% 中灰和保守适应速度的默认配置 */
    public static AutoExposureSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public float minExposure() {
        return minExposure;
    }

    public float maxExposure() {
        return maxExposure;
    }

    public float keyValue() {
        return keyValue;
    }

    public float brightenSpeed() {
        return brightenSpeed;
    }

    public float darkenSpeed() {
        return darkenSpeed;
    }

    /** 自动曝光配置构建器。 */
    public static final class Builder {
        private float minExposure = 0.25f;
        private float maxExposure = 4.0f;
        private float keyValue = 0.18f;
        private float brightenSpeed = 1.5f;
        private float darkenSpeed = 3.0f;

        private Builder() {
        }

        public Builder minExposure(float value) {
            minExposure = value;
            return this;
        }

        public Builder maxExposure(float value) {
            maxExposure = value;
            return this;
        }

        public Builder keyValue(float value) {
            keyValue = value;
            return this;
        }

        public Builder brightenSpeed(float value) {
            brightenSpeed = value;
            return this;
        }

        public Builder darkenSpeed(float value) {
            darkenSpeed = value;
            return this;
        }

        public AutoExposureSettings build() {
            requirePositiveFinite(minExposure, "minExposure");
            requirePositiveFinite(maxExposure, "maxExposure");
            requirePositiveFinite(keyValue, "keyValue");
            requirePositiveFinite(brightenSpeed, "brightenSpeed");
            requirePositiveFinite(darkenSpeed, "darkenSpeed");
            if (minExposure > maxExposure) {
                throw new IllegalArgumentException("minExposure must not exceed maxExposure");
            }
            return new AutoExposureSettings(this);
        }

        private static void requirePositiveFinite(float value, String name) {
            if (!Float.isFinite(value) || value <= 0.0f) {
                throw new IllegalArgumentException(name + " must be finite and positive");
            }
        }
    }
}
