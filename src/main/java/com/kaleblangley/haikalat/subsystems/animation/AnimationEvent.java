package com.kaleblangley.haikalat.subsystems.animation;

import java.util.Objects;

/** 动画时间轴上的不可变动作事件。 */
public record AnimationEvent(float timeSeconds, String name, String payload) {
    public AnimationEvent {
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            throw new IllegalArgumentException("timeSeconds must be finite and non-negative");
        }
        name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        payload = Objects.requireNonNull(payload, "payload");
    }

    public AnimationEvent(float timeSeconds, String name) {
        this(timeSeconds, name, "");
    }
}
