package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;

import java.util.Objects;

public record SceneLight(
        LightType type,
        Vector3f color,
        float intensity,
        Vector3f direction,
        Vector3f position,
        float range,
        float innerConeRadians,
        float outerConeRadians,
        boolean castShadows
) {
    public SceneLight {
        type = Objects.requireNonNull(type, "type");
        color = new Vector3f(Objects.requireNonNull(color, "color"));
        direction = new Vector3f(Objects.requireNonNull(direction, "direction"));
        position = new Vector3f(Objects.requireNonNull(position, "position"));
        requireFinite(color, "color");
        requireFinite(direction, "direction");
        requireFinite(position, "position");
        if (color.x < 0.0f || color.y < 0.0f || color.z < 0.0f) {
            throw new IllegalArgumentException("color components must be non-negative");
        }
        if (!Float.isFinite(intensity) || intensity < 0.0f) {
            throw new IllegalArgumentException("intensity must be non-negative");
        }
        if (!Float.isFinite(range) || range < 0.0f) {
            throw new IllegalArgumentException("range must be non-negative");
        }
        if ((type == LightType.DIRECTIONAL || type == LightType.SPOT) && direction.lengthSquared() == 0.0f) {
            throw new IllegalArgumentException(type + " light direction must be non-zero");
        }
        if (direction.lengthSquared() > 0.0f) {
            direction.normalize();
        }
        if ((type == LightType.POINT || type == LightType.SPOT) && range <= 0.0f) {
            throw new IllegalArgumentException(type + " light range must be positive");
        }
        if (type == LightType.SPOT
                && (!Float.isFinite(innerConeRadians) || !Float.isFinite(outerConeRadians)
                || innerConeRadians < 0.0f || outerConeRadians <= innerConeRadians
                || outerConeRadians > (float) (Math.PI * 0.5))) {
            throw new IllegalArgumentException("spot cone must satisfy 0 <= inner < outer <= PI/2");
        }
    }

    public static SceneLight directional(Vector3f direction, Vector3f color, float intensity) {
        return new SceneLight(LightType.DIRECTIONAL, color, intensity, direction,
                new Vector3f(), 0.0f, 0.0f, 0.0f, false);
    }

    public static SceneLight shadowedDirectional(Vector3f direction, Vector3f color, float intensity) {
        return new SceneLight(LightType.DIRECTIONAL, color, intensity, direction,
                new Vector3f(), 0.0f, 0.0f, 0.0f, true);
    }

    public static SceneLight point(Vector3f position, Vector3f color, float intensity, float range) {
        return new SceneLight(LightType.POINT, color, intensity, new Vector3f(0.0f, -1.0f, 0.0f),
                position, range, 0.0f, 0.0f, false);
    }

    public static SceneLight spot(Vector3f position, Vector3f direction, Vector3f color,
                                  float intensity, float range, float innerConeRadians,
                                  float outerConeRadians) {
        return new SceneLight(LightType.SPOT, color, intensity, direction,
                position, range, innerConeRadians, outerConeRadians, false);
    }

    private static void requireFinite(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }
}
