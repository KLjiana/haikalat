package com.kaleblangley.haikalat.subsystems.render3d.transparency;

/**
 * Bounded configuration of the weighted blended order-independent transparency
 * (WBOIT) collection.
 *
 * <p>The pass accumulates the selected transparent quads into two local MRT
 * attachments and resolves the weighted average over the caller's HDR target.
 * It is an approximation of back-to-front alpha blending, not an exact
 * transparent sort: the resolved colour uses one weighted average per pixel and
 * the transmittance is the exact product {@code prod(1 - alpha)}. A sorted
 * reference therefore agrees exactly only when all layers share one colour;
 * otherwise the error is bounded by the layer alpha spread (see the S3 #28
 * technical card, section "权重公式与误差").</p>
 *
 * <p>Depth weighting follows the published WBOIT idea: nearer surfaces receive
 * a larger weight so the weighted average is not dominated by a dense far
 * layer. The implemented weight is</p>
 *
 * <pre>{@code
 * weight(z) = clamp(depthScale / (1 + (|z| / referenceDepth)^2),
 *                   minimumWeight, maximumWeight)
 * }</pre>
 *
 * <p>with {@code z} the positive view-space depth in metres. The function is
 * bounded, monotone decreasing in {@code |z|}, finite at {@code z = 0}, and
 * reduces to the exact value {@code 1.0} when {@link #depthWeighting()} is
 * {@code false}. Because the resolve divides by {@code sum(alpha * weight)},
 * the absolute scale of the weight cancels; it only changes the relative
 * weighting and the float16 accumulation range.</p>
 *
 * <p>{@link #disabled()} is the exact no-op value: the integration layer must
 * not construct {@link WeightedOitPass}, an attachment or a draw while
 * {@link #active()} is {@code false}.</p>
 */
