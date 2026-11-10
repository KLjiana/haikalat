package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Immutable dissolve/erosion definition for a VFX material.
 *
 * <p>{@code progress == 0} restores the untouched material and
 * {@code progress == 1} removes every covered fragment.  The visible side is
 * {@code noise >= progress}; an optional bounded glow is added on the visible
 * side of the threshold.  A zero edge width produces no glow and never divides
 * by zero.</p>
 */
public record VfxDissolve(ProgressSource progressSource, float edgeWidth,
                          Vector3f edgeColor, float edgeIntensity,
                          float noiseScale, long noiseSeed) {
    /** Where the per-frame dissolve progress comes from. */
    public enum ProgressSource {
        /** Primitive normalized age; particles and meshes expose their own value. */
        LIFECYCLE,
        /** Explicit effect-phase progress supplied with the frame snapshot. */
        EXPLICIT
    }

    public VfxDissolve {
        progressSource = Objects.requireNonNull(progressSource, "progressSource");
        if (!Float.isFinite(edgeWidth) || edgeWidth < 0.0f || edgeWidth > 1.0f) {
            throw new IllegalArgumentException("dissolve edgeWidth must be finite and in [0, 1]");
        }
        edgeColor = new Vector3f(Objects.requireNonNull(edgeColor, "edgeColor"));
        requireFinite(edgeColor, "edgeColor");
        if (edgeColor.x < 0.0f || edgeColor.y < 0.0f || edgeColor.z < 0.0f) {
            throw new IllegalArgumentException("dissolve edge color components must be non-negative");
        }
        if (!Float.isFinite(edgeIntensity) || edgeIntensity < 0.0f) {
            throw new IllegalArgumentException("dissolve edgeIntensity must be finite and non-negative");
        }
        if (!Float.isFinite(noiseScale) || noiseScale <= 0.0f) {
            throw new IllegalArgumentException("dissolve noiseScale must be finite and positive");
        }
    }

    public static VfxDissolve lifecycle(float edgeWidth, Vector3f edgeColor,
                                        float edgeIntensity, float noiseScale, long noiseSeed) {
        return new VfxDissolve(ProgressSource.LIFECYCLE, edgeWidth, edgeColor,
                edgeIntensity, noiseScale, noiseSeed);
    }

    public static VfxDissolve explicit(float edgeWidth, Vector3f edgeColor,
                                       float edgeIntensity, float noiseScale, long noiseSeed) {
        return new VfxDissolve(ProgressSource.EXPLICIT, edgeWidth, edgeColor,
                edgeIntensity, noiseScale, noiseSeed);
    }

    /** Analytic visibility reference for constant noise. */
    public static boolean visible(float noise, float progress) {
        if (!Float.isFinite(noise) || !Float.isFinite(progress)
                || progress < 0.0f || progress > 1.0f) {
            throw new IllegalArgumentException("noise and progress must be finite and bounded");
        }
        if (progress >= 1.0f) return false;
        if (progress <= 0.0f) return true;
        return noise >= progress;
    }

    /** Analytic edge glow reference; zero width yields zero. */
    public static float edgeGlow(float noise, float progress, float edgeWidth) {
        if (!visible(noise, progress)) return 0.0f;
        if (!Float.isFinite(edgeWidth) || edgeWidth <= 0.0f) return 0.0f;
        float distance = noise - progress;
        if (distance >= edgeWidth) return 0.0f;
        return 1.0f - distance / edgeWidth;
    }

    @Override
    public Vector3f edgeColor() {
        return new Vector3f(edgeColor);
    }

    private static void requireFinite(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }
}
