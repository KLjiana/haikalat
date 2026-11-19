package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.AntiAliasingMode;

public final class RenderSettings {
    /**
     * Smallest accepted reduced scene-resolution ratio.  Below this the loss of
     * scene detail is not recoverable by the bounded reconstruction slice.
     */
    public static final float MIN_RENDER_SCALE = 0.25f;
    /** Largest accepted ratio; one is the exact full-resolution no-op. */
    public static final float MAX_RENDER_SCALE = 1.0f;

    private final boolean vsync;
    private final AntiAliasingMode antiAliasingMode;
    private final int msaaSamples;
    private final ToneMappingMode toneMappingMode;
    private final float exposure;
    private final ExposureMode exposureMode;
    private final AutoExposureSettings autoExposureSettings;
    private final BloomSettings bloomSettings;
    private final boolean sceneVisibility;
    private final float renderScale;

    private RenderSettings(Builder builder) {
        this.vsync = builder.vsync;
        this.antiAliasingMode = builder.antiAliasingMode;
        this.msaaSamples = builder.msaaSamples;
        this.toneMappingMode = builder.toneMappingMode;
        this.exposure = builder.exposure;
        this.exposureMode = builder.exposureMode;
        this.autoExposureSettings = builder.autoExposureSettings;
        this.bloomSettings = builder.bloomSettings;
        this.sceneVisibility = builder.sceneVisibility;
        this.renderScale = builder.renderScale;
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

    public ExposureMode exposureMode() {
        return exposureMode;
    }

    public AutoExposureSettings autoExposureSettings() {
        return autoExposureSettings;
    }

    public BloomSettings bloomSettings() {
        return bloomSettings;
    }


    /** @return 普通 scene renderer 是否启用相机/阴影视锥裁剪 */
    public boolean sceneVisibility() {
        return sceneVisibility;
    }

    /**
     * The requested scene-to-target resolution ratio.
     *
     * <p>{@code 1.0} is the default and the exact no-op: the scene extent equals
     * the target extent and every existing composition is unchanged.  A value
     * below one rasterises the scene at
     * {@code max(1, round(target * renderScale))} and reconstructs the target
     * extent through the internal temporal source; the single rounding rule
     * lives in {@code TemporalUpscalingSettings.scaledExtent(int, float)}.
     * Compositions that still require full-resolution scene data are rejected by
     * the feature policy instead of being rendered incorrectly.</p>
     *
     * @return the validated ratio in {@code [0.25, 1.0]}
     */
    public float renderScale() {
        return renderScale;
    }

    /** @return 当前设置是否需要线性 HDR 中间目标 */
    public boolean hdrEnabled() {
        return toneMappingMode != ToneMappingMode.NONE;
    }

    /** Returns a validated snapshot with the profile-controlled values replaced. */
    public RenderSettings withVisualSettings(float manualExposure, BloomSettings bloom,
                                             AntiAliasingMode antiAliasing) {
        return builder().vsync(vsync).antiAliasingMode(antiAliasing).msaaSamples(msaaSamples)
                .toneMappingMode(toneMappingMode).exposure(manualExposure)
                .exposureMode(exposureMode).autoExposureSettings(autoExposureSettings)
                .bloomSettings(bloom).sceneVisibility(sceneVisibility)
                .renderScale(renderScale).build();
    }

    /**
     * Returns a validated snapshot with only the scene resolution ratio
     * replaced.  Every other component is preserved.
     */
    public RenderSettings withRenderScale(float value) {
        return builder().vsync(vsync).antiAliasingMode(antiAliasingMode)
                .msaaSamples(msaaSamples).toneMappingMode(toneMappingMode)
                .exposure(exposure).exposureMode(exposureMode)
                .autoExposureSettings(autoExposureSettings).bloomSettings(bloomSettings)
                .sceneVisibility(sceneVisibility).renderScale(value).build();
    }

    public static final class Builder {
        private boolean vsync = true;
        private AntiAliasingMode antiAliasingMode = AntiAliasingMode.NONE;
        private int msaaSamples = 4;
        private ToneMappingMode toneMappingMode = ToneMappingMode.NONE;
        private float exposure = 1.0f;
        private ExposureMode exposureMode = ExposureMode.MANUAL;
        private AutoExposureSettings autoExposureSettings = AutoExposureSettings.defaults();
        private BloomSettings bloomSettings = BloomSettings.defaults();
        private boolean sceneVisibility = true;
        private float renderScale = MAX_RENDER_SCALE;

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

        public Builder exposureMode(ExposureMode value) {
            exposureMode = value;
            return this;
        }

        public Builder autoExposureSettings(AutoExposureSettings value) {
            autoExposureSettings = value;
            return this;
        }

        public Builder bloomSettings(BloomSettings value) {
            bloomSettings = value;
            return this;
        }


        /** 开启或关闭普通 renderer 裁剪；关闭时仍经过同一 SceneFrame 路径。 */
        public Builder sceneVisibility(boolean value) {
            sceneVisibility = value;
            return this;
        }

        /**
         * Requests a reduced scene resolution in {@code [0.25, 1.0]}; one is the
         * default and the exact no-op.
         */
        public Builder renderScale(float value) {
            renderScale = value;
            return this;
        }

        public RenderSettings build() {
            if (antiAliasingMode == null) {
                throw new NullPointerException("antiAliasingMode");
            }
            if (toneMappingMode == null) {
                throw new NullPointerException("toneMappingMode");
            }
            if (antiAliasingMode == AntiAliasingMode.SMAA
                    && toneMappingMode == ToneMappingMode.NONE) {
                throw new IllegalStateException("SMAA requires HDR tone mapping");
            }
            if (!Float.isFinite(exposure) || exposure <= 0.0f) {
                throw new IllegalArgumentException("exposure must be finite and positive");
            }
            if (!Float.isFinite(renderScale) || renderScale < MIN_RENDER_SCALE
                    || renderScale > MAX_RENDER_SCALE) {
                throw new IllegalArgumentException("renderScale must be finite and in ["
                        + MIN_RENDER_SCALE + ", " + MAX_RENDER_SCALE + "]");
            }
            if (exposureMode == null) {
                throw new NullPointerException("exposureMode");
            }
            if (autoExposureSettings == null) {
                throw new NullPointerException("autoExposureSettings");
            }
            if (exposureMode == ExposureMode.AUTO && toneMappingMode == ToneMappingMode.NONE) {
                throw new IllegalStateException("Automatic exposure requires HDR tone mapping");
            }
            if (bloomSettings == null) {
                throw new NullPointerException("bloomSettings");
            }
            if (bloomSettings.enabled() && toneMappingMode == ToneMappingMode.NONE) {
                throw new IllegalStateException("Bloom requires HDR tone mapping");
            }
            return new RenderSettings(this);
        }
    }
}
