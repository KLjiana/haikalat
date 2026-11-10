package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * GL-free artistic rim-light definition for mesh VFX.
 *
 * <p>The term is {@code pow(1 - clamp(dot(N, V), 0, 1), exponent)} evaluated
 * with the world normal and the vector from the surface to the camera.  It is
 * an art-directed edge glow, not a second physical Fresnel BRDF, and never
 * changes alpha by itself.  The material mask still bounds the contribution.</p>
 */
public record VfxRimLight(Vector3f color, float intensity, float exponent) {
    public VfxRimLight {
        color = new Vector3f(Objects.requireNonNull(color, "color"));
        requireFinite(color, "color");
        if (color.x < 0.0f || color.y < 0.0f || color.z < 0.0f) {
            throw new IllegalArgumentException("rim color components must be non-negative");
        }
        if (!Float.isFinite(intensity) || intensity < 0.0f) {
            throw new IllegalArgumentException("rim intensity must be finite and non-negative");
        }
        if (!Float.isFinite(exponent) || exponent <= 0.0f) {
            throw new IllegalArgumentException("rim exponent must be finite and positive");
        }
    }

    public static VfxRimLight of(Vector3f color, float intensity, float exponent) {
        return new VfxRimLight(color, intensity, exponent);
    }

    /** Analytic scalar used by the independent JVM reference and documentation. */
    public static float weight(float normalDotView, float exponent) {
        if (!Float.isFinite(normalDotView) || !Float.isFinite(exponent) || exponent <= 0.0f) {
            throw new IllegalArgumentException("normalDotView and exponent must be finite");
        }
        float clamped = Math.max(0.0f, Math.min(1.0f, normalDotView));
        return (float) Math.pow(1.0f - clamped, exponent);
    }

    @Override
    public Vector3f color() {
        return new Vector3f(color);
    }

    private static void requireFinite(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }
}
