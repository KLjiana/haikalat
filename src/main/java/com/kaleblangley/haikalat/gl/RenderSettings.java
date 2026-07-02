package com.kaleblangley.haikalat.gl;

public final class RenderSettings {
    private final boolean vsync;
    private final boolean debugErrors;
    private final AntiAliasingMode antiAliasingMode;
    private final int msaaSamples;

    private RenderSettings(Builder builder) {
        this.vsync = builder.vsync;
        this.debugErrors = builder.debugErrors;
        this.antiAliasingMode = builder.antiAliasingMode;
        this.msaaSamples = builder.msaaSamples;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean vsync() {
        return vsync;
    }

    public boolean debugErrors() {
        return debugErrors;
    }

    public AntiAliasingMode antiAliasingMode() {
        return antiAliasingMode;
    }

    public int msaaSamples() {
        return msaaSamples;
    }

    public static final class Builder {
        private boolean vsync = true;
        private boolean debugErrors;
        private AntiAliasingMode antiAliasingMode = AntiAliasingMode.NONE;
        private int msaaSamples = 4;

        private Builder() {
        }

        public Builder vsync(boolean value) {
            this.vsync = value;
            return this;
        }

        public Builder debugErrors(boolean value) {
            this.debugErrors = value;
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
