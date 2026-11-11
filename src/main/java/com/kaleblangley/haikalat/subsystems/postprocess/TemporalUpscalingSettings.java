package com.kaleblangley.haikalat.subsystems.postprocess;

/**
 * Bounded temporal-upscaling parameters for an independently rendered
 * low-resolution colour signal.
 *
 * <p>The feature reconstructs a higher-resolution frame from a low-resolution
 * colour/depth/velocity signal by reprojecting a double-buffered history,
 * rejecting stale samples and sharpening the resolved result.  It is a
 * reconstruction path of its own: it does not rename, replace or share the
 * native TAA resolve ({@code TemporalChain}) or the temporal denoiser
 * ({@code TemporalDenoisePass}).</p>
 *
 * <h2>Independent extents</h2>
 * <p>{@code renderScale} is the nominal input/output ratio used by the host to
 * choose the low-resolution extent.  {@link #inputWidth(int)} and
 * {@link #inputHeight(int)} apply the single authoritative rounding rule; the
 * pass itself accepts any positive input extent so a dynamic-resolution host can
 * vary it per frame without reallocating history.  A {@code renderScale} of one
 * means input and output share an extent (a pure temporal reconstruction with
 * no spatial magnification).</p>
 *
 * <h2>Trade-off</h2>
 * <ul>
 *   <li>{@code historyBlend} is the exponential history weight in
 *       {@code [0, 0.99]}.  A larger value converges to more effective samples
 *       but reacts more slowly; {@code 0} is current-frame only.</li>
 *   <li>{@code sharpness} is the bounded local-contrast unsharp amount in
 *       {@code [0, 1]}; the filter clamps every channel to the local
 *       neighbourhood range so it cannot introduce halos outside the existing
 *       contrast.  {@code 0} skips the sharpen pass entirely.</li>
 *   <li>{@code reactiveThreshold} rejects history when the relative luminance
 *       jumps (a reactive/shock reset); {@code 0} resets on any change.</li>
 *   <li>{@code exposureThreshold} rejects history when the frame exposure
 *       scalar changes by more than the relative threshold; {@code 1} accepts
 *       every positive exposure.  The pass never applies exposure itself:
 *       exposure remains the tone-mapping stage's responsibility and the scalar
 *       is only a scene-brightness change signal.</li>
 *   <li>{@code maxReprojectionPixels} rejects a history sample whose stable
 *       raster displacement exceeds the budget, measured in output pixels.</li>
 *   <li>{@code quality} selects the neighbourhood clamp radius in input texels:
 *       LOW 1, MEDIUM 2, HIGH 3.  A larger radius rejects ghosting across
 *       stronger spatial contrast at a higher tap cost.</li>
 * </ul>
 *
 * <p>The depth rejection window is fixed at {@code max(1e-4, 0.02 * max(depth,
 * 1))} metres, matching the conservative TAA/denoise default.
 * {@link #disabled()} is the exact no-op value: no pass, shader, history
 * attachment or draw is created while the effect is inactive.</p>
 */
