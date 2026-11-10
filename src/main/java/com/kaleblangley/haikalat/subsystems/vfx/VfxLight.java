package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * GL-free world-space light emitted by one effect instance.
 *
 * <p>The identity is {@code (effectId, lightIndex)}: it is stable for the life
 * of the instance and is mapped to a positive scene-compatible stable light ID
 * by the render3d bridge.  A list index, frame number or negative range is
 * never used as the long-lived identity.</p>
 */
public record VfxLight(long effectId, int lightIndex, Vector3f position,
                       Vector3f color, float intensity, float range, int priority) {
    public VfxLight {
        if (effectId <= 0L) throw new IllegalArgumentException("effectId must be positive");
        if (lightIndex < 0) throw new IllegalArgumentException("lightIndex must be non-negative");
        position = new Vector3f(Objects.requireNonNull(position, "position"));
        color = new Vector3f(Objects.requireNonNull(color, "color"));
        requireFinite(position, "position");
        requireFinite(color, "color");
        if (color.x < 0.0f || color.y < 0.0f || color.z < 0.0f) {
            throw new IllegalArgumentException("light color components must be non-negative");
        }
        if (!Float.isFinite(intensity) || intensity < 0.0f) {
            throw new IllegalArgumentException("light intensity must be finite and non-negative");
        }
        if (!Float.isFinite(range) || range <= 0.0f) {
            throw new IllegalArgumentException("light range must be finite and positive");
        }
    }

    public Key key() {
        return new Key(effectId, lightIndex);
    }

    @Override
    public Vector3f position() {
        return new Vector3f(position);
    }

    @Override
    public Vector3f color() {
        return new Vector3f(color);
    }

    /** Stable effect-local identity of a light. */
    public record Key(long effectId, int lightIndex) {
        public Key {
            if (effectId <= 0L) throw new IllegalArgumentException("effectId must be positive");
            if (lightIndex < 0) throw new IllegalArgumentException("lightIndex must be non-negative");
        }
    }

    private static void requireFinite(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }
}
