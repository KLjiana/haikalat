package com.kaleblangley.haikalat.subsystems.ui.animation;

import java.util.Objects;

/** Timing configuration shared by visual and layout transitions. */
public record UiTweenSpec(float durationSeconds, float delaySeconds, UiEasing easing) {
    public UiTweenSpec {
        requireNonNegativeFinite(durationSeconds, "durationSeconds");
        requireNonNegativeFinite(delaySeconds, "delaySeconds");
        Objects.requireNonNull(easing, "easing");
    }

    public UiTweenSpec(float durationSeconds, UiEasing easing) {
        this(durationSeconds, 0.0f, easing);
    }

    public static UiTweenSpec easeOut(float durationSeconds) {
        return new UiTweenSpec(durationSeconds, UiEasing.EASE_OUT_CUBIC);
    }

    public static UiTweenSpec spring(float durationSeconds) {
        return new UiTweenSpec(durationSeconds, UiEasing.SPRING);
    }

    private static void requireNonNegativeFinite(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
