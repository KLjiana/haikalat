package com.kaleblangley.haikalat.subsystems.render3d.geometry;

import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;

import org.joml.Matrix4f;

/** CameraUniforms 与 visibility 共用的稳定投影事实来源。 */
public final class CameraProjection {
    public static final float NEAR_PLANE = 0.1f;
    public static final float FAR_PLANE = 100.0f;

    private CameraProjection() {
    }

    public static Matrix4f stable(Camera camera, int width, int height, Matrix4f destination) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("camera projection requires a positive framebuffer extent");
        }
        if (camera instanceof ExternalCamera external) {
            return external.projection(destination);
        }
        return destination.identity().perspective((float) Math.toRadians(camera.zoom()),
                width / (float) height, camera.nearPlane(), camera.farPlane());
    }
}
