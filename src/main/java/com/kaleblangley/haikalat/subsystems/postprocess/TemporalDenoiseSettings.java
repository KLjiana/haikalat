package com.kaleblangley.haikalat.subsystems.postprocess;

/**
 * Bounded temporal-denoise parameters for a single noisy real signal.
 *
 * <p>The first production binding is the screen-space reflection colour
 * produced by {@code SsrPass}: one ray per pixel, hence sparse and temporally
 * unstable samples.  A denoise owner owns exactly one signal history.  Future
 * signals (for example screen-space GI) must get their own
 * {@code TemporalDenoisePass} rather than sharing this history: two different
 * signals have different variance, disocclusion and motion semantics.</p>
 *
 * <h2>Trade-off</h2>
 *
 * <p>{@code historyLengthFrames} is the exponential moving-average horizon.
 * The shader receives {@link #historyWeight()} = {@code 1 - 1 / length}; a
 * longer history converges to more samples but reacts more slowly when the
 * signal changes.  {@code neighborhoodClampRadius} bounds the spatial support
 * used to reject stale history; {@code depthRejectionThreshold} and
 * {@code normalRejectionThreshold} reject history from another surface;
 * {@code reactiveThreshold} rejects history when the luminance jumps (a
 * reactive/shock reset) and {@code maxReprojectionPixels} rejects a history
 * sample that would move too far across the raster.</p>
 *
 * <p>{@link #disabled()} is the exact no-op value: no pass, shader, history
 * attachment or draw is created while the effect is inactive.</p>
 */
