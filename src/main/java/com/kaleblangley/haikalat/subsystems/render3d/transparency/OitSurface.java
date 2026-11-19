package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/**
 * One transparent surface contributed to a {@link WeightedOitPass} collection.
 *
 * <p>The surface is a world-space parallelogram: {@code center} is the middle,
 * {@code halfRight} and {@code halfUp} are the edge vectors from the centre to
 * the right and top edges. Every corner is
 * {@code center +- halfRight +- halfUp}; the two triangles are emitted from the
 * pass's unit quad. Camera-facing billboards are therefore expressed by passing
 * the camera right/up basis scaled by half the desired size, while smoke cards
 * may use any orientation.</p>
 *
 * <p>Colour is linear HDR radiance and alpha is straight (not premultiplied)
 * coverage in {@code [0, 1]}. Both are written with the weight documented on
 * {@link WeightedOitSettings}; the pass never mutates or retains the values.
 * All components are stored as primitives so a per-frame particle list does not
 * allocate vectors per surface.</p>
 *
 * <p>This is intentionally not a general transparent-mesh contract: meshes with
 * per-vertex colour, textures, normals or vertex animation are not accepted by
 * the first version. The caller is expected to expand the selected particle or
 * smoke cards into this bounded list.</p>
 */
public record OitSurface(
        float centerX,
        float centerY,
        float centerZ,
        float halfRightX,
        float halfRightY,
        float halfRightZ,
        float halfUpX,
        float halfUpY,
        float halfUpZ,
        float red,
        float green,
        float blue,
        float alpha) {

    /** Largest accepted linear colour component (still a finite float16 value). */
    public static final float MAXIMUM_COLOR_COMPONENT = 65_504.0f;

    public OitSurface {
        requireFinite(centerX, "centerX");
        requireFinite(centerY, "centerY");
        requireFinite(centerZ, "centerZ");
        requireFinite(halfRightX, "halfRightX");
        requireFinite(halfRightY, "halfRightY");
        requireFinite(halfRightZ, "halfRightZ");
        requireFinite(halfUpX, "halfUpX");
        requireFinite(halfUpY, "halfUpY");
        requireFinite(halfUpZ, "halfUpZ");
        requireColor(red, "red");
        requireColor(green, "green");
        requireColor(blue, "blue");
        if (!Float.isFinite(alpha) || alpha < 0.0f || alpha > 1.0f) {
            throw new IllegalArgumentException("alpha must be finite and in [0, 1]");
        }
    }

    /**
     * Convenience factory that copies JOML vectors without retaining them.
     *
     * @param center    world-space centre
     * @param halfRight world-space edge vector from the centre to the right edge
     * @param halfUp    world-space edge vector from the centre to the top edge
     * @param color     linear HDR radiance
     * @param alpha     straight coverage in {@code [0, 1]}
     */
    public static OitSurface of(Vector3fc center, Vector3fc halfRight, Vector3fc halfUp,
                                Vector3fc color, float alpha) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(halfRight, "halfRight");
        Objects.requireNonNull(halfUp, "halfUp");
        Objects.requireNonNull(color, "color");
        return new OitSurface(center.x(), center.y(), center.z(),
                halfRight.x(), halfRight.y(), halfRight.z(),
                halfUp.x(), halfUp.y(), halfUp.z(),
                color.x(), color.y(), color.z(), alpha);
    }

    /** @return a defensive copy of the world-space centre */
    public Vector3f center() {
        return new Vector3f(centerX, centerY, centerZ);
    }

    /** @return a defensive copy of the right half-edge vector */
    public Vector3f halfRight() {
        return new Vector3f(halfRightX, halfRightY, halfRightZ);
    }

    /** @return a defensive copy of the up half-edge vector */
    public Vector3f halfUp() {
        return new Vector3f(halfUpX, halfUpY, halfUpZ);
    }

    /** @return a defensive copy of the linear HDR colour */
    public Vector3f color() {
        return new Vector3f(red, green, blue);
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireColor(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > MAXIMUM_COLOR_COMPONENT) {
            throw new IllegalArgumentException(name + " must be finite and in [0, "
                    + MAXIMUM_COLOR_COMPONENT + "]");
        }
    }
}
