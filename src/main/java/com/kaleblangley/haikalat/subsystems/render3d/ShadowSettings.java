package com.kaleblangley.haikalat.subsystems.render3d;

public record ShadowSettings(int resolution, float sceneRadius, float nearPlane, float farPlane, float bias) {
    public ShadowSettings(int resolution, float sceneRadius, float nearPlane, float farPlane) {
        this(resolution, sceneRadius, nearPlane, farPlane, 0.003f);
    }

    public ShadowSettings {
        if (resolution <= 0) {
            throw new IllegalArgumentException("resolution must be positive");
        }
        if (sceneRadius <= 0.0f) {
            throw new IllegalArgumentException("sceneRadius must be positive");
        }
        if (nearPlane <= 0.0f || farPlane <= nearPlane) {
            throw new IllegalArgumentException("farPlane must be greater than nearPlane");
        }
        if (!Float.isFinite(bias) || bias < 0.0f) {
            throw new IllegalArgumentException("bias must be finite and non-negative");
        }
    }

    public static ShadowSettings directionalDefaults() {
        return new ShadowSettings(2048, 20.0f, 0.1f, 80.0f, 0.003f);
    }
}
