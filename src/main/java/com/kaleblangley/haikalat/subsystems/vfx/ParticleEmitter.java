package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/** 无 OpenGL 依赖的确定性 CPU 粒子发射器定义。 */
public record ParticleEmitter(
        int maxParticles,
        float spawnRate,
        float lifetimeSeconds,
        Vector3f direction,
        float coneRadians,
        float minimumSpeed,
        float maximumSpeed,
        Vector3f acceleration,
        float drag,
        float startSize,
        float endSize,
        Vector4f startColor,
        Vector4f endColor
) {
    public ParticleEmitter {
        if (maxParticles <= 0) throw new IllegalArgumentException("maxParticles must be positive");
        requireFiniteNonNegative(spawnRate, "spawnRate");
        requireFinitePositive(lifetimeSeconds, "lifetimeSeconds");
        direction = new Vector3f(Objects.requireNonNull(direction, "direction"));
        acceleration = new Vector3f(Objects.requireNonNull(acceleration, "acceleration"));
        startColor = new Vector4f(Objects.requireNonNull(startColor, "startColor"));
        endColor = new Vector4f(Objects.requireNonNull(endColor, "endColor"));
        requireFinite(direction, "direction");
        requireFinite(acceleration, "acceleration");
        requireColor(startColor, "startColor");
        requireColor(endColor, "endColor");
        if (direction.lengthSquared() == 0.0f) {
            throw new IllegalArgumentException("direction must be non-zero");
        }
        direction.normalize();
        if (!Float.isFinite(coneRadians) || coneRadians < 0.0f
                || coneRadians > (float) Math.PI) {
            throw new IllegalArgumentException("coneRadians must be in [0, PI]");
        }
        requireFiniteNonNegative(minimumSpeed, "minimumSpeed");
        if (!Float.isFinite(maximumSpeed) || maximumSpeed < minimumSpeed) {
            throw new IllegalArgumentException("maximumSpeed must be finite and >= minimumSpeed");
        }
        requireFiniteNonNegative(drag, "drag");
        requireFinitePositive(startSize, "startSize");
        requireFiniteNonNegative(endSize, "endSize");
    }

    @Override
    public Vector3f direction() {
        return new Vector3f(direction);
    }

    @Override
    public Vector3f acceleration() {
        return new Vector3f(acceleration);
    }

    @Override
    public Vector4f startColor() {
        return new Vector4f(startColor);
    }

    @Override
    public Vector4f endColor() {
        return new Vector4f(endColor);
    }

    private static void requireFinite(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }

    static void requireColor(Vector4f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y)
                || !Float.isFinite(value.z) || !Float.isFinite(value.w)
                || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.w < 0.0f || value.w > 1.0f) {
            throw new IllegalArgumentException(name
                    + " must contain finite non-negative RGB and alpha in [0, 1]");
        }
    }

    static void requireFinitePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    static void requireFiniteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
