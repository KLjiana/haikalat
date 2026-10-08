package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import org.joml.Matrix4f;
import org.joml.Vector2f;

/**
 * The single source of truth for TAA projection jitter.  Rasterisation uses the
 * jittered projection while motion vectors use the stable projection; the
 * resolve pass converts the per-frame jitter into UV space with the sign that
 * the actual projection produces (locked by TemporalJitterTest).
 */
final class TemporalJitter {
    private TemporalJitter() {
    }

    static boolean enabled(AntiAliasingMode mode) {
        return mode == AntiAliasingMode.TAA;
    }

    /** Sub-pixel offset in pixels, in the range [-0.25, 0.25]. */
    static Vector2f offsetPixels(AntiAliasingMode mode, int frameIndex, Vector2f destination) {
        Vector2f result = destination == null ? new Vector2f() : destination;
        if (!enabled(mode)) {
            return result.set(0.0f, 0.0f);
        }
        int phase = frameIndex & 3;
        float jitterX = (phase & 1) == 0 ? -0.25f : 0.25f;
        float jitterY = (phase & 2) == 0 ? -0.25f : 0.25f;
        return result.set(jitterX, jitterY);
    }

    /** Applies the frozen projection delta used by CameraUniforms. */
    static void applyProjection(Matrix4f projection, int width, int height,
                                AntiAliasingMode mode, int frameIndex) {
        if (!enabled(mode) || width <= 0 || height <= 0) {
            return;
        }
        int phase = frameIndex & 3;
        float jitterX = (phase & 1) == 0 ? -0.25f : 0.25f;
        float jitterY = (phase & 2) == 0 ? -0.25f : 0.25f;
        float deltaX = jitterX * 2.0f / width;
        float deltaY = jitterY * 2.0f / height;
        // Translate clip XY by -delta * clip W. Perspective changes m20/m21;
        // orthographic changes m30/m31, retaining a depth-independent offset.
        projection.m00(projection.m00() - deltaX * projection.m03());
        projection.m10(projection.m10() - deltaX * projection.m13());
        projection.m20(projection.m20() - deltaX * projection.m23());
        projection.m30(projection.m30() - deltaX * projection.m33());
        projection.m01(projection.m01() - deltaY * projection.m03());
        projection.m11(projection.m11() - deltaY * projection.m13());
        projection.m21(projection.m21() - deltaY * projection.m23());
        projection.m31(projection.m31() - deltaY * projection.m33());
    }

    /**
     * UV-space offset produced by the jittered projection relative to the
     * stable projection. Clip XY is translated by -delta * clip W, so the
     * NDC offset is independent of depth for both perspective and orthographic cameras.
     */
    static Vector2f uvOffset(AntiAliasingMode mode, int frameIndex, int width, int height,
                             Vector2f destination) {
        Vector2f result = destination == null ? new Vector2f() : destination;
        Vector2f pixels = offsetPixels(mode, frameIndex, null);
        if (width <= 0 || height <= 0 || (pixels.x == 0.0f && pixels.y == 0.0f)) {
            return result.set(0.0f, 0.0f);
        }
        return result.set(-pixels.x / width, -pixels.y / height);
    }
}
