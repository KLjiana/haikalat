package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.Objects;

/** Immutable color-grading configuration applied after tone mapping and before display encoding. */
public record ColorGradingSettings(boolean enabled, ColorGradingLut lut, float intensity) {
    private static final ColorGradingLut DISABLED_LUT = ColorGradingLut.identity(2);

    public ColorGradingSettings {
        Objects.requireNonNull(lut, "lut");
        if (!Float.isFinite(intensity) || intensity < 0.0f || intensity > 1.0f) {
            throw new IllegalArgumentException("color grading intensity must be within [0, 1]");
        }
    }

    public static ColorGradingSettings disabled() {
        return new ColorGradingSettings(false, DISABLED_LUT, 0.0f);
    }

    public static ColorGradingSettings of(ColorGradingLut lut, float intensity) {
        return new ColorGradingSettings(true, lut, intensity);
    }
}
