package com.kaleblangley.haikalat.subsystems.ui.layout;

/** Yoga measure callback 的 subsystem-neutral 约束。 */
public record MeasureContext(float availableWidth, Mode widthMode,
                             float availableHeight, Mode heightMode) {
    public enum Mode { UNDEFINED, EXACTLY, AT_MOST }

    public MeasureContext {
        if (widthMode == null || heightMode == null) {
            throw new NullPointerException("measure mode");
        }
        if (!Float.isFinite(availableWidth) || !Float.isFinite(availableHeight)
                || availableWidth < 0.0f || availableHeight < 0.0f) {
            throw new IllegalArgumentException("measure constraints must be finite and non-negative");
        }
    }
}
