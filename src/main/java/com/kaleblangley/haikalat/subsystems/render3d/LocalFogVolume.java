package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** One uniform local medium used by the outdoor volume integrator. */
public record LocalFogVolume(Shape shape, Vector3f center, Vector3f extent,
                             float density, Vector3f color, float noiseScale,
                             float noiseAmount) {
    public enum Shape { SPHERE, BOX }

    public LocalFogVolume {
        shape = Objects.requireNonNull(shape, "shape");
        center = copyFinite(center, "center");
        extent = copyFinite(extent, "extent");
        if (shape == Shape.SPHERE && extent.x <= 0.0f) {
            throw new IllegalArgumentException("sphere radius must be positive");
        }
        if (shape == Shape.BOX && (extent.x <= 0.0f || extent.y <= 0.0f || extent.z <= 0.0f)) {
            throw new IllegalArgumentException("box extent must be positive on every axis");
        }
        if (!Float.isFinite(density) || density < 0.0f) {
            throw new IllegalArgumentException("density must be finite and non-negative");
        }
        color = copyFinite(color, "color");
        if (color.x < 0.0f || color.y < 0.0f || color.z < 0.0f) {
            throw new IllegalArgumentException("color must be non-negative");
        }
        if (!Float.isFinite(noiseScale) || noiseScale < 0.0f
                || !Float.isFinite(noiseAmount) || noiseAmount < 0.0f || noiseAmount > 1.0f) {
            throw new IllegalArgumentException("noise parameters are outside supported ranges");
        }
    }

    public LocalFogVolume(Shape shape, Vector3fc center, Vector3fc extent,
                          float density, Vector3fc color, float noiseScale,
                          float noiseAmount) {
        this(shape, new Vector3f(Objects.requireNonNull(center, "center")),
                new Vector3f(Objects.requireNonNull(extent, "extent")), density,
                new Vector3f(Objects.requireNonNull(color, "color")), noiseScale, noiseAmount);
    }

    public static LocalFogVolume sphere(Vector3fc center, float radius, float density,
                                        Vector3fc color) {
        return new LocalFogVolume(Shape.SPHERE, center, new Vector3f(radius, 0.0f, 0.0f),
                density, color, 0.0f, 0.0f);
    }

    public static LocalFogVolume box(Vector3fc center, Vector3fc halfExtents, float density,
                                     Vector3fc color) {
        return new LocalFogVolume(Shape.BOX, center, halfExtents, density, color, 0.0f, 0.0f);
    }

    @Override public Vector3f center() { return new Vector3f(center); }
    @Override public Vector3f extent() { return new Vector3f(extent); }
    @Override public Vector3f color() { return new Vector3f(color); }

    Vector3f centerInternal() { return center; }
    Vector3f extentInternal() { return extent; }
    Vector3f colorInternal() { return color; }

    private static Vector3f copyFinite(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isFinite()) {
            throw new IllegalArgumentException(name + " must contain finite components");
        }
        return new Vector3f(value);
    }
}
