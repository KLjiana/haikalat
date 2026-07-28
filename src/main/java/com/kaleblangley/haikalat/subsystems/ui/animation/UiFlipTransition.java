package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;

import java.util.Objects;

/** First/last/invert data for an explicitly opted-in FLIP layout transition. */
public record UiFlipTransition(LayoutBox first, LayoutBox last,
                               UiVisualTransform inverseTransform) {
    public UiFlipTransition {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(last, "last");
        Objects.requireNonNull(inverseTransform, "inverseTransform");
    }

    public static UiFlipTransition between(LayoutBox first, LayoutBox last) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(last, "last");
        if (first.width() <= 0.0f || first.height() <= 0.0f
                || last.width() <= 0.0f || last.height() <= 0.0f) {
            throw new IllegalArgumentException("FLIP bounds must have positive dimensions");
        }
        double firstCenterX = first.x() + first.width() * 0.5;
        double firstCenterY = first.y() + first.height() * 0.5;
        double lastCenterX = last.x() + last.width() * 0.5;
        double lastCenterY = last.y() + last.height() * 0.5;
        UiVisualTransform inverse = new UiVisualTransform(
                firstCenterX - lastCenterX, firstCenterY - lastCenterY,
                first.width() / last.width(), first.height() / last.height(),
                0.0, 0.5, 0.5);
        return new UiFlipTransition(first, last, inverse);
    }
}
