package com.kaleblangley.haikalat.subsystems.postprocess;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** 有界屏幕空间体积聚光实验参数。 */
public final class VolumetricLightSettings {
    public static final int MAX_STEPS = 128;

    private final int steps;
    private final float maximumDistance;
    private final float density;
    private final float anisotropy;
    private final float range;
    private final float innerConeCosine;
    private final float outerConeCosine;
    private final float intensity;
    private final Vector3f lightPosition;
    private final Vector3f lightDirection;
    private final Vector3f lightColor;

    public VolumetricLightSettings(int steps, float maximumDistance, float density,
                                   float anisotropy, float range,
                                   float innerConeCosine, float outerConeCosine,
                                   float intensity, Vector3fc lightPosition,
                                   Vector3fc lightDirection, Vector3fc lightColor) {
        if (steps < 4 || steps > MAX_STEPS) {
            throw new IllegalArgumentException("steps must be in [4, " + MAX_STEPS + "]");
        }
        requirePositive(maximumDistance, "maximumDistance");
        requireNonNegative(density, "density");
        if (!Float.isFinite(anisotropy) || anisotropy <= -0.95f || anisotropy >= 0.95f) {
            throw new IllegalArgumentException("anisotropy must be finite and in (-0.95, 0.95)");
        }
        requirePositive(range, "range");
        if (!Float.isFinite(innerConeCosine) || !Float.isFinite(outerConeCosine)
                || innerConeCosine < outerConeCosine
                || innerConeCosine > 1.0f || outerConeCosine < -1.0f) {
            throw new IllegalArgumentException("cone cosines must satisfy -1 <= outer <= inner <= 1");
        }
        requireNonNegative(intensity, "intensity");
        this.steps = steps;
        this.maximumDistance = maximumDistance;
        this.density = density;
        this.anisotropy = anisotropy;
        this.range = range;
        this.innerConeCosine = innerConeCosine;
        this.outerConeCosine = outerConeCosine;
        this.intensity = intensity;
        this.lightPosition = copyFinite(lightPosition, "lightPosition");
        this.lightDirection = copyFinite(lightDirection, "lightDirection");
        if (this.lightDirection.lengthSquared() <= 1.0e-12f) {
            throw new IllegalArgumentException("lightDirection must be non-zero");
        }
        this.lightDirection.normalize();
        this.lightColor = copyFinite(lightColor, "lightColor");
        if (this.lightColor.x < 0.0f || this.lightColor.y < 0.0f || this.lightColor.z < 0.0f) {
            throw new IllegalArgumentException("lightColor must be non-negative");
        }
    }

    public int steps() { return steps; }
    public float maximumDistance() { return maximumDistance; }
    public float density() { return density; }
    public float anisotropy() { return anisotropy; }
    public float range() { return range; }
    public float innerConeCosine() { return innerConeCosine; }
    public float outerConeCosine() { return outerConeCosine; }
    public float intensity() { return intensity; }
    public Vector3f lightPosition() { return new Vector3f(lightPosition); }
    public Vector3f lightDirection() { return new Vector3f(lightDirection); }
    public Vector3f lightColor() { return new Vector3f(lightColor); }

    private static Vector3f copyFinite(Vector3fc value, String name) {
        Objects.requireNonNull(value, name);
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must contain finite components");
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
}
