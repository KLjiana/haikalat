package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;

/** CameraUniforms 与 visibility 共用的稳定投影事实来源。 */
final class CameraProjection {
    static final float NEAR_PLANE = 0.1f;
    static final float FAR_PLANE = 100.0f;

    private CameraProjection() {
    }

    static Matrix4f stable(Camera camera, int width, int height, Matrix4f destination) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("camera projection requires a positive framebuffer extent");
        }
        return destination.identity().perspective((float) Math.toRadians(camera.zoom()),
                width / (float) height, NEAR_PLANE, FAR_PLANE);
    }
}