public record TemporalUpscalingSettings(
        boolean enabled,
        float renderScale,
        float historyBlend,
        float sharpness,
        float reactiveThreshold,
        float exposureThreshold,
        float maxReprojectionPixels,
        Quality quality
) {
    /** Smallest accepted input/output ratio. */
    public static final float MIN_RENDER_SCALE = 0.1f;
    /** Largest accepted input/output ratio; one means no spatial magnification. */
    public static final float MAX_RENDER_SCALE = 1.0f;
    /** Largest accepted history weight; keeps the current sample non-zero. */
    public static final float MAX_HISTORY_BLEND = 0.99f;
    /** Largest accepted reprojection distance in output pixels. */
    public static final float MAX_REPROJECTION_PIXELS = 4096.0f;
    /** Relative depth window used by the depth rejection. */
    public static final float DEPTH_REJECTION_THRESHOLD = 0.02f;
    /** Absolute depth window floor in view-space metres. */
    public static final float MINIMUM_DEPTH_TOLERANCE = 1.0e-4f;

    /** Neighbourhood clamp radius in low-resolution texels. */
    public enum Quality {
        LOW(1),
        MEDIUM(2),
        HIGH(3);

        private final int neighborhoodRadius;

        Quality(int neighborhoodRadius) {
            this.neighborhoodRadius = neighborhoodRadius;
        }

        public int neighborhoodRadius() {
            return neighborhoodRadius;
        }
    }

    public TemporalUpscalingSettings {
        if (!Float.isFinite(renderScale) || renderScale < MIN_RENDER_SCALE
                || renderScale > MAX_RENDER_SCALE) {
            throw new IllegalArgumentException("renderScale must be finite and in ["
                    + MIN_RENDER_SCALE + ", " + MAX_RENDER_SCALE + "]");
        }
        if (!Float.isFinite(historyBlend) || historyBlend < 0.0f
                || historyBlend > MAX_HISTORY_BLEND) {
            throw new IllegalArgumentException("historyBlend must be finite and in [0, "
                    + MAX_HISTORY_BLEND + "]");
        }
        if (!Float.isFinite(sharpness) || sharpness < 0.0f || sharpness > 1.0f) {
            throw new IllegalArgumentException("sharpness must be finite and in [0, 1]");
        }
        if (!Float.isFinite(reactiveThreshold) || reactiveThreshold < 0.0f) {
            throw new IllegalArgumentException(
                    "reactiveThreshold must be finite and non-negative");
        }
        if (!Float.isFinite(exposureThreshold) || exposureThreshold < 0.0f
                || exposureThreshold > 1.0f) {
            throw new IllegalArgumentException(
                    "exposureThreshold must be finite and in [0, 1]");
        }
        if (!Float.isFinite(maxReprojectionPixels) || maxReprojectionPixels < 0.0f
                || maxReprojectionPixels > MAX_REPROJECTION_PIXELS) {
            throw new IllegalArgumentException("maxReprojectionPixels must be finite and in [0, "
                    + MAX_REPROJECTION_PIXELS + "]");
        }
        if (quality == null) {
            throw new NullPointerException("quality");
        }
    }

    /** Exact no-op value used when no temporal upscaling was requested. */
    public static TemporalUpscalingSettings disabled() {
        return new TemporalUpscalingSettings(false, 0.5f, 0.9f, 0.25f, 0.5f, 0.1f,
                64.0f, Quality.MEDIUM);
    }

    /** Enables the effect with the shared conservative default tuning. */
    public static TemporalUpscalingSettings defaults() {
        return disabled().withEnabled(true);
    }

    /** @return true when the pass, shader and history attachments should exist */
    public boolean active() {
        return enabled;
    }

    /** @return the exponential history weight in {@code [0, 0.99]} */
    public float historyWeight() {
        return historyBlend;
    }

    /** @return the current-frame weight {@code 1 - historyWeight()} */
    public float currentWeight() {
        return 1.0f - historyBlend;
    }

    /** @return the low-resolution width for an output width under {@code renderScale} */
    public int inputWidth(int outputWidth) {
        return scaledExtent(outputWidth, renderScale);
    }

    /** @return the low-resolution height for an output height under {@code renderScale} */
    public int inputHeight(int outputHeight) {
        return scaledExtent(outputHeight, renderScale);
    }

    /**
     * Absolute plus relative depth acceptance window for an expected positive
     * view-space depth.
     *
     * @param expectedDepth positive previous-frame view depth
     * @return maximum accepted depth difference in view-space metres
     */
    public float depthTolerance(float expectedDepth) {
        return Math.max(MINIMUM_DEPTH_TOLERANCE,
                DEPTH_REJECTION_THRESHOLD * Math.max(Math.abs(expectedDepth), 1.0f));
    }

    /**
     * @return the relative exposure change between two positive exposure scalars
     */
    public float exposureRelativeChange(float currentExposure, float previousExposure) {
        if (!Float.isFinite(currentExposure) || currentExposure <= 0.0f
                || !Float.isFinite(previousExposure) || previousExposure <= 0.0f) {
            throw new IllegalArgumentException("exposure values must be finite and positive");
        }
        return Math.abs(currentExposure - previousExposure)
                / Math.max(currentExposure, previousExposure);
    }

    /**
     * @return true when the exposure change stays inside the configured window
     */
    public boolean exposureAccepted(float currentExposure, float previousExposure) {
        return exposureRelativeChange(currentExposure, previousExposure)
                <= exposureThreshold;
    }

    /**
     * @return true when the relative luminance jump exceeds the reactive threshold
     */
    public boolean reactiveReset(float currentLuminance, float historyLuminance) {
        if (!Float.isFinite(currentLuminance) || !Float.isFinite(historyLuminance)) {
            return true;
        }
        float scale = Math.max(Math.max(Math.abs(currentLuminance), Math.abs(historyLuminance)),
                1.0e-6f);
        return Math.abs(currentLuminance - historyLuminance) / scale > reactiveThreshold;
    }

    public TemporalUpscalingSettings withEnabled(boolean value) {
        return new TemporalUpscalingSettings(value, renderScale, historyBlend, sharpness,
                reactiveThreshold, exposureThreshold, maxReprojectionPixels, quality);
    }

    public TemporalUpscalingSettings withRenderScale(float value) {
        return new TemporalUpscalingSettings(enabled, value, historyBlend, sharpness,
                reactiveThreshold, exposureThreshold, maxReprojectionPixels, quality);
    }

    public TemporalUpscalingSettings withHistoryBlend(float value) {
        return new TemporalUpscalingSettings(enabled, renderScale, value, sharpness,
                reactiveThreshold, exposureThreshold, maxReprojectionPixels, quality);
    }

    public TemporalUpscalingSettings withSharpness(float value) {
        return new TemporalUpscalingSettings(enabled, renderScale, historyBlend, value,
                reactiveThreshold, exposureThreshold, maxReprojectionPixels, quality);
    }

    public TemporalUpscalingSettings withReactiveThreshold(float value) {
        return new TemporalUpscalingSettings(enabled, renderScale, historyBlend, sharpness,
                value, exposureThreshold, maxReprojectionPixels, quality);
    }

    public TemporalUpscalingSettings withExposureThreshold(float value) {
        return new TemporalUpscalingSettings(enabled, renderScale, historyBlend, sharpness,
                reactiveThreshold, value, maxReprojectionPixels, quality);
    }

    public TemporalUpscalingSettings withMaxReprojectionPixels(float value) {
        return new TemporalUpscalingSettings(enabled, renderScale, historyBlend, sharpness,
                reactiveThreshold, exposureThreshold, value, quality);
    }

    public TemporalUpscalingSettings withQuality(Quality value) {
        return new TemporalUpscalingSettings(enabled, renderScale, historyBlend, sharpness,
                reactiveThreshold, exposureThreshold, maxReprojectionPixels, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static int scaledExtent(int outputExtent, float scale) {
        if (outputExtent <= 0) {
            throw new IllegalArgumentException("output extent must be positive");
        }
        return Math.max(1, (int) Math.round((double) outputExtent * (double) scale));
    }

    public static final class Builder {
        private boolean enabled;
        private float renderScale = 0.5f;
        private float historyBlend = 0.9f;
        private float sharpness = 0.25f;
        private float reactiveThreshold = 0.5f;
        private float exposureThreshold = 0.1f;
        private float maxReprojectionPixels = 64.0f;
        private Quality quality = Quality.MEDIUM;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder renderScale(float value) {
            renderScale = value;
            return this;
        }

        public Builder historyBlend(float value) {
            historyBlend = value;
            return this;
        }

        public Builder sharpness(float value) {
            sharpness = value;
            return this;
        }

        public Builder reactiveThreshold(float value) {
            reactiveThreshold = value;
            return this;
        }

        public Builder exposureThreshold(float value) {
            exposureThreshold = value;
            return this;
        }

        public Builder maxReprojectionPixels(float value) {
            maxReprojectionPixels = value;
            return this;
        }

        public Builder quality(Quality value) {
            quality = value;
            return this;
        }

        public TemporalUpscalingSettings build() {
            return new TemporalUpscalingSettings(enabled, renderScale, historyBlend, sharpness,
                    reactiveThreshold, exposureThreshold, maxReprojectionPixels, quality);
        }
    }
}