public record TemporalDenoiseSettings(
        boolean enabled,
        int historyLengthFrames,
        int neighborhoodClampRadius,
        float depthRejectionThreshold,
        float normalRejectionThreshold,
        float reactiveThreshold,
        float maxReprojectionPixels
) {
    /** Shortest accepted history; two frames is the minimum non-trivial blend. */
    public static final int MINIMUM_HISTORY_LENGTH = 2;
    /** Longest accepted history; keeps the exponential weight below one. */
    public static final int MAXIMUM_HISTORY_LENGTH = 64;
    /** Hard neighbourhood budget shared with the shader compile-time bound. */
    public static final int MAXIMUM_NEIGHBORHOOD_RADIUS = 4;
    /** Smallest accepted relative depth-rejection window. */
    public static final float MINIMUM_DEPTH_REJECTION = 1.0e-4f;
    /** Largest accepted relative depth-rejection window. */
    public static final float MAXIMUM_DEPTH_REJECTION = 0.5f;
    /** Largest accepted reprojection distance in output pixels. */
    public static final float MAXIMUM_REPROJECTION_PIXELS = 4096.0f;

    public TemporalDenoiseSettings {
        if (historyLengthFrames < MINIMUM_HISTORY_LENGTH
                || historyLengthFrames > MAXIMUM_HISTORY_LENGTH) {
            throw new IllegalArgumentException("historyLengthFrames must be in ["
                    + MINIMUM_HISTORY_LENGTH + ", " + MAXIMUM_HISTORY_LENGTH + "]");
        }
        if (neighborhoodClampRadius < 1
                || neighborhoodClampRadius > MAXIMUM_NEIGHBORHOOD_RADIUS) {
            throw new IllegalArgumentException("neighborhoodClampRadius must be in [1, "
                    + MAXIMUM_NEIGHBORHOOD_RADIUS + "]");
        }
        if (!Float.isFinite(depthRejectionThreshold)
                || depthRejectionThreshold < MINIMUM_DEPTH_REJECTION
                || depthRejectionThreshold > MAXIMUM_DEPTH_REJECTION) {
            throw new IllegalArgumentException("depthRejectionThreshold must be finite and in ["
                    + MINIMUM_DEPTH_REJECTION + ", " + MAXIMUM_DEPTH_REJECTION + "]");
        }
        if (!Float.isFinite(normalRejectionThreshold)
                || normalRejectionThreshold < 0.0f || normalRejectionThreshold > 1.0f) {
            throw new IllegalArgumentException(
                    "normalRejectionThreshold must be finite and in [0, 1]");
        }
        if (!Float.isFinite(reactiveThreshold) || reactiveThreshold < 0.0f) {
            throw new IllegalArgumentException(
                    "reactiveThreshold must be finite and non-negative");
        }
        if (!Float.isFinite(maxReprojectionPixels) || maxReprojectionPixels < 0.0f
                || maxReprojectionPixels > MAXIMUM_REPROJECTION_PIXELS) {
            throw new IllegalArgumentException("maxReprojectionPixels must be finite and in [0, "
                    + MAXIMUM_REPROJECTION_PIXELS + "]");
        }
    }

    /** Exact no-op value used when no temporal denoise was requested. */
    public static TemporalDenoiseSettings disabled() {
        return new TemporalDenoiseSettings(false, 10, 2, 0.02f, 0.85f, 0.5f, 64.0f);
    }

    /** Enables the effect with the shared conservative default tuning. */
    public static TemporalDenoiseSettings defaults() {
        return disabled().withEnabled(true);
    }

    /** @return true when the pass, shader and history attachments should exist */
    public boolean active() {
        return enabled;
    }

    /**
     * @return the exponential history weight in {@code (0, 1)} received by the shader
     */
    public float historyWeight() {
        return 1.0f - 1.0f / historyLengthFrames;
    }

    /** @return the current-frame weight {@code 1 - historyWeight()} */
    public float currentWeight() {
        return 1.0f - historyWeight();
    }

    /**
     * Absolute plus relative depth acceptance window for an expected positive
     * view-space depth.  The one-metre floor keeps the absolute window stable
     * near the camera instead of shrinking to zero at small depths.
     *
     * @param expectedDepth positive previous-frame view depth
     * @return maximum accepted depth difference in view-space metres
     */
    public float depthTolerance(float expectedDepth) {
        return Math.max(MINIMUM_DEPTH_REJECTION,
                depthRejectionThreshold * Math.max(Math.abs(expectedDepth), 1.0f));
    }

    public TemporalDenoiseSettings withEnabled(boolean value) {
        return new TemporalDenoiseSettings(value, historyLengthFrames,
                neighborhoodClampRadius, depthRejectionThreshold,
                normalRejectionThreshold, reactiveThreshold, maxReprojectionPixels);
    }

    public TemporalDenoiseSettings withHistoryLengthFrames(int value) {
        return new TemporalDenoiseSettings(enabled, value, neighborhoodClampRadius,
                depthRejectionThreshold, normalRejectionThreshold, reactiveThreshold,
                maxReprojectionPixels);
    }

    public TemporalDenoiseSettings withNeighborhoodClampRadius(int value) {
        return new TemporalDenoiseSettings(enabled, historyLengthFrames, value,
                depthRejectionThreshold, normalRejectionThreshold, reactiveThreshold,
                maxReprojectionPixels);
    }

    public TemporalDenoiseSettings withDepthRejectionThreshold(float value) {
        return new TemporalDenoiseSettings(enabled, historyLengthFrames,
                neighborhoodClampRadius, value, normalRejectionThreshold, reactiveThreshold,
                maxReprojectionPixels);
    }

    public TemporalDenoiseSettings withNormalRejectionThreshold(float value) {
        return new TemporalDenoiseSettings(enabled, historyLengthFrames,
                neighborhoodClampRadius, depthRejectionThreshold, value, reactiveThreshold,
                maxReprojectionPixels);
    }

    public TemporalDenoiseSettings withReactiveThreshold(float value) {
        return new TemporalDenoiseSettings(enabled, historyLengthFrames,
                neighborhoodClampRadius, depthRejectionThreshold, normalRejectionThreshold,
                value, maxReprojectionPixels);
    }

    public TemporalDenoiseSettings withMaxReprojectionPixels(float value) {
        return new TemporalDenoiseSettings(enabled, historyLengthFrames,
                neighborhoodClampRadius, depthRejectionThreshold, normalRejectionThreshold,
                reactiveThreshold, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled;
        private int historyLengthFrames = 10;
        private int neighborhoodClampRadius = 2;
        private float depthRejectionThreshold = 0.02f;
        private float normalRejectionThreshold = 0.85f;
        private float reactiveThreshold = 0.5f;
        private float maxReprojectionPixels = 64.0f;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder historyLengthFrames(int value) {
            historyLengthFrames = value;
            return this;
        }

        public Builder neighborhoodClampRadius(int value) {
            neighborhoodClampRadius = value;
            return this;
        }

        public Builder depthRejectionThreshold(float value) {
            depthRejectionThreshold = value;
            return this;
        }

        public Builder normalRejectionThreshold(float value) {
            normalRejectionThreshold = value;
            return this;
        }

        public Builder reactiveThreshold(float value) {
            reactiveThreshold = value;
            return this;
        }

        public Builder maxReprojectionPixels(float value) {
            maxReprojectionPixels = value;
            return this;
        }

        public TemporalDenoiseSettings build() {
            return new TemporalDenoiseSettings(enabled, historyLengthFrames,
                    neighborhoodClampRadius, depthRejectionThreshold,
                    normalRejectionThreshold, reactiveThreshold, maxReprojectionPixels);
        }
    }
}