public record WeightedOitSettings(
        boolean enabled,
        boolean depthWeighting,
        float depthScale,
        float referenceDepth,
        float minimumWeight,
        float maximumWeight,
        float alphaThreshold,
        int maximumSurfaces) {

    /** Smallest accepted weight numerator. */
    public static final float MINIMUM_DEPTH_SCALE = 0.001f;
    /** Largest accepted weight numerator. */
    public static final float MAXIMUM_DEPTH_SCALE = 1_000.0f;
    /** Smallest accepted reference depth in world metres. */
    public static final float MINIMUM_REFERENCE_DEPTH = 0.01f;
    /** Largest accepted reference depth in world metres. */
    public static final float MAXIMUM_REFERENCE_DEPTH = 1_000_000.0f;
    /** Largest accepted weight floor or cap. */
    public static final float MAXIMUM_WEIGHT_LIMIT = 1_000.0f;
    /** Largest accepted per-layer alpha discard threshold. */
    public static final float MAXIMUM_ALPHA_THRESHOLD = 0.999f;
    /** Smallest accepted quad capacity. */
    public static final int MINIMUM_SURFACES = 1;
    /** Largest accepted quad capacity. */
    public static final int MAXIMUM_SURFACES = 65_536;

    /** Default weight numerator for the depth-weighting branch. */
    public static final float DEFAULT_DEPTH_SCALE = 10.0f;
    /** Default half-weight view depth in metres. */
    public static final float DEFAULT_REFERENCE_DEPTH = 20.0f;
    /** Default weight floor (the published WBOIT order of magnitude). */
    public static final float DEFAULT_MINIMUM_WEIGHT = 1.0e-2f;
    /** Default weight cap (also keeps the float16 accumulator in range). */
    public static final float DEFAULT_MAXIMUM_WEIGHT = 300.0f;
    /** Default per-layer alpha discard threshold. */
    public static final float DEFAULT_ALPHA_THRESHOLD = 0.0f;
    /** Default maximum number of quads in one collection. */
    public static final int DEFAULT_MAXIMUM_SURFACES = 4_096;

    public WeightedOitSettings {
        if (!Float.isFinite(depthScale) || depthScale < MINIMUM_DEPTH_SCALE
                || depthScale > MAXIMUM_DEPTH_SCALE) {
            throw new IllegalArgumentException("depthScale must be finite and in ["
                    + MINIMUM_DEPTH_SCALE + ", " + MAXIMUM_DEPTH_SCALE + "]");
        }
        if (!Float.isFinite(referenceDepth) || referenceDepth < MINIMUM_REFERENCE_DEPTH
                || referenceDepth > MAXIMUM_REFERENCE_DEPTH) {
            throw new IllegalArgumentException("referenceDepth must be finite and in ["
                    + MINIMUM_REFERENCE_DEPTH + ", " + MAXIMUM_REFERENCE_DEPTH + "]");
        }
        if (!Float.isFinite(minimumWeight) || minimumWeight < 0.0f
                || minimumWeight > MAXIMUM_WEIGHT_LIMIT) {
            throw new IllegalArgumentException("minimumWeight must be finite and in [0, "
                    + MAXIMUM_WEIGHT_LIMIT + "]");
        }
        if (!Float.isFinite(maximumWeight) || maximumWeight <= 0.0f
                || maximumWeight > MAXIMUM_WEIGHT_LIMIT
                || maximumWeight < minimumWeight) {
            throw new IllegalArgumentException("maximumWeight must be finite, in (0, "
                    + MAXIMUM_WEIGHT_LIMIT + "] and no smaller than minimumWeight");
        }
        if (!Float.isFinite(alphaThreshold) || alphaThreshold < 0.0f
                || alphaThreshold > MAXIMUM_ALPHA_THRESHOLD) {
            throw new IllegalArgumentException("alphaThreshold must be finite and in [0, "
                    + MAXIMUM_ALPHA_THRESHOLD + "]");
        }
        if (maximumSurfaces < MINIMUM_SURFACES || maximumSurfaces > MAXIMUM_SURFACES) {
            throw new IllegalArgumentException("maximumSurfaces must be in ["
                    + MINIMUM_SURFACES + ", " + MAXIMUM_SURFACES + "]");
        }
    }

    /** Exact no-op value used when the OIT collection is absent or suppressed. */
    public static WeightedOitSettings disabled() {
        return new WeightedOitSettings(false, true, DEFAULT_DEPTH_SCALE,
                DEFAULT_REFERENCE_DEPTH, DEFAULT_MINIMUM_WEIGHT, DEFAULT_MAXIMUM_WEIGHT,
                DEFAULT_ALPHA_THRESHOLD, DEFAULT_MAXIMUM_SURFACES);
    }

    /** @return true when the pass, attachments and draws should exist */
    public boolean active() {
        return enabled;
    }

    /**
     * CPU counterpart of the shader's {@code weightForViewDepth} function.
     *
     * <p>The computation uses double precision and then clamps to the
     * configured bounds, so the JVM oracle can pin the exact formula. The GLSL
     * implementation evaluates the same expression in single precision; a
     * relative difference of a few {@code 1e-6} is expected for the resolve
     * contract and is covered by the shader source test.</p>
     *
     * @param viewDepthMetres view-space depth; the sign is ignored
     * @return a finite weight in {@code [minimumWeight, maximumWeight]}, or
     *         exactly {@code 1.0} when depth weighting is disabled
     */
    public double weightForViewDepth(double viewDepthMetres) {
        if (!Double.isFinite(viewDepthMetres)) {
            throw new IllegalArgumentException("viewDepthMetres must be finite");
        }
        if (!depthWeighting) {
            return 1.0;
        }
        double z = Math.abs(viewDepthMetres);
        double normalized = z / (double) referenceDepth;
        double weight = (double) depthScale / (1.0 + normalized * normalized);
        return Math.min((double) maximumWeight, Math.max((double) minimumWeight, weight));
    }

    /** Bounded copy of this value with only the enabled flag changed. */
    public WeightedOitSettings withEnabled(boolean value) {
        return new WeightedOitSettings(value, depthWeighting, depthScale, referenceDepth,
                minimumWeight, maximumWeight, alphaThreshold, maximumSurfaces);
    }

    /** Bounded copy of this value with only the quad capacity changed. */
    public WeightedOitSettings withMaximumSurfaces(int value) {
        return new WeightedOitSettings(enabled, depthWeighting, depthScale, referenceDepth,
                minimumWeight, maximumWeight, alphaThreshold, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder mirroring the record's validated ranges. */
    public static final class Builder {
        private boolean enabled;
        private boolean depthWeighting = true;
        private float depthScale = DEFAULT_DEPTH_SCALE;
        private float referenceDepth = DEFAULT_REFERENCE_DEPTH;
        private float minimumWeight = DEFAULT_MINIMUM_WEIGHT;
        private float maximumWeight = DEFAULT_MAXIMUM_WEIGHT;
        private float alphaThreshold = DEFAULT_ALPHA_THRESHOLD;
        private int maximumSurfaces = DEFAULT_MAXIMUM_SURFACES;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder depthWeighting(boolean value) {
            depthWeighting = value;
            return this;
        }

        public Builder depthScale(float value) {
            depthScale = value;
            return this;
        }

        public Builder referenceDepth(float value) {
            referenceDepth = value;
            return this;
        }

        public Builder minimumWeight(float value) {
            minimumWeight = value;
            return this;
        }

        public Builder maximumWeight(float value) {
            maximumWeight = value;
            return this;
        }

        public Builder alphaThreshold(float value) {
            alphaThreshold = value;
            return this;
        }

        public Builder maximumSurfaces(int value) {
            maximumSurfaces = value;
            return this;
        }

        public WeightedOitSettings build() {
            return new WeightedOitSettings(enabled, depthWeighting, depthScale, referenceDepth,
                    minimumWeight, maximumWeight, alphaThreshold, maximumSurfaces);
        }
    }
}
