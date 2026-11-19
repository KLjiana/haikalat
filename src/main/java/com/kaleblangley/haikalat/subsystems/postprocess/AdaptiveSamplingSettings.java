package com.kaleblangley.haikalat.subsystems.postprocess;

/**
 * Bounded adaptive step-budget policy for a real expensive sampler.
 *
 * <p>The first production binding is the screen-space reflection march of
 * {@link SsrSettings}: one reflected ray per pixel and a fixed per-pixel step
 * budget.  Adaptive sampling replaces that fixed budget with a budget derived
 * from two measured signals of the same pass:</p>
 *
 * <ul>
 *   <li><b>Per-tile error</b>: the local variance of the shared world normal and
 *       material roughness probes inside a {@link #tileSizePixels()} tile.
 *       Every pixel of a tile evaluates the same tile-quantized probes, so the
 *       tile term is identical inside the tile.  Depth is deliberately not
 *       part of this term: a receding floor has a large smooth depth spread
 *       that says nothing about a silhouette, while a normal/roughness
 *       discontinuity marks the thin-geometry band where coarse steps can
 *       tunnel.</li>
 *   <li><b>Per-pixel residual</b>: the convergence error measured by the
 *       minimum-sample coarse march of that pixel's own ray, namely the
 *       largest normalized screen-space step and the largest positive depth
 *       jump at a surface crossing.  A coarse step that crosses a surface with
 *       a large depth gap can tunnel through thin geometry; that measured gap
 *       is the error estimate.</li>
 * </ul>
 *
 * <p>The final budget is {@code max(tileBudget, pixelBudget)} clamped to
 * {@code [minimumSteps, maximumSteps]}.  Both terms are measured, monotone and
 * bounded, so the allocation changes inside one settings configuration and is
 * not a quality-tier switch.  Every pixel always spends the
 * {@link #minimumSteps()} floor first, which is the stability guarantee: extra
 * budget can only add samples, and the adaptive shader keeps a coarse hit when
 * the finer march does not find one.</p>
 *
 * <h2>Cost</h2>
 *
 * <p>The coarse march spends {@code minimumSteps} samples and the final march
 * spends at most {@code maximumSteps}; the worst case per pixel is therefore
 * {@code minimumSteps + maximumSteps}, and both marches are clamped by the
 * shader's compile-time {@code SSR_MAX_STEPS}.  A pixel whose residual is zero
 * reuses the coarse result and pays only the floor.  This is a step budget, not
 * a measured GPU time claim.</p>
 *
 * <p>{@link #disabled()} is the exact no-op value: the screen-space reflection
 * pass keeps its fixed-budget shader, allocates no extra program and changes no
 * uniform or pass.  Enabling adaptive sampling requires an active
 * {@link SsrSettings} and {@code maximumSteps} must not exceed
 * {@link SsrSettings#maxSteps()} so the configured reflection hard bound stays
 * the ceiling.</p>
 */
