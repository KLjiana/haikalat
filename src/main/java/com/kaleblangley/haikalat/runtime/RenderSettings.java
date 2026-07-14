package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.AntiAliasingMode;

public final class RenderSettings {
    private final boolean vsync;
    private final AntiAliasingMode antiAliasingMode;
    private final int msaaSamples;
    private final ToneMappingMode toneMappingMode;
    private final float exposure;

    private RenderSettings(Builder builder) {
        this.vsync = builder.vsync;
        this.antiAliasingMode = builder.antiAliasingMode;
        this.msaaSamples = builder.msaaSamples;
        this.toneMappingMode = builder.toneMappingMode;
        this.exposure = builder.exposure;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean vsync() {
        return vsync;
    }

    public AntiAliasingMode antiAliasingMode() {
        return antiAliasingMode;
    }

    public int msaaSamples() {
        return msaaSamples;
    }

    public ToneMappingMode toneMappingMode() {
        return toneMappingMode;
    }

    public float exposure() {
        return exposure;
    }

    /** @return 当前设置是否需要线性 HDR 中间目标 */
    public boolean hdrEnabled() {
        return toneMappingMode != ToneMappingMode.NONE;
    }

    public static final class Builder {
        private boolean vsync = true;
        private AntiAliasingMode antiAliasingMode = AntiAliasingMode.NONE;
        private int msaaSamples = 4;
        private ToneMappingMode toneMappingMode = ToneMappingMode.NONE;
        private float exposure = 1.0f;

        private Builder() {
        }

        public Builder vsync(boolean value) {
            this.vsync = value;
            return this;
        }

        public Builder antiAliasingMode(AntiAliasingMode value) {
            this.antiAliasingMode = value;
            return this;
        }

        public Builder msaaSamples(int value) {
            this.msaaSamples = value;
            return this;
        }

        public Builder toneMappingMode(ToneMappingMode value) {
            this.toneMappingMode = value;
            return this;
        }

        public Builder exposure(float value) {
            this.exposure = value;
            return this;
        }

        public RenderSettings build() {
            if (antiAliasingMode == null) {
                throw new NullPointerException("antiAliasingMode");
            }
            if (toneMappingMode == null) {
                throw new NullPointerException("toneMappingMode");
            }
            if (!Float.isFinite(exposure) || exposure <= 0.0f) {
                throw new IllegalArgumentException("exposure must be finite and positive");
            }
            return new RenderSettings(this);
        }
    }
}
