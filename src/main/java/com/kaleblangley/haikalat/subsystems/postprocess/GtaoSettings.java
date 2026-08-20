package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.Objects;

/** Immutable configuration for the screen-space GTAO path. */
public record GtaoSettings(
        boolean enabled,
        GtaoQuality quality,
        float radius,
        float strength,
        float thickness,
        boolean temporal,
        float historyWeight,
        float depthRejectionThreshold
) {
    public GtaoSettings {
        Objects.requireNonNull(quality, "quality");
        requireFinitePositive(radius, "radius");
        if (radius > 100.0f) {
            throw new IllegalArgumentException("radius must be <= 100");
        }
        requireRange(strength, 0.0f, 4.0f, "strength");
        requireRange(thickness, 0.0f, radius, "thickness");
        requireRange(historyWeight, 0.0f, 0.98f, "historyWeight");
        requireFinitePositive(depthRejectionThreshold, "depthRejectionThreshold");
        if (depthRejectionThreshold > 1.0f) {
            throw new IllegalArgumentException("depthRejectionThreshold must be <= 1");
        }
    }

    public static GtaoSettings defaults() {
        return new GtaoSettings(false, GtaoQuality.MEDIUM, 1.0f, 1.0f,
                0.25f, true, 0.90f, 0.02f);
    }

    public static GtaoSettings disabled() {
        return new GtaoSettings(false, GtaoQuality.MEDIUM, 1.0f, 1.0f,
                0.25f, true, 0.90f, 0.02f);
    }

    /** Creates an enabled configuration using the shared default tuning for one quality tier. */
    public static GtaoSettings quality(GtaoQuality value) {
        return defaults().withEnabled(true).withQuality(value);
    }

    public GtaoSettings withQuality(GtaoQuality value) {
        return new GtaoSettings(enabled, Objects.requireNonNull(value, "quality"), radius,
                strength, thickness, temporal, historyWeight, depthRejectionThreshold);
    }

    public GtaoSettings withEnabled(boolean value) {
        return new GtaoSettings(value, quality, radius, strength, thickness, temporal,
                historyWeight, depthRejectionThreshold);
    }

    private static void requireFinitePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireRange(float value, float minimum, float maximum, String name) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and in ["
                    + minimum + ", " + maximum + "]");
        }
    }
}
