package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** Coefficients in world units: extinction per metre, bounded albedo and emission per metre. */
public record FogMediumSettings(float extinction, Vector3f albedo, Vector3f emission,
                                float baseHeight, float heightFalloff) {
    public static final float MAX_EXTINCTION = 64.0f;
    public static final float MAX_EMISSION = 16.0f;

    public FogMediumSettings {
        if (!Float.isFinite(extinction) || extinction < 0 || extinction > MAX_EXTINCTION) {
            throw new IllegalArgumentException("extinction must be in [0,64] per world unit");
        }
        albedo = bounded(albedo, 1, "albedo");
        emission = bounded(emission, MAX_EMISSION, "emission");
        if (!Float.isFinite(baseHeight) || !Float.isFinite(heightFalloff) || heightFalloff < 0) {
            throw new IllegalArgumentException("height parameters must be finite with non-negative falloff");
        }
    }

    public static FogMediumSettings vacuum() {
        return homogeneous(0, new Vector3f(), new Vector3f());
    }

    public static FogMediumSettings homogeneous(float extinction, Vector3fc albedo, Vector3fc emission) {
        return new FogMediumSettings(extinction, new Vector3f(albedo), new Vector3f(emission), 0, 0);
    }

    @Override public Vector3f albedo() { return new Vector3f(albedo); }
    @Override public Vector3f emission() { return new Vector3f(emission); }
    Vector3f albedoInternal() { return albedo; }
    Vector3f emissionInternal() { return emission; }
    boolean hasEmission() { return emission.lengthSquared() > 0; }

    static Vector3f bounded(Vector3f value, float maximum, String name) {
        Vector3f copy = new Vector3f(Objects.requireNonNull(value, name));
        if (!copy.isFinite() || copy.x < 0 || copy.y < 0 || copy.z < 0
                || copy.x > maximum || copy.y > maximum || copy.z > maximum) {
            throw new IllegalArgumentException(name + " components must be in [0," + maximum + "]");
        }
        return copy;
    }
}
