package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import java.util.Objects;

/**
 * Immutable author value for one projected decal.
 *
 * <p>The decal is a world-space receiver volume, not a camera-facing quad.
 * {@code center} sits on the intended receiver surface, {@code normal} points
 * out of that surface toward the viewer and {@code up} fixes the texture
 * orientation on the receiver.  {@link ProjectedDecalSettings} supplies the
 * depth extent and receiver filters that are shared by a batch; this value
 * only carries the per-instance placement, texture and colour.</p>
 *
 * <p>{@code normal} and {@code up} are normalized and orthogonalized on
 * construction, so equal placement describes equal records.  Texture sampling
 * uses {@code local.xy + 0.5} in the decal basis; the alpha channel is the
 * optional receiver mask.</p>
 *
 * @param sequence       stable author id used to break equal-distance ordering
 * @param texture        decoded sRGB colour texture; alpha is the optional mask
 * @param center         world-space receiver anchor on the surface
 * @param normal         world-space direction out of the receiver surface
 * @param up             world-space texture-up direction, not parallel to {@code normal}
 * @param size           world-space width and height of the receiver rectangle
 * @param color          linear tint in RGB and coverage in alpha
 * @param emissiveScale  bounded HDR scale applied to the sampled RGB
 */
public record ProjectedDecal(long sequence, AssetRef texture, Vector3f center,
                             Vector3f normal, Vector3f up, Vector2f size,
                             Vector4f color, float emissiveScale) {
    /** Largest supported emissive multiplier. */
    public static final float MAX_EMISSIVE = 8.0f;

    public ProjectedDecal {
        texture = Objects.requireNonNull(texture, "texture");
        center = new Vector3f(Objects.requireNonNull(center, "center"));
        normal = new Vector3f(Objects.requireNonNull(normal, "normal"));
        up = new Vector3f(Objects.requireNonNull(up, "up"));
        size = new Vector2f(Objects.requireNonNull(size, "size"));
        color = new Vector4f(Objects.requireNonNull(color, "color"));
        requireFinite(center, "center");
        requireFinite(normal, "normal");
        requireFinite(up, "up");
        if (normal.lengthSquared() <= 1.0e-12f) {
            throw new IllegalArgumentException("normal must be non-zero");
        }
        if (up.lengthSquared() <= 1.0e-12f) {
            throw new IllegalArgumentException("up must be non-zero");
        }
        normal.normalize();
        up.fma(-up.dot(normal), normal);
        if (up.lengthSquared() <= 1.0e-12f) {
            throw new IllegalArgumentException("normal and up must not be parallel");
        }
        up.normalize();
        if (!Float.isFinite(size.x) || !Float.isFinite(size.y)
                || size.x <= 0.0f || size.y <= 0.0f) {
            throw new IllegalArgumentException("size must contain finite positive values");
        }
        requireColor(color);
        if (!Float.isFinite(emissiveScale) || emissiveScale < 0.0f
                || emissiveScale > MAX_EMISSIVE) {
            throw new IllegalArgumentException("emissiveScale must be finite and in [0, "
                    + MAX_EMISSIVE + "]");
        }
    }

    @Override
    public Vector3f center() {
        return new Vector3f(center);
    }

    @Override
    public Vector3f normal() {
        return new Vector3f(normal);
    }

    @Override
    public Vector3f up() {
        return new Vector3f(up);
    }

    @Override
    public Vector2f size() {
        return new Vector2f(size);
    }

    @Override
    public Vector4f color() {
        return new Vector4f(color);
    }

    /** Coverage alpha in {@code [0, 1]}. */
    public float opacity() {
        return color.w;
    }

    /** Squared camera distance without allocating an accessor copy. */
    float distanceSquaredTo(Vector3fc point) {
        float dx = center.x - point.x();
        float dy = center.y - point.y();
        float dz = center.z - point.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * View-space z of the decal anchor without allocating a temporary vector.
     * OpenGL view space looks down {@code -z}, so a value {@code >= 0} means
     * the anchor is behind the camera plane.
     */
    float viewSpaceZ(Matrix4fc view) {
        return view.m02() * center.x + view.m12() * center.y
                + view.m22() * center.z + view.m32();
    }

    private static void requireFinite(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y)
                || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
    }

    private static void requireColor(Vector4f value) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y)
                || !Float.isFinite(value.z) || !Float.isFinite(value.w)
                || value.x < 0.0f || value.y < 0.0f || value.z < 0.0f
                || value.w < 0.0f || value.w > 1.0f) {
            throw new IllegalArgumentException(
                    "color must contain finite non-negative RGB and alpha in [0, 1]");
        }
    }
}
