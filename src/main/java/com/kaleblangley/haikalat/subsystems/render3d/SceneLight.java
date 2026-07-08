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
        if (direction.lengthSquared() > 0.0f) {
            direction.normalize();
        }
        position = new Vector3f(Objects.requireNonNull(position, "position"));
        if (intensity < 0.0f) {
            throw new IllegalArgumentException("intensity must be non-negative");
        }
        if (range < 0.0f) {
            throw new IllegalArgumentException("range must be non-negative");
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
}
