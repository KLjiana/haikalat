package com.kaleblangley.haikalat.subsystems.animation;

import java.util.Objects;

/** 用于相位同步和结构化 signal 的不可变动画标记。 */
public record AnimationMarker(float timeSeconds, String name, Priority priority) {
    public AnimationMarker(float timeSeconds, String name) {
        this(timeSeconds, name, Priority.NORMAL);
    }

    public AnimationMarker {
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            throw new IllegalArgumentException("timeSeconds must be finite and non-negative");
        }
        name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        priority = Objects.requireNonNull(priority, "priority");
    }

    public enum Priority {
        NORMAL,
        HIGH
    }
}
