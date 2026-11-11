package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.subsystems.postprocess.ScreenDistortion;
import com.kaleblangley.haikalat.subsystems.vfx.VfxDistortion;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Projects GL-free {@link VfxDistortion} values into bounded
 * {@link ScreenDistortion} contributions for the colour chain.
 *
 * <p>The adapter mirrors {@code VfxLightBridge}: the effect subsystem owns the
 * world-space contribution and this render3d adapter owns the camera
 * projection.  It is pure CPU math (no GL call, no snapshot mutation) so the
 * projection contract is testable without a window.  Contributions whose
 * centre is behind the camera, outside the unit UV square or outside the
 * near/far range are dropped instead of being clamped, and the world radius is
 * projected through the camera right/up axes so the screen-space extent stays
 * aspect and perspective correct.  {@code depthThicknessMeters} is converted to
 * an NDC depth tolerance by projecting a point one thickness farther along the
 * camera forward axis.</p>
 */
public final class VfxDistortionProjector {
    private static final float MINIMUM_W = 1.0e-6f;
    private static final float MINIMUM_EXTENT_UV = 1.0e-4f;

    private VfxDistortionProjector() {
    }

    /**
     * @param distortions world-space contributions; may be empty
     * @param projection  camera projection matrix
     * @param view        camera view matrix
     * @return immutable screen-space contributions in input order; empty when
     *         every contribution is culled
     */
    public static List<ScreenDistortion> project(List<VfxDistortion> distortions,
                                                 Matrix4fc projection, Matrix4fc view) {
        Objects.requireNonNull(distortions, "distortions");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(view, "view");
        if (distortions.isEmpty()) return List.of();

        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        Vector3f right = cameraAxis(view, 0, new Vector3f(1.0f, 0.0f, 0.0f));
        Vector3f up = cameraAxis(view, 1, new Vector3f(0.0f, 1.0f, 0.0f));
        Vector3f forward = cameraAxis(view, 2, new Vector3f(0.0f, 0.0f, -1.0f)).negate();

        List<ScreenDistortion> screenDistortions = new ArrayList<>(distortions.size());
        Set<VfxDistortion.Key> keys = new HashSet<>(distortions.size() * 2);
        Vector4f projected = new Vector4f();
        Vector4f source = new Vector4f();
        Vector3f offsetPoint = new Vector3f();
        for (VfxDistortion distortion : distortions) {
            Objects.requireNonNull(distortion, "distortion");
            if (!keys.add(distortion.key())) {
                throw new IllegalArgumentException(
                        "duplicate vfx distortion key " + distortion.key());
            }
            Vector3f center = distortion.worldCenter();
            if (!projectPoint(viewProjection, center.x, center.y, center.z,
                    source, projected)) {
                continue;
            }
            float centerU = projected.x;
            float centerV = projected.y;
            float effectDepth = projected.z;
            if (centerU < 0.0f || centerU > 1.0f || centerV < 0.0f || centerV > 1.0f
                    || effectDepth < 0.0f || effectDepth > 1.0f) {
                continue;
            }
            float radius = distortion.worldRadius();
            float extentX = 0.0f;
            float extentY = 0.0f;
            for (int axis = 0; axis < 2; axis++) {
                Vector3f direction = axis == 0 ? right : up;
                for (int sign = -1; sign <= 1; sign += 2) {
                    offsetPoint.set(center).fma(sign * radius, direction, offsetPoint);
                    if (!projectPoint(viewProjection, offsetPoint.x, offsetPoint.y,
                            offsetPoint.z, source, projected)) {
                        continue;
                    }
                    extentX = Math.max(extentX, Math.abs(projected.x - centerU));
                    extentY = Math.max(extentY, Math.abs(projected.y - centerV));
                }
            }
            if (extentX <= 0.0f || extentY <= 0.0f) continue;
            float depthThickness = 0.0f;
            if (distortion.depthThicknessMeters() > 0.0f) {
                offsetPoint.set(center)
                        .fma(distortion.depthThicknessMeters(), forward, offsetPoint);
                if (projectPoint(viewProjection, offsetPoint.x, offsetPoint.y,
                        offsetPoint.z, source, projected)) {
                    depthThickness = Math.min(1.0f, Math.abs(projected.z - effectDepth));
                }
            }
            screenDistortions.add(new ScreenDistortion(
                    distortion.effectId(), distortion.distortionIndex(),
                    new Vector2f(centerU, centerV),
                    new Vector2f(
                            Math.min(ScreenDistortion.MAXIMUM_EXTENT_UV,
                                    Math.max(MINIMUM_EXTENT_UV, extentX)),
                            Math.min(ScreenDistortion.MAXIMUM_EXTENT_UV,
                                    Math.max(MINIMUM_EXTENT_UV, extentY))),
                    distortion.uvOffset(), distortion.mask(),
                    effectDepth, depthThickness, distortion.ring(),
                    distortion.haze(), distortion.phaseSeconds()));
        }
        return List.copyOf(screenDistortions);
    }

    private static Vector3f cameraAxis(Matrix4fc view, int row, Vector3f fallback) {
        Vector3f axis = switch (row) {
            case 0 -> new Vector3f(view.m00(), view.m01(), view.m02());
            case 1 -> new Vector3f(view.m10(), view.m11(), view.m12());
            default -> new Vector3f(view.m20(), view.m21(), view.m22());
        };
        if (!finite(axis) || axis.lengthSquared() <= 1.0e-12f) return new Vector3f(fallback);
        return axis.normalize();
    }

    /**
     * @return false when the point is behind the camera or non-finite;
     *         {@code out} then holds normalized UV in xy and NDC depth in z
     */
    private static boolean projectPoint(Matrix4fc viewProjection,
                                        float x, float y, float z,
                                        Vector4f source, Vector4f out) {
        viewProjection.transform(source.set(x, y, z, 1.0f), out);
        if (!Float.isFinite(out.w) || out.w <= MINIMUM_W) return false;
        float inverseW = 1.0f / out.w;
        float ndcX = out.x * inverseW;
        float ndcY = out.y * inverseW;
        float ndcZ = out.z * inverseW;
        if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY) || !Float.isFinite(ndcZ)) {
            return false;
        }
        out.set(ndcX * 0.5f + 0.5f, ndcY * 0.5f + 0.5f, ndcZ * 0.5f + 0.5f, 1.0f);
        return true;
    }

    private static boolean finite(Vector3f value) {
        return Float.isFinite(value.x) && Float.isFinite(value.y)
                && Float.isFinite(value.z);
    }
}
