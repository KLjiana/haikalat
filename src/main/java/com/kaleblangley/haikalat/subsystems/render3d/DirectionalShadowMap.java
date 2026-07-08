package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

public final class DirectionalShadowMap {
    public static final String PASS_NAME = "DirectionalShadowPass";
    public static final String TEXTURE_NAME = "DirectionalShadowMap";

    private final ShadowSettings settings;

    public DirectionalShadowMap(ShadowSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public static DirectionalShadowMap defaults() {
        return new DirectionalShadowMap(ShadowSettings.directionalDefaults());
    }

    public ShadowSettings settings() {
        return settings;
    }

    public FramebufferDescriptor descriptor() {
        return FramebufferDescriptor.builder(settings.resolution(), settings.resolution())
                .depthTexture()
                .build();
    }

    public Matrix4f lightSpaceMatrix(SceneLight light, Vector3f focus) {
        Objects.requireNonNull(light, "light");
        Objects.requireNonNull(focus, "focus");
        if (light.type() != LightType.DIRECTIONAL) {
            throw new IllegalArgumentException("Directional shadow map requires a directional light");
        }
        Vector3f direction = new Vector3f(light.direction());
        if (direction.lengthSquared() == 0.0f) {
            direction.set(0.0f, -1.0f, 0.0f);
        }
        Vector3f eye = new Vector3f(focus).fma(-settings.sceneRadius(), direction);
        Matrix4f projection = new Matrix4f().ortho(
                -settings.sceneRadius(), settings.sceneRadius(),
                -settings.sceneRadius(), settings.sceneRadius(),
                settings.nearPlane(), settings.farPlane());
        Matrix4f view = new Matrix4f().lookAt(eye, focus, new Vector3f(0.0f, 1.0f, 0.0f));
        return projection.mul(view);
    }
}
