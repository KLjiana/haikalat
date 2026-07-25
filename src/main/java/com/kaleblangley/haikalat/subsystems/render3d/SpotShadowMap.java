package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

/** 与聚光灯外锥一致的单张透视 depth map。 */
public final class SpotShadowMap {
    public static final String PASS_NAME = "SpotShadowPass";
    public static final String TEXTURE_NAME = "SpotShadowMap";

    private final LocalShadowSettings settings;

    public SpotShadowMap(LocalShadowSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public static SpotShadowMap defaults() {
        return new SpotShadowMap(LocalShadowSettings.defaults());
    }

    public LocalShadowSettings settings() {
        return settings;
    }

    public Matrix4f lightSpaceMatrix(SceneLight light) {
        Objects.requireNonNull(light, "light");
        if (light.type() != LightType.SPOT) {
            throw new IllegalArgumentException("Spot shadow map requires a spot light");
        }
        if (settings.nearPlane() >= light.range()) {
            throw new IllegalArgumentException("spot light range must exceed shadow nearPlane");
        }
        Vector3f direction = light.direction();
        Vector3f position = light.position();
        Vector3f up = Math.abs(direction.dot(0.0f, 1.0f, 0.0f)) > 0.99f
                ? new Vector3f(0.0f, 0.0f, 1.0f) : new Vector3f(0.0f, 1.0f, 0.0f);
        Matrix4f projection = new Matrix4f().perspective(light.outerConeRadians() * 2.0f,
                1.0f, settings.nearPlane(), light.range());
        Matrix4f view = new Matrix4f().lookAt(position,
                new Vector3f(position).add(direction), up);
        return projection.mul(view);
    }
}
