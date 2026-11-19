package com.kaleblangley.haikalat.subsystems.render3d.transparency;

/**
 * Bounded configuration of the stochastic transparency collection (S4 #37).
 *
 * <p>Stochastic transparency renders every selected transparent surface as an
 * <em>opaque</em> fragment with probability {@code p(alpha)} and keeps the
 * nearest accepted fragment per pixel and per sample.  Averaging independent
 * samples therefore estimates the exact back-to-front alpha composite of the
 * collection: a layer with coverage {@code alpha} hides everything behind it in
 * {@code alpha} of the samples, so the expected colour is the sorted-over
 * result, and the expected coverage is {@code 1 - prod(1 - p_i)}.</p>
 *
 * <p>The acceptance probability is</p>
 *
 * <pre>{@code
 * p(alpha) = 0                                  if alpha <= alphaThreshold
 *          = 1                                  if alpha >= 1
 *          = clamp(alpha ^ coverageGamma, 0, 1) otherwise
 * }</pre>
 *
 * <p>{@code coverageGamma = 1} is the unbiased estimator.  Other values
 * deliberately remap coverage (below one boosts faint smoke, above one thins
 * it); the resulting image no longer estimates the original alpha composite
 * exactly and the documented expectation is the composite with {@code p_i} as
 * the layer alpha.</p>
 *
 * <p>{@link #samplesPerFrame} independent samples are accumulated per frame.
 * Optional temporal accumulation then exponentiates the per-frame estimate with
 * a bounded running average.  The effective history weight after {@code n}
 * committed frames is</p>
 *
 * <pre>{@code
 * n = min(historyLength, maximumHistoryLength)
 * w = temporalAccumulation ? historyWeight * n / (n + 1) : 0
 * }</pre>
 *
 * <p>so the first frame contributes only itself, the history horizon is bounded
 * by {@code maximumHistoryLength}, and {@code historyWeight} is a master cap.
 * Temporal rejection uses the reprojected scene-depth motion budget
 * ({@link #velocityRejectionPixels}), a per-pixel coverage shock
 * ({@link #reactiveThreshold}) and the pass' explicit {@code invalidate()}
 * camera-cut entry point; accepted history is additionally clamped to the
 * current depth-matched neighbourhood.</p>
 *
 * <p>{@link #disabled()} is the exact no-op value: no pass, framebuffer,
 * history, shader program or draw is created/recorded while
 * {@link #active()} is {@code false}.</p>
 */
