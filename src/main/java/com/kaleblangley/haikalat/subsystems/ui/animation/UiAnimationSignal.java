package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.subsystems.ui.UiId;

import java.util.Objects;

/** Stable cross-subsystem signal emitted by a UI timeline. */
public record UiAnimationSignal(UiId source, long sequence, Type type,
                                float normalizedTime, String payload) {
    public UiAnimationSignal {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(type, "type");
        payload = Objects.requireNonNullElse(payload, "");
        if (sequence <= 0L) throw new IllegalArgumentException("sequence must be positive");
        if (!Float.isFinite(normalizedTime)
                || normalizedTime < 0.0f || normalizedTime > 1.0f) {
            throw new IllegalArgumentException("normalizedTime must be within [0, 1]");
        }
    }

    public enum Type { START, MARKER, COMPLETE, CANCEL }
}