public record AdaptiveSamplingSettings(
        boolean enabled,
        int minimumSteps,
        int maximumSteps,
        int tileSizePixels,
        float errorThreshold,
        float screenStepScalePixels,
        float depthJumpScale
) {
    /** Smallest accepted sample floor; one sample is the degenerate coarse march. */
    public static final int MINIMUM_STEP_FLOOR = 1;
    /** Largest accepted step budget; matches the shader's {@code SSR_MAX_STEPS}. */
    public static final int MAXIMUM_STEP_CEILING = SsrSettings.MAXIMUM_STEPS;
    /** Smallest accepted tile edge in pixels. */
    public static final int MINIMUM_TILE_SIZE = 1;
    /** Largest accepted tile edge in pixels. */
    public static final int MAXIMUM_TILE_SIZE = 64;
    /** Largest accepted normalized error threshold; keeps a non-degenerate ramp. */
    public static final float MAXIMUM_ERROR_THRESHOLD = 0.9f;
    /** Largest accepted screen-space step scale in pixels. */
    public static final float MAXIMUM_SCREEN_STEP_SCALE_PIXELS = 64.0f;
    /** Largest accepted depth-jump scale in units of the hit thickness. */
    public static final float MAXIMUM_DEPTH_JUMP_SCALE = 8.0f;

    public AdaptiveSamplingSettings {
        if (minimumSteps < MINIMUM_STEP_FLOOR || minimumSteps > MAXIMUM_STEP_CEILING) {
            throw new IllegalArgumentException("minimumSteps must be in ["
                    + MINIMUM_STEP_FLOOR + ", " + MAXIMUM_STEP_CEILING + "]");
        }
        if (maximumSteps < minimumSteps || maximumSteps > MAXIMUM_STEP_CEILING) {
            throw new IllegalArgumentException("maximumSteps must be in [minimumSteps, "
                    + MAXIMUM_STEP_CEILING + "]");
        }
        if (tileSizePixels < MINIMUM_TILE_SIZE || tileSizePixels > MAXIMUM_TILE_SIZE) {
            throw new IllegalArgumentException("tileSizePixels must be in ["
                    + MINIMUM_TILE_SIZE + ", " + MAXIMUM_TILE_SIZE + "]");
        }
        if (!Float.isFinite(errorThreshold) || errorThreshold < 0.0f
                || errorThreshold > MAXIMUM_ERROR_THRESHOLD) {
            throw new IllegalArgumentException("errorThreshold must be finite and in [0, "
                    + MAXIMUM_ERROR_THRESHOLD + "]");
        }
        if (!Float.isFinite(screenStepScalePixels) || screenStepScalePixels <= 0.0f
                || screenStepScalePixels > MAXIMUM_SCREEN_STEP_SCALE_PIXELS) {
            throw new IllegalArgumentException("screenStepScalePixels must be finite and in (0, "
                    + MAXIMUM_SCREEN_STEP_SCALE_PIXELS + "]");
        }
        if (!Float.isFinite(depthJumpScale) || depthJumpScale <= 0.0f
                || depthJumpScale > MAXIMUM_DEPTH_JUMP_SCALE) {
            throw new IllegalArgumentException("depthJumpScale must be finite and in (0, "
                    + MAXIMUM_DEPTH_JUMP_SCALE + "]");
        }
    }

    /** Exact no-op value used when no adaptive budget was requested. */
    public static AdaptiveSamplingSettings disabled() {
        return new AdaptiveSamplingSettings(false, 8, 32, 8, 0.10f, 4.0f, 1.0f);
    }

    /** Enables adaptive sampling with the shared conservative default tuning. */
    public static AdaptiveSamplingSettings defaults() {
        return disabled().withEnabled(true);
    }

    /** @return true when the adaptive shader variant and its uniforms are used */
    public boolean active() {
        return enabled;
    }

    /** @return number of allocatable steps between the floor and the ceiling */
    public int budgetSpanSteps() {
        return maximumSteps - minimumSteps;
    }

    /**
     * Worst-case per-pixel sample count of the two-phase march.  Both phases
     * are independently clamped by the shader's compile-time loop bound.
     *
     * @return {@code minimumSteps + maximumSteps}
     */
    public int worstCaseSteps() {
        return minimumSteps + maximumSteps;
    }

    /**
     * Maps a normalized measured error to a bounded step budget.
     *
     * <p>This is the documented allocation formula shared with
     * {@code ssr-adaptive.frag}: errors at or below {@link #errorThreshold()}
     * spend only the floor, the ramp is linear over
     * {@code (threshold, 1]} and the result is rounded half up.  The independent
     * JVM double reference recomputes the same fixed inputs.</p>
     *
     * @param normalizedError measured error in {@code [0, 1]}; values outside
     *                        the interval are clamped, non-finite values fail
     * @return step budget in {@code [minimumSteps, maximumSteps]}
     */
    public int budgetFor(double normalizedError) {
        if (!Double.isFinite(normalizedError)) {
            throw new IllegalArgumentException("normalizedError must be finite");
        }
        double error = Math.max(0.0, Math.min(1.0, normalizedError));
        double threshold = errorThreshold;
        double denominator = Math.max(1.0 - threshold, 1.0e-4);
        double normalized = Math.max(0.0, Math.min(1.0, (error - threshold) / denominator));
        return minimumSteps + (int) Math.floor(normalized * budgetSpanSteps() + 0.5);
    }

    /**
     * Number of tile edges that a {@code width x height} raster covers,
     * including a partial last tile.  Kept for diagnostics and the JVM
     * reference; the shader derives the same tile from {@code gl_FragCoord}.
     *
     * @param width  raster width in pixels
     * @param height raster height in pixels
     * @return tiles along x multiplied by tiles along y
     */
    public long tileCount(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("raster extent must be positive");
        }
        long columns = ((long) width + tileSizePixels - 1) / tileSizePixels;
        long rows = ((long) height + tileSizePixels - 1) / tileSizePixels;
        return columns * rows;
    }

    public AdaptiveSamplingSettings withEnabled(boolean value) {
        return new AdaptiveSamplingSettings(value, minimumSteps, maximumSteps, tileSizePixels,
                errorThreshold, screenStepScalePixels, depthJumpScale);
    }

    public AdaptiveSamplingSettings withMinimumSteps(int value) {
        return new AdaptiveSamplingSettings(enabled, value, maximumSteps, tileSizePixels,
                errorThreshold, screenStepScalePixels, depthJumpScale);
    }

    public AdaptiveSamplingSettings withMaximumSteps(int value) {
        return new AdaptiveSamplingSettings(enabled, minimumSteps, value, tileSizePixels,
                errorThreshold, screenStepScalePixels, depthJumpScale);
    }

    public AdaptiveSamplingSettings withTileSizePixels(int value) {
        return new AdaptiveSamplingSettings(enabled, minimumSteps, maximumSteps, value,
                errorThreshold, screenStepScalePixels, depthJumpScale);
    }

    public AdaptiveSamplingSettings withErrorThreshold(float value) {
        return new AdaptiveSamplingSettings(enabled, minimumSteps, maximumSteps, tileSizePixels,
                value, screenStepScalePixels, depthJumpScale);
    }

    public AdaptiveSamplingSettings withScreenStepScalePixels(float value) {
        return new AdaptiveSamplingSettings(enabled, minimumSteps, maximumSteps, tileSizePixels,
                errorThreshold, value, depthJumpScale);
    }

    public AdaptiveSamplingSettings withDepthJumpScale(float value) {
        return new AdaptiveSamplingSettings(enabled, minimumSteps, maximumSteps, tileSizePixels,
                errorThreshold, screenStepScalePixels, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled;
        private int minimumSteps = 8;
        private int maximumSteps = 32;
        private int tileSizePixels = 8;
        private float errorThreshold = 0.10f;
        private float screenStepScalePixels = 4.0f;
        private float depthJumpScale = 1.0f;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder minimumSteps(int value) {
            minimumSteps = value;
            return this;
        }

        public Builder maximumSteps(int value) {
            maximumSteps = value;
            return this;
        }

        public Builder tileSizePixels(int value) {
            tileSizePixels = value;
            return this;
        }

        public Builder errorThreshold(float value) {
            errorThreshold = value;
            return this;
        }

        public Builder screenStepScalePixels(float value) {
            screenStepScalePixels = value;
            return this;
        }

        public Builder depthJumpScale(float value) {
            depthJumpScale = value;
            return this;
        }

        public AdaptiveSamplingSettings build() {
            return new AdaptiveSamplingSettings(enabled, minimumSteps, maximumSteps,
                    tileSizePixels, errorThreshold, screenStepScalePixels, depthJumpScale);
        }
    }
}
