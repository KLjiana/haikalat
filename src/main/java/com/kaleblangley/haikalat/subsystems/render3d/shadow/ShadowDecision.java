package com.kaleblangley.haikalat.subsystems.render3d.shadow;

import com.kaleblangley.haikalat.subsystems.render3d.LightType;

/** Terminal result for one shadow-capable scene light. */
public record ShadowDecision(long stableId, LightType type, int frameLightIndex,
                      Status status, int slot, int priority, float score) {
    public enum Status {
        SELECTED,
        DISABLED_BY_SETTINGS,
        INVALID_NEAR_FAR_RANGE,
        OUTSIDE_CAMERA_INFLUENCE,
        LOWER_PRIORITY,
        BUDGET_EXHAUSTED,
        HELD_BY_HYSTERESIS
    }
}
