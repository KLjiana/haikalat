package com.kaleblangley.haikalat.subsystems.postprocess.vrs;

import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingCapability;
import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingRate;

import java.util.Objects;

/**
 * Request for selective fragment shading-rate control inside the colour chain.
 *
 * <p>The request describes a screen-space split: a centred rectangular region
 * keeps the ordinary one-invocation-per-pixel rate, while the remaining tiles
 * use a coarser footprint.  It is intentionally a pure value object — no OpenGL
 * call, resource or capability probe happens until a pipeline generation is
 * assembled and the request is active.</p>
 *
 * <p>The first declared consumer is the screen-space subsurface-scattering
 * full-screen pass.  A request without that consumer is rejected by
 * {@code PostProcessPipeline} instead of silently doing nothing.  When the
 * extension is absent the request is reported as a fallback and the consumer is
 * rendered at full rate; fallback is never reported as an active VRS
 * implementation.</p>
 *
 * <p><b>Quality caveat.</b> A coarse fragment shader invocation computes one
 * value for its whole footprint.  Per-pixel features thinner than the coarse
 * footprint (hair, wire, one-pixel lines, alpha fringes) and shading that
 * depends on {@code gl_FragCoord} lose spatial resolution.  Derivative values
 * and implicit texture LODs are only defined per invocation; shaders that rely
 * on them should use explicit LODs or texel fetches when consumed at a coarse
 * rate.  The declared subsurface consumer uses explicit LOD 0 for that
 * reason.</p>
 */
public record VariableRateShadingSettings(
        boolean enabled,
        VariableRateShadingRate fineRate,
        VariableRateShadingRate coarseRate,
        float fineRegionFraction) {

    /** Smallest accepted centred fine-region fraction. */
    public static final float MIN_FINE_REGION_FRACTION = 0.05f;
    /** Largest accepted centred fine-region fraction (exclusive; 1.0 is no-op). */
    public static final float MAX_FINE_REGION_FRACTION = 0.95f;

    public VariableRateShadingSettings {
        Objects.requireNonNull(fineRate, "fineRate");
        Objects.requireNonNull(coarseRate, "coarseRate");
        if (fineRate != VariableRateShadingRate.ONE_BY_ONE) {
            throw new IllegalArgumentException(
                    "the fine region must use the one-invocation-per-pixel rate");
        }
        if (coarseRate.fullRate()) {
            throw new IllegalArgumentException(
                    "the coarse region must use a footprint larger than one pixel");
        }
        if (!Float.isFinite(fineRegionFraction)
                || fineRegionFraction < MIN_FINE_REGION_FRACTION
                || fineRegionFraction > MAX_FINE_REGION_FRACTION) {
            throw new IllegalArgumentException("fineRegionFraction must be finite and within ["
                    + MIN_FINE_REGION_FRACTION + ", " + MAX_FINE_REGION_FRACTION + "], got "
                    + fineRegionFraction);
        }
    }

    /** @return the exact no-op request; it allocates nothing and records nothing */
    public static VariableRateShadingSettings disabled() {
        return new VariableRateShadingSettings(false, VariableRateShadingRate.ONE_BY_ONE,
                VariableRateShadingRate.TWO_BY_TWO, 0.5f);
    }

    /** @return a builder whose default request is active with a 2x2 coarse periphery */
    public static Builder builder() {
        return new Builder();
    }

    /** @return whether the request asks for selective shading at all */
    public boolean active() {
        return enabled;
    }

    /** @return a copy with the request enabled flag replaced and every other value preserved */
    public VariableRateShadingSettings withEnabled(boolean value) {
        return new VariableRateShadingSettings(value, fineRate, coarseRate, fineRegionFraction);
    }

    /**
     * Pure capability decision shared by the pipeline and unit tests.
     *
     * @param capability runtime capability; may be {@code null} for "not probed"
     * @return the effective outcome
     */
    public Decision decide(VariableRateShadingCapability capability) {
        if (!enabled) return Decision.DISABLED;
        if (capability != null && capability.nvImagePathAvailable()) {
            return Decision.ACTIVE_NV_IMAGE;
        }
        return Decision.FALLBACK_UNSUPPORTED;
    }

    /** @return a stable one-line request description for diagnostics */
    public String summary() {
        if (!enabled) return "disabled";
        return "fine=" + fineRate.footprint() + " coarse=" + coarseRate.footprint()
                + " centre=" + Math.round(fineRegionFraction * 100.0f) + "%";
    }

    /** Effective outcome of a request against a probed capability. */
    public enum Decision {
        /** The request is disabled and must be a byte-exact no-op. */
        DISABLED,
        /** The NV per-tile image path is usable. */
        ACTIVE_NV_IMAGE,
        /** The request is enabled but no implemented extension path exists. */
        FALLBACK_UNSUPPORTED
    }

    /** Builder for {@link VariableRateShadingSettings}. */
    public static final class Builder {
        private boolean enabled = true;
        private VariableRateShadingRate fineRate = VariableRateShadingRate.ONE_BY_ONE;
        private VariableRateShadingRate coarseRate = VariableRateShadingRate.TWO_BY_TWO;
        private float fineRegionFraction = 0.5f;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder fineRate(VariableRateShadingRate value) {
            fineRate = Objects.requireNonNull(value, "fineRate");
            return this;
        }

        public Builder coarseRate(VariableRateShadingRate value) {
            coarseRate = Objects.requireNonNull(value, "coarseRate");
            return this;
        }

        public Builder fineRegionFraction(float value) {
            fineRegionFraction = value;
            return this;
        }

        public VariableRateShadingSettings build() {
            return new VariableRateShadingSettings(enabled, fineRate, coarseRate,
                    fineRegionFraction);
        }
    }
}
