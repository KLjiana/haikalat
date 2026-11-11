package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.List;
import java.util.Objects;

/**
 * Immutable screen-space distortion settings for the HDR colour chain.
 *
 * <p>The effect is disabled by default and contributes no pass, shader or
 * attachment when inactive.  When enabled it requires HDR tone mapping and the
 * HDR VFX composite, because it consumes the composited HDR colour and the
 * scene depth written by that composite.  Parameters are bounded so a single
 * frame cannot fold or smear the image beyond the configured limit.</p>
 */
public record ScreenDistortionSettings(boolean enabled, float maximumOffsetUv,
                                       int maximumContributions, float edgeFeatherUv,
                                       ScreenDistortionSource source) {
    /** Largest accepted peak UV offset. */
    public static final float MAXIMUM_OFFSET_UV = 0.1f;
    /** Largest accepted edge feather in UV units. */
    public static final float MAXIMUM_EDGE_FEATHER_UV = 0.25f;

    public ScreenDistortionSettings {
        if (!Float.isFinite(maximumOffsetUv)
                || maximumOffsetUv <= 0.0f || maximumOffsetUv > MAXIMUM_OFFSET_UV) {
            throw new IllegalArgumentException("maximumOffsetUv must be finite and in (0, "
                    + MAXIMUM_OFFSET_UV + "]");
        }
        if (maximumContributions < 1
                || maximumContributions > ScreenDistortion.MAXIMUM_CONTRIBUTIONS) {
            throw new IllegalArgumentException("maximumContributions must be in [1, "
                    + ScreenDistortion.MAXIMUM_CONTRIBUTIONS + "]");
        }
        if (!Float.isFinite(edgeFeatherUv)
                || edgeFeatherUv < 0.0f || edgeFeatherUv > MAXIMUM_EDGE_FEATHER_UV) {
            throw new IllegalArgumentException("edgeFeatherUv must be finite and in [0, "
                    + MAXIMUM_EDGE_FEATHER_UV + "]");
        }
        if (enabled && source == null) {
            throw new IllegalArgumentException("enabled screen distortion requires a source");
        }
    }

    public static ScreenDistortionSettings disabled() {
        return new ScreenDistortionSettings(false, 0.02f, 4, 0.05f, null);
    }

    /** Enables the effect for a producer with conservative defaults. */
    public static ScreenDistortionSettings of(ScreenDistortionSource source) {
        return new ScreenDistortionSettings(true, 0.02f, 4, 0.05f,
                Objects.requireNonNull(source, "source"));
    }

    /** Disabled is an exact image and topology no-op: no pass and no shader. */
    public boolean active() {
        return enabled;
    }

    /** Queries the producer for this frame; disabled settings may still expose authored data but add no pass. */
    public List<ScreenDistortion> contributions() {
        return source == null ? List.of() : source.screenDistortions();
    }

    public ScreenDistortionSettings withEnabled(boolean value) {
        return new ScreenDistortionSettings(value, maximumOffsetUv, maximumContributions,
                edgeFeatherUv, source);
    }

    public ScreenDistortionSettings withMaximumOffsetUv(float value) {
        return new ScreenDistortionSettings(enabled, value, maximumContributions,
                edgeFeatherUv, source);
    }

    public ScreenDistortionSettings withMaximumContributions(int value) {
        return new ScreenDistortionSettings(enabled, maximumOffsetUv, value,
                edgeFeatherUv, source);
    }

    public ScreenDistortionSettings withEdgeFeatherUv(float value) {
        return new ScreenDistortionSettings(enabled, maximumOffsetUv, maximumContributions,
                value, source);
    }

    public ScreenDistortionSettings withSource(ScreenDistortionSource value) {
        return new ScreenDistortionSettings(enabled, maximumOffsetUv, maximumContributions,
                edgeFeatherUv, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled;
        private float maximumOffsetUv = 0.02f;
        private int maximumContributions = 4;
        private float edgeFeatherUv = 0.05f;
        private ScreenDistortionSource source;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder maximumOffsetUv(float value) {
            maximumOffsetUv = value;
            return this;
        }

        public Builder maximumContributions(int value) {
            maximumContributions = value;
            return this;
        }

        public Builder edgeFeatherUv(float value) {
            edgeFeatherUv = value;
            return this;
        }

        public Builder source(ScreenDistortionSource value) {
            source = value;
            return this;
        }

        public ScreenDistortionSettings build() {
            return new ScreenDistortionSettings(enabled, maximumOffsetUv,
                    maximumContributions, edgeFeatherUv, source);
        }
    }
}
