package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.Objects;

/** Actual participation of one feature in the last published frame plan. */
public record RenderFeatureActivity(RenderFeature feature, boolean active,
                                    RenderFeatureReason reason) {
    public RenderFeatureActivity {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(reason, "reason");
        if (active != (reason == RenderFeatureReason.ACTIVE)) {
            throw new IllegalArgumentException("active feature must use ACTIVE reason");
        }
    }
}