public record StochasticTransparencySettings(
        boolean enabled,
        int samplesPerFrame,
        int seed,
        JitterMode jitterMode,
        float alphaThreshold,
        float coverageGamma,
        boolean temporalAccumulation,
        float historyWeight,
        int maximumHistoryLength,
        int neighborhoodClampRadius,
        float reactiveThreshold,
        float velocityRejectionPixels,
        float depthRejectionThreshold,
        int maximumSurfaces) {

    /** Lowest accepted per-frame sample count. */
    public static final int MINIMUM_SAMPLES_PER_FRAME = 1;
    /** Highest accepted per-frame sample count (keeps the per-frame draw count bounded). */
    public static final int MAXIMUM_SAMPLES_PER_FRAME = 64;
    /** Largest accepted per-layer alpha discard threshold. */
    public static final float MAXIMUM_ALPHA_THRESHOLD = 0.999f;
    /** Smallest accepted coverage exponent. */
    public static final float MINIMUM_COVERAGE_GAMMA = 0.25f;
    /** Largest accepted coverage exponent. */
    public static final float MAXIMUM_COVERAGE_GAMMA = 4.0f;
    /** Largest accepted reactive coverage-shock window. */
    public static final float MAXIMUM_REACTIVE_THRESHOLD = 1.0f;
    /** Largest accepted reprojection displacement in output pixels. */
    public static final float MAXIMUM_VELOCITY_REJECTION_PIXELS = 4096.0f;
    /** Smallest accepted relative depth-rejection window. */
    public static final float MINIMUM_DEPTH_REJECTION = 1.0e-4f;
    /** Largest accepted relative depth-rejection window. */
    public static final float MAXIMUM_DEPTH_REJECTION = 0.5f;
    /** Shortest accepted history cap; two frames is the minimum non-trivial blend. */
    public static final int MINIMUM_HISTORY_LENGTH = 2;
    /** Longest accepted history cap. */
    public static final int MAXIMUM_HISTORY_LENGTH = 64;
    /** Hard neighbourhood budget shared with the shader's compile-time bound. */
    public static final int MAXIMUM_NEIGHBORHOOD_RADIUS = 4;
    /** Smallest accepted quad capacity. */
    public static final int MINIMUM_SURFACES = 1;
    /** Largest accepted quad capacity. */
    public static final int MAXIMUM_SURFACES = 65_536;

    /** Default per-frame sample count (a compromise between noise and draw cost). */
    public static final int DEFAULT_SAMPLES_PER_FRAME = 4;
    /** Default deterministic seed. */
    public static final int DEFAULT_SEED = 0x5EED_5EED;
    /** Default coverage exponent (unbiased). */
    public static final float DEFAULT_COVERAGE_GAMMA = 1.0f;
    /** Default maximum history weight cap. */
    public static final float DEFAULT_HISTORY_WEIGHT = 1.0f;
    /** Default history horizon in frames. */
    public static final int DEFAULT_MAXIMUM_HISTORY_LENGTH = 16;
    /** Default neighbourhood clamp radius. */
    public static final int DEFAULT_NEIGHBORHOOD_CLAMP_RADIUS = 2;
    /** Default coverage-shock window; one means "reactive disabled". */
    public static final float DEFAULT_REACTIVE_THRESHOLD = 1.0f;
    /** Default reprojection motion budget in output pixels. */
    public static final float DEFAULT_VELOCITY_REJECTION_PIXELS = 32.0f;
    /** Default relative depth-rejection window. */
    public static final float DEFAULT_DEPTH_REJECTION_THRESHOLD = 0.02f;
    /** Default maximum number of quads in one collection. */
    public static final int DEFAULT_MAXIMUM_SURFACES = 4_096;

    /**
     * Spatio-temporal threshold source.  Every mode is deterministic for a
     * fixed seed, frame index, sample index, instance index and pixel.
     */
    public enum JitterMode {
        /** 32-bit integer hash; white-noise estimator (default). */
        HASH(0),
        /** Interleaved-gradient dither with per-sample/frame offsets. */
        INTERLEAVED_GRADIENT(1),
        /** 4x4 ordered Bayer dither with per-sample/frame offsets. */
        BAYER_4X4(2);

        private final int glCode;

        JitterMode(int glCode) {
            this.glCode = glCode;
        }

        /** @return the integer consumed by the production shader's {@code uJitterMode} */
        public int glCode() {
            return glCode;
        }
    }

    public StochasticTransparencySettings {
        if (samplesPerFrame < MINIMUM_SAMPLES_PER_FRAME
                || samplesPerFrame > MAXIMUM_SAMPLES_PER_FRAME) {
            throw new IllegalArgumentException("samplesPerFrame must be in ["
                    + MINIMUM_SAMPLES_PER_FRAME + ", " + MAXIMUM_SAMPLES_PER_FRAME + "]");
        }
        if (jitterMode == null) {
            throw new IllegalArgumentException("jitterMode must not be null");
        }
        if (!Float.isFinite(alphaThreshold) || alphaThreshold < 0.0f
                || alphaThreshold > MAXIMUM_ALPHA_THRESHOLD) {
            throw new IllegalArgumentException("alphaThreshold must be finite and in [0, "
                    + MAXIMUM_ALPHA_THRESHOLD + "]");
        }
        if (!Float.isFinite(coverageGamma) || coverageGamma < MINIMUM_COVERAGE_GAMMA
                || coverageGamma > MAXIMUM_COVERAGE_GAMMA) {
            throw new IllegalArgumentException("coverageGamma must be finite and in ["
                    + MINIMUM_COVERAGE_GAMMA + ", " + MAXIMUM_COVERAGE_GAMMA + "]");
        }
        if (!Float.isFinite(historyWeight) || historyWeight < 0.0f
                || historyWeight > 1.0f) {
            throw new IllegalArgumentException("historyWeight must be finite and in [0, 1]");
        }
        if (maximumHistoryLength < MINIMUM_HISTORY_LENGTH
                || maximumHistoryLength > MAXIMUM_HISTORY_LENGTH) {
            throw new IllegalArgumentException("maximumHistoryLength must be in ["
                    + MINIMUM_HISTORY_LENGTH + ", " + MAXIMUM_HISTORY_LENGTH + "]");
        }
        if (neighborhoodClampRadius < 1
                || neighborhoodClampRadius > MAXIMUM_NEIGHBORHOOD_RADIUS) {
            throw new IllegalArgumentException("neighborhoodClampRadius must be in [1, "
                    + MAXIMUM_NEIGHBORHOOD_RADIUS + "]");
        }
        if (!Float.isFinite(reactiveThreshold) || reactiveThreshold < 0.0f
                || reactiveThreshold > MAXIMUM_REACTIVE_THRESHOLD) {
            throw new IllegalArgumentException("reactiveThreshold must be finite and in [0, "
                    + MAXIMUM_REACTIVE_THRESHOLD + "]");
        }
        if (!Float.isFinite(velocityRejectionPixels) || velocityRejectionPixels < 0.0f
                || velocityRejectionPixels > MAXIMUM_VELOCITY_REJECTION_PIXELS) {
            throw new IllegalArgumentException("velocityRejectionPixels must be finite and in [0, "
                    + MAXIMUM_VELOCITY_REJECTION_PIXELS + "]");
        }
        if (!Float.isFinite(depthRejectionThreshold)
                || depthRejectionThreshold < MINIMUM_DEPTH_REJECTION
                || depthRejectionThreshold > MAXIMUM_DEPTH_REJECTION) {
            throw new IllegalArgumentException("depthRejectionThreshold must be finite and in ["
                    + MINIMUM_DEPTH_REJECTION + ", " + MAXIMUM_DEPTH_REJECTION + "]");
        }
        if (maximumSurfaces < MINIMUM_SURFACES || maximumSurfaces > MAXIMUM_SURFACES) {
            throw new IllegalArgumentException("maximumSurfaces must be in ["
                    + MINIMUM_SURFACES + ", " + MAXIMUM_SURFACES + "]");
        }
    }

    /** Exact no-op value used when the stochastic collection is absent or suppressed. */
    public static StochasticTransparencySettings disabled() {
        return new StochasticTransparencySettings(false, DEFAULT_SAMPLES_PER_FRAME,
                DEFAULT_SEED, JitterMode.HASH, 0.0f, DEFAULT_COVERAGE_GAMMA, true,
                DEFAULT_HISTORY_WEIGHT, DEFAULT_MAXIMUM_HISTORY_LENGTH,
                DEFAULT_NEIGHBORHOOD_CLAMP_RADIUS, DEFAULT_REACTIVE_THRESHOLD,
                DEFAULT_VELOCITY_REJECTION_PIXELS, DEFAULT_DEPTH_REJECTION_THRESHOLD,
                DEFAULT_MAXIMUM_SURFACES);
    }

    /** Conservative active defaults: four samples, hash jitter, bounded temporal history. */
    public static StochasticTransparencySettings defaults() {
        return disabled().withEnabled(true);
    }

    /** @return true when the pass, attachments and draws should exist */
    public boolean active() {
        return enabled;
    }

    /**
     * CPU counterpart of the shader's acceptance test.
     *
     * <p>The computation uses double precision; the shader evaluates the same
     * expression in single precision.  Fixed-input JVM cases and the GL
     * statistical cases use this method as the independent expectation, never
     * the shader itself.</p>
     *
     * @param alpha straight surface coverage in {@code [0, 1]}
     * @return acceptance probability in {@code [0, 1]}
     */
    public double acceptanceProbability(double alpha) {
        if (!Double.isFinite(alpha) || alpha < 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("alpha must be finite and in [0, 1]");
        }
        if (alpha <= (double) alphaThreshold) {
            return 0.0;
        }
        if (alpha >= 1.0) {
            return 1.0;
        }
        double probability = Math.pow(alpha, (double) coverageGamma);
        return Math.min(1.0, Math.max(0.0, probability));
    }

    /**
     * Effective history weight for a committed history length.
     *
     * <p>Returns exactly {@code 0} when temporal accumulation is disabled, when
     * no frame has committed, or when {@code historyLength <= 0}.  The result is
     * strictly below one for legal settings, so the current frame always
     * contributes.</p>
     *
     * @param historyLength number of already committed temporal frames
     * @return weight applied to reprojected history in {@code [0, 1)}
     */
    public double historyWeightForLength(int historyLength) {
        if (historyLength < 0) {
            throw new IllegalArgumentException("historyLength must be non-negative");
        }
        if (!temporalAccumulation || historyLength == 0) {
            return 0.0;
        }
        int clamped = Math.min(historyLength, maximumHistoryLength);
        return (double) historyWeight * clamped / (clamped + 1.0);
    }

    /**
     * Absolute plus relative depth acceptance window for an expected positive
     * view-space depth, matching the resolve shader.
     *
     * @param expectedDepth positive view-space depth
     * @return maximum accepted depth difference in view-space metres
     */
    public double depthTolerance(double expectedDepth) {
        if (!Double.isFinite(expectedDepth)) {
            throw new IllegalArgumentException("expectedDepth must be finite");
        }
        return Math.max((double) MINIMUM_DEPTH_REJECTION,
                (double) depthRejectionThreshold * Math.max(Math.abs(expectedDepth), 1.0));
    }

    /** Bounded copy of this value with only the enabled flag changed. */
    public StochasticTransparencySettings withEnabled(boolean value) {
        return new StochasticTransparencySettings(value, samplesPerFrame, seed, jitterMode,
                alphaThreshold, coverageGamma, temporalAccumulation, historyWeight,
                maximumHistoryLength, neighborhoodClampRadius, reactiveThreshold,
                velocityRejectionPixels, depthRejectionThreshold, maximumSurfaces);
    }

    /** Bounded copy of this value with only the per-frame sample count changed. */
    public StochasticTransparencySettings withSamplesPerFrame(int value) {
        return new StochasticTransparencySettings(enabled, value, seed, jitterMode,
                alphaThreshold, coverageGamma, temporalAccumulation, historyWeight,
                maximumHistoryLength, neighborhoodClampRadius, reactiveThreshold,
                velocityRejectionPixels, depthRejectionThreshold, maximumSurfaces);
    }

    /** Bounded copy of this value with only the deterministic seed changed. */
    public StochasticTransparencySettings withSeed(int value) {
        return new StochasticTransparencySettings(enabled, samplesPerFrame, value, jitterMode,
                alphaThreshold, coverageGamma, temporalAccumulation, historyWeight,
                maximumHistoryLength, neighborhoodClampRadius, reactiveThreshold,
                velocityRejectionPixels, depthRejectionThreshold, maximumSurfaces);
    }

    /** Bounded copy of this value with only the temporal toggle changed. */
    public StochasticTransparencySettings withTemporalAccumulation(boolean value) {
        return new StochasticTransparencySettings(enabled, samplesPerFrame, seed, jitterMode,
                alphaThreshold, coverageGamma, value, historyWeight,
                maximumHistoryLength, neighborhoodClampRadius, reactiveThreshold,
                velocityRejectionPixels, depthRejectionThreshold, maximumSurfaces);
    }

    /** Bounded copy of this value with only the history weight cap changed. */
    public StochasticTransparencySettings withHistoryWeight(float value) {
        return new StochasticTransparencySettings(enabled, samplesPerFrame, seed, jitterMode,
                alphaThreshold, coverageGamma, temporalAccumulation, value,
                maximumHistoryLength, neighborhoodClampRadius, reactiveThreshold,
                velocityRejectionPixels, depthRejectionThreshold, maximumSurfaces);
    }

    /** Bounded copy of this value with only the history horizon changed. */
    public StochasticTransparencySettings withMaximumHistoryLength(int value) {
        return new StochasticTransparencySettings(enabled, samplesPerFrame, seed, jitterMode,
                alphaThreshold, coverageGamma, temporalAccumulation, historyWeight,
                value, neighborhoodClampRadius, reactiveThreshold,
                velocityRejectionPixels, depthRejectionThreshold, maximumSurfaces);
    }

    /** Bounded copy of this value with only the quad capacity changed. */
    public StochasticTransparencySettings withMaximumSurfaces(int value) {
        return new StochasticTransparencySettings(enabled, samplesPerFrame, seed, jitterMode,
                alphaThreshold, coverageGamma, temporalAccumulation, historyWeight,
                maximumHistoryLength, neighborhoodClampRadius, reactiveThreshold,
                velocityRejectionPixels, depthRejectionThreshold, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder mirroring the record's validated ranges. */
    public static final class Builder {
        private boolean enabled;
        private int samplesPerFrame = DEFAULT_SAMPLES_PER_FRAME;
        private int seed = DEFAULT_SEED;
        private JitterMode jitterMode = JitterMode.HASH;
        private float alphaThreshold;
        private float coverageGamma = DEFAULT_COVERAGE_GAMMA;
        private boolean temporalAccumulation = true;
        private float historyWeight = DEFAULT_HISTORY_WEIGHT;
        private int maximumHistoryLength = DEFAULT_MAXIMUM_HISTORY_LENGTH;
        private int neighborhoodClampRadius = DEFAULT_NEIGHBORHOOD_CLAMP_RADIUS;
        private float reactiveThreshold = DEFAULT_REACTIVE_THRESHOLD;
        private float velocityRejectionPixels = DEFAULT_VELOCITY_REJECTION_PIXELS;
        private float depthRejectionThreshold = DEFAULT_DEPTH_REJECTION_THRESHOLD;
        private int maximumSurfaces = DEFAULT_MAXIMUM_SURFACES;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder samplesPerFrame(int value) {
            samplesPerFrame = value;
            return this;
        }

        public Builder seed(int value) {
            seed = value;
            return this;
        }

        public Builder jitterMode(JitterMode value) {
            jitterMode = value;
            return this;
        }

        public Builder alphaThreshold(float value) {
            alphaThreshold = value;
            return this;
        }

        public Builder coverageGamma(float value) {
            coverageGamma = value;
            return this;
        }

        public Builder temporalAccumulation(boolean value) {
            temporalAccumulation = value;
            return this;
        }

        public Builder historyWeight(float value) {
            historyWeight = value;
            return this;
        }

        public Builder maximumHistoryLength(int value) {
            maximumHistoryLength = value;
            return this;
        }

        public Builder neighborhoodClampRadius(int value) {
            neighborhoodClampRadius = value;
            return this;
        }

        public Builder reactiveThreshold(float value) {
            reactiveThreshold = value;
            return this;
        }

        public Builder velocityRejectionPixels(float value) {
            velocityRejectionPixels = value;
            return this;
        }

        public Builder depthRejectionThreshold(float value) {
            depthRejectionThreshold = value;
            return this;
        }

        public Builder maximumSurfaces(int value) {
            maximumSurfaces = value;
            return this;
        }

        public StochasticTransparencySettings build() {
            return new StochasticTransparencySettings(enabled, samplesPerFrame, seed,
                    jitterMode, alphaThreshold, coverageGamma, temporalAccumulation,
                    historyWeight, maximumHistoryLength, neighborhoodClampRadius,
                    reactiveThreshold, velocityRejectionPixels, depthRejectionThreshold,
                    maximumSurfaces);
        }
    }
}
