package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** Linear-space gradient, sun-disc and halo parameters for an outdoor sky. */
public record StylizedSkySettings(Vector3f zenithColor, Vector3f horizonColor,
                                  Vector3f nadirColor, Vector3f sunDirection,
                                  Vector3f sunColor, float sunIntensity,
                                  float sunAngularRadius, float haloIntensity,
                                  float environmentIntensity) {
    public StylizedSkySettings {
        zenithColor = color(zenithColor, "zenithColor");
        horizonColor = color(horizonColor, "horizonColor");
        nadirColor = color(nadirColor, "nadirColor");
        sunDirection = direction(sunDirection, "sunDirection");
        sunColor = color(sunColor, "sunColor");
        nonNegative(sunIntensity, "sunIntensity");
        if (!Float.isFinite(sunAngularRadius) || sunAngularRadius <= 0.0f
                || sunAngularRadius > 0.5f) {
            throw new IllegalArgumentException("sunAngularRadius must be in (0, 0.5]");
        }
        nonNegative(haloIntensity, "haloIntensity");
        nonNegative(environmentIntensity, "environmentIntensity");
    }

    public StylizedSkySettings(Vector3fc zenithColor, Vector3fc horizonColor, Vector3fc nadirColor,
                               Vector3fc sunDirection, Vector3fc sunColor, float sunIntensity,
                               float sunAngularRadius, float haloIntensity, float environmentIntensity) {
        this(new Vector3f(Objects.requireNonNull(zenithColor, "zenithColor")),
                new Vector3f(Objects.requireNonNull(horizonColor, "horizonColor")),
                new Vector3f(Objects.requireNonNull(nadirColor, "nadirColor")),
                new Vector3f(Objects.requireNonNull(sunDirection, "sunDirection")),
                new Vector3f(Objects.requireNonNull(sunColor, "sunColor")), sunIntensity,
                sunAngularRadius, haloIntensity, environmentIntensity);
    }

    public static StylizedSkySettings morningFog() {
        return new StylizedSkySettings(new Vector3f(0.16f, 0.30f, 0.52f),
                new Vector3f(0.68f, 0.76f, 0.82f), new Vector3f(0.18f, 0.22f, 0.28f),
                new Vector3f(-0.45f, -0.78f, -0.28f), new Vector3f(1.0f, 0.62f, 0.32f),
                3.0f, 0.035f, 0.45f, 0.75f);
    }

    public static StylizedSkySettings clearDay() {
        return new StylizedSkySettings(new Vector3f(0.12f, 0.38f, 0.85f),
                new Vector3f(0.55f, 0.78f, 0.98f), new Vector3f(0.30f, 0.40f, 0.52f),
                new Vector3f(-0.35f, -0.90f, -0.20f), new Vector3f(1.0f, 0.93f, 0.78f),
                2.4f, 0.03f, 0.25f, 0.9f);
    }

    public static StylizedSkySettings goldenHour() {
        return new StylizedSkySettings(new Vector3f(0.24f, 0.16f, 0.34f),
                new Vector3f(0.92f, 0.42f, 0.18f), new Vector3f(0.22f, 0.16f, 0.20f),
                new Vector3f(0.30f, -0.55f, -0.74f), new Vector3f(1.0f, 0.46f, 0.16f),
                2.8f, 0.04f, 0.55f, 0.8f);
    }

    @Override public Vector3f zenithColor() { return new Vector3f(zenithColor); }
    @Override public Vector3f horizonColor() { return new Vector3f(horizonColor); }
    @Override public Vector3f nadirColor() { return new Vector3f(nadirColor); }
    @Override public Vector3f sunDirection() { return new Vector3f(sunDirection); }
    @Override public Vector3f sunColor() { return new Vector3f(sunColor); }

    Vector3f horizonColorInternal() { return horizonColor; }
    Vector3f sunDirectionInternal() { return sunDirection; }
    Vector3f sunColorInternal() { return sunColor; }

    private static Vector3f color(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isFinite() || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return new Vector3f(value);
    }

    private static Vector3f direction(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isFinite()) throw new IllegalArgumentException(name + " must be finite");
        Vector3f result = new Vector3f(value);
        if (result.lengthSquared() <= 1.0e-10f) throw new IllegalArgumentException(name + " must be non-zero");
        return result.normalize();
    }

    private static void nonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) throw new IllegalArgumentException(name + " must be finite and non-negative");
    }
}
