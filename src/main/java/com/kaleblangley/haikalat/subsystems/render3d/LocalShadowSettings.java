package com.kaleblangley.haikalat.subsystems.render3d;

/** 点光与聚光深度图共享的固定分辨率和深度偏移设置。 */
public record LocalShadowSettings(int resolution, float nearPlane, float bias) {
    public LocalShadowSettings {
        if (resolution <= 0) throw new IllegalArgumentException("resolution must be positive");
        if (!Float.isFinite(nearPlane) || nearPlane <= 0.0f) {
            throw new IllegalArgumentException("nearPlane must be finite and positive");
        }
        if (!Float.isFinite(bias) || bias < 0.0f) {
            throw new IllegalArgumentException("bias must be finite and non-negative");
        }
    }

    public static LocalShadowSettings defaults() {
        return new LocalShadowSettings(512, 0.05f, 0.0025f);
    }
}
