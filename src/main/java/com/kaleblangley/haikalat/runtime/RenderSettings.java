package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.AntiAliasingMode;

public final class RenderSettings {
    private final boolean vsync;
    private final AntiAliasingMode antiAliasingMode;
    private final int msaaSamples;

    private RenderSettings(Builder builder) {
        this.vsync = builder.vsync;
        this.antiAliasingMode = builder.antiAliasingMode;
        this.msaaSamples = builder.msaaSamples;
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

    public static final class Builder {
        private boolean vsync = true;
        private AntiAliasingMode antiAliasingMode = AntiAliasingMode.NONE;
        private int msaaSamples = 4;

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

        public RenderSettings build() {
            return new RenderSettings(this);
        }
    }
}
