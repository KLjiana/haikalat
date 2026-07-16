package com.kaleblangley.haikalat.subsystems.ui.style;

/** 与 Yoga 无关的 UI 长度。 */
public record UiLength(Unit unit, float value) {
    public enum Unit { AUTO, POINTS, PERCENT }

    public static final UiLength AUTO = new UiLength(Unit.AUTO, 0.0f);

    public UiLength {
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException("UI length must be finite and non-negative");
        }
        if (unit == Unit.PERCENT && value > 100.0f) {
            throw new IllegalArgumentException("percentage length must be in [0, 100]");
        }
    }

    public static UiLength points(float value) {
        return new UiLength(Unit.POINTS, value);
    }

    public static UiLength percent(float value) {
        return new UiLength(Unit.PERCENT, value);
    }
}
