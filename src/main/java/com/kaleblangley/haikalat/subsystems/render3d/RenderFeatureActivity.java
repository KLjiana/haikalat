package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.Objects;

/**
 * Actual participation of one feature in the last published frame plan.
 *
 * <p>{@code detail} carries the human-readable reason for an inactive feature
 * when the enum alone is not specific enough (for example which capability or
 * consumer is missing).  Active activities have an empty detail.  The
 * three-argument constructor is the source-compatible form for callers that
 * only inspect {@code feature}/{@code active}/{@code reason}.</p>
 */
public record RenderFeatureActivity(RenderFeature feature, boolean active,
                                    RenderFeatureReason reason, String detail) {
    public RenderFeatureActivity {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(reason, "reason");
        detail = detail == null ? "" : detail;
        if (active != (reason == RenderFeatureReason.ACTIVE)) {
            throw new IllegalArgumentException("active feature must use ACTIVE reason");
        }
    }

    public RenderFeatureActivity(RenderFeature feature, boolean active,
                                 RenderFeatureReason reason) {
        this(feature, active, reason, "");
    }
}
