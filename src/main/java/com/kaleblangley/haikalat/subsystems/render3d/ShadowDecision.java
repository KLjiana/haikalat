package com.kaleblangley.haikalat.subsystems.render3d;

/** Terminal result for one shadow-capable scene light. */
record ShadowDecision(long stableId, LightType type, int shaderIndex,
                      Status status, int slot, int priority, float score) {
    enum Status {
        SELECTED,
        DISABLED_BY_SETTINGS,
        OUTSIDE_SHADER_LIMIT,
        INVALID_NEAR_FAR_RANGE,
        OUTSIDE_CAMERA_INFLUENCE,
        LOWER_PRIORITY,
        BUDGET_EXHAUSTED,
        HELD_BY_HYSTERESIS
    }
}
