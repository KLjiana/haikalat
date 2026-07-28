package com.kaleblangley.haikalat.subsystems.ui.animation;

import java.util.Objects;

/** Named marker inside one animation step. */
public record UiAnimationTrigger(float normalizedTime, String payload) {
    public UiAnimationTrigger {
        if (!Float.isFinite(normalizedTime)
                || normalizedTime < 0.0f || normalizedTime > 1.0f) {
            throw new IllegalArgumentException("normalizedTime must be within [0, 1]");
        }
        payload = Objects.requireNonNull(payload, "payload");
        if (payload.isBlank()) throw new IllegalArgumentException("payload must not be blank");
    }
}
