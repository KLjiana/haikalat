package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/**
 * Immutable parameters for the v0.24 screen-space sun volume.
 *
 * <p>The values are scene-space values (one unit is one metre in the outdoor
 * sample).  The class deliberately contains no GL handles so a host can keep
 * and validate a preset before a pipeline generation is built.</p>
 */
public record VolumetricSunSettings(boolean enabled, int steps, int downsample,
                                    float maximumDistance, float density,
                                    Vector3f scatteringColor, float anisotropy,
                                    float historyWeight, float depthRejectThreshold,
                                    float noiseStrength) {
    public static final int MAX_STEPS = 128;

    public VolumetricSunSettings {
        if (steps < 4 || steps > MAX_STEPS) {
            throw new IllegalArgumentException("steps must be in [4, " + MAX_STEPS + "]");
        }
        if (downsample != 1 && downsample != 2 && downsample != 4) {
            throw new IllegalArgumentException("downsample must be 1, 2 or 4");
        }
        requirePositive(maximumDistance, "maximumDistance");
        requireNonNegative(density, "density");
        if (!Float.isFinite(anisotropy) || anisotropy <= -0.95f || anisotropy >= 0.95f) {
            throw new IllegalArgumentException("anisotropy must be finite and in (-0.95, 0.95)");
        }
        requireUnit(historyWeight, "historyWeight");
        requireNonNegative(depthRejectThreshold, "depthRejectThreshold");
        requireUnit(noiseStrength, "noiseStrength");
        scatteringColor = copyColor(scatteringColor);
    }

    public VolumetricSunSettings(boolean enabled, int steps, int downsample,
                                 float maximumDistance, float density,
                                 Vector3fc scatteringColor, float anisotropy,
                                 float historyWeight, float depthRejectThreshold,
                                 float noiseStrength) {
        this(enabled, steps, downsample, maximumDistance, density,
                new Vector3f(Objects.requireNonNull(scatteringColor, "scatteringColor")),
                anisotropy, historyWeight, depthRejectThreshold, noiseStrength);
    }

    public static VolumetricSunSettings disabled() {
        return new VolumetricSunSettings(false, 32, 2, 48.0f, 0.0f,
                new Vector3f(1.0f), 0.0f, 0.0f, 0.05f, 0.0f);
    }

    public static VolumetricSunSettings balanced() {
        return new VolumetricSunSettings(true, 32, 2, 64.0f, 0.012f,
                new Vector3f(0.82f, 0.90f, 1.0f), 0.35f, 0.86f, 0.08f, 0.35f);
    }

    public static VolumetricSunSettings high() {
        return new VolumetricSunSettings(true, 64, 2, 80.0f, 0.016f,
                new Vector3f(0.86f, 0.93f, 1.0f), 0.4f, 0.9f, 0.06f, 0.25f);
    }

    @Override
    public Vector3f scatteringColor() {
        return new Vector3f(scatteringColor);
    }

    public float scatteringRed() { return scatteringColor.x; }
    public float scatteringGreen() { return scatteringColor.y; }
    public float scatteringBlue() { return scatteringColor.z; }

    private static Vector3f copyColor(Vector3f value) {
        Objects.requireNonNull(value, "scatteringColor");
        if (!value.isFinite() || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f) {
            throw new IllegalArgumentException("scatteringColor must be finite and non-negative");
        }
        return new Vector3f(value);
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }
}
