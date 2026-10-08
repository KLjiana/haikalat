package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** Local additive coefficients; box extent is a half size and sphere extent.x a radius. */
public record LocalFogVolume(Shape shape, Vector3f center, Vector3f extent,
                             float extinction, Vector3f albedo, Vector3f emission,
                             float falloff, float noiseScale, float noiseAmount) {
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
        if (!Float.isFinite(extinction) || extinction < 0.0f || extinction > 64.0f) {
            throw new IllegalArgumentException("extinction must be in [0,64]");
        }
        albedo = FogMediumSettings.bounded(albedo, 1, "albedo");
        emission = FogMediumSettings.bounded(emission, 16, "emission");
        if (!Float.isFinite(falloff) || falloff < 0 || falloff > 1) {
            throw new IllegalArgumentException("falloff must be in [0,1]");
        }
        if (!Float.isFinite(noiseScale) || noiseScale < 0.0f
                || !Float.isFinite(noiseAmount) || noiseAmount < 0.0f || noiseAmount > 1.0f) {
            throw new IllegalArgumentException("noise parameters are outside supported ranges");
        }
    }

    public LocalFogVolume(Shape shape, Vector3fc center, Vector3fc extent,
                          float extinction, Vector3fc albedo, Vector3fc emission,
                          float falloff, float noiseScale, float noiseAmount) {
        this(shape, new Vector3f(Objects.requireNonNull(center, "center")),
                new Vector3f(Objects.requireNonNull(extent, "extent")), extinction,
                new Vector3f(Objects.requireNonNull(albedo, "albedo")),
                new Vector3f(Objects.requireNonNull(emission, "emission")), falloff, noiseScale, noiseAmount);
    }

    public static LocalFogVolume sphere(Vector3fc center, float radius, float extinction,
                                        Vector3fc albedo) {
        return new LocalFogVolume(Shape.SPHERE, center, new Vector3f(radius, 0.0f, 0.0f),
                extinction, albedo, new Vector3f(), 0, 0, 0);
    }

    public static LocalFogVolume box(Vector3fc center, Vector3fc halfExtents, float extinction,
                                     Vector3fc albedo) {
        return new LocalFogVolume(Shape.BOX, center, halfExtents, extinction, albedo, new Vector3f(), 0, 0, 0);
    }

    @Override public Vector3f center() { return new Vector3f(center); }
    @Override public Vector3f extent() { return new Vector3f(extent); }
    @Override public Vector3f albedo() { return new Vector3f(albedo); }
    @Override public Vector3f emission() { return new Vector3f(emission); }

    Vector3f centerInternal() { return center; }
    Vector3f extentInternal() { return extent; }
    Vector3f albedoInternal() { return albedo; }
    Vector3f emissionInternal() { return emission; }

    private static Vector3f copyFinite(Vector3f value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isFinite()) {
            throw new IllegalArgumentException(name + " must contain finite components");
        }
        return new Vector3f(value);
    }
}
