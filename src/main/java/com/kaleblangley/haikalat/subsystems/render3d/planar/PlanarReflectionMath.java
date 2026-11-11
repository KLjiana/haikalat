package com.kaleblangley.haikalat.subsystems.render3d.planar;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/**
 * GL-free planar reflection math: mirror camera, reflecting view matrix and the
 * Lengyel oblique near-plane projection.
 *
 * <p>Planes use the normalized equation {@code n . p + d == 0}; {@code n}
 * points toward the retained scene side.  The functions work for any
 * orientation, not only {@code y == level}.  Inputs are validated and
 * degenerate planes or projections throw {@link IllegalArgumentException}
 * instead of producing NaN matrices.</p>
 *
 * <p>The reflecting view matrix is computed as {@code V * R}, where {@code R}
 * is the world-space Householder reflection.  It must not be rebuilt with a
 * {@code lookAt} from the reflected up vector: a reflection inverts
 * handedness, so a lookAt call would silently flip the horizontal axis.  The
 * determinant of the resulting view is negative; callers must use
 * {@code FrontFace.CW} while recording the mirrored scene.</p>
 */
public final class PlanarReflectionMath {
    /** Smallest accepted squared plane-normal length. */
    public static final float MINIMUM_NORMAL_LENGTH_SQUARED = 1.0e-12f;
    /** Smallest accepted oblique-projection corner determinant. */
    public static final double MINIMUM_OBLIQUE_DETERMINANT = 1.0e-6;

    private PlanarReflectionMath() {
    }

    /**
     * Reflects a world-space position across {@code plane}.
     *
     * @return {@code destination}
     */
    public static Vector3f mirrorPosition(Vector4f plane, Vector3f position,
                                          Vector3f destination) {
        validatePlane(plane);
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(destination, "destination");
        requireFinite(position, "position");
        float nx = plane.x;
        float ny = plane.y;
        float nz = plane.z;
        float inverseLengthSquared = 1.0f / (nx * nx + ny * ny + nz * nz);
        float signedDistance = nx * position.x + ny * position.y + nz * position.z + plane.w;
        float scale = 2.0f * signedDistance * inverseLengthSquared;
        return destination.set(position.x - scale * nx, position.y - scale * ny,
                position.z - scale * nz);
    }

    /**
     * Reflects a world-space direction across {@code plane}.
     *
     * @return {@code destination}
     */
    public static Vector3f mirrorDirection(Vector4f plane, Vector3f direction,
                                           Vector3f destination) {
        validatePlane(plane);
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(destination, "destination");
        requireFinite(direction, "direction");
        float nx = plane.x;
        float ny = plane.y;
        float nz = plane.z;
        float inverseLengthSquared = 1.0f / (nx * nx + ny * ny + nz * nz);
        float dot = nx * direction.x + ny * direction.y + nz * direction.z;
        float scale = 2.0f * dot * inverseLengthSquared;
        return destination.set(direction.x - scale * nx, direction.y - scale * ny,
                direction.z - scale * nz);
    }

    /**
     * Writes the world-space reflection matrix for {@code plane}.
     *
     * <p>The returned matrix is an involution and has determinant {@code -1};
     * it maps the retained side onto the mirrored side.</p>
     *
     * @return {@code destination}
     */
    public static Matrix4f reflectionMatrix(Vector4f plane, Matrix4f destination) {
        validatePlane(plane);
        Objects.requireNonNull(destination, "destination");
        float nx = plane.x;
        float ny = plane.y;
        float nz = plane.z;
        float d = plane.w;
        float inverseLengthSquared = 1.0f / (nx * nx + ny * ny + nz * nz);
        float scale = 2.0f * inverseLengthSquared;
        float xx = scale * nx * nx;
        float xy = scale * nx * ny;
        float xz = scale * nx * nz;
        float yy = scale * ny * ny;
        float yz = scale * ny * nz;
        float zz = scale * nz * nz;
        float tx = -scale * d * nx;
        float ty = -scale * d * ny;
        float tz = -scale * d * nz;
        return destination.identity()
                .m00(1.0f - xx).m01(-xy).m02(-xz)
                .m10(-xy).m11(1.0f - yy).m12(-yz)
                .m20(-xz).m21(-yz).m22(1.0f - zz)
                .m30(tx).m31(ty).m32(tz);
    }

    /**
     * Writes {@code view * reflectionMatrix(plane)} into {@code destination}.
     *
     * <p>This allocation-free overload requires distinct scratch and destination
     * matrices so a per-frame caller can reuse both.</p>
     *
     * @return {@code destination}
     */
    public static Matrix4f mirrorView(Vector4f plane, Matrix4f view,
                                      Matrix4f reflectionScratch, Matrix4f destination) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(reflectionScratch, "reflectionScratch");
        Objects.requireNonNull(destination, "destination");
        if (reflectionScratch == view || reflectionScratch == destination) {
            throw new IllegalArgumentException(
                    "reflectionScratch must differ from view and destination");
        }
        reflectionMatrix(plane, reflectionScratch);
        return destination.set(view).mul(reflectionScratch);
    }

    /**
     * Allocating convenience overload of
     * {@link #mirrorView(Vector4f, Matrix4f, Matrix4f, Matrix4f)}.
     *
     * @return {@code destination}
     */
    public static Matrix4f mirrorView(Vector4f plane, Matrix4f view, Matrix4f destination) {
        return mirrorView(plane, view, new Matrix4f(), destination);
    }

    /**
     * Transforms a normalized world-space plane equation into view space.
     *
     * <p>{@code view} is expected to be a rigid camera view matrix (rotation
     * plus translation), which is what {@link com.kaleblangley.haikalat.subsystems.render3d.Camera}
     * and its external-camera subtype produce.</p>
     *
     * @return {@code destination}
     */
    public static Vector4f planeToView(Vector4f worldPlane, Matrix4f view,
                                       Vector4f destination) {
        validatePlane(worldPlane);
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(destination, "destination");
        float nx = worldPlane.x;
        float ny = worldPlane.y;
        float nz = worldPlane.z;
        float inverseLengthSquared = 1.0f / (nx * nx + ny * ny + nz * nz);
        float w = worldPlane.w;
        Vector3f point = new Vector3f(nx * -w * inverseLengthSquared,
                ny * -w * inverseLengthSquared, nz * -w * inverseLengthSquared);
        Vector3f pointView = view.transformPosition(point, new Vector3f());
        Vector3f normalView = view.transformDirection(new Vector3f(nx, ny, nz), new Vector3f());
        float dot = normalView.dot(pointView);
        return destination.set(normalView.x, normalView.y, normalView.z, -dot);
    }

    /**
     * Applies Lengyel's oblique near-plane clipping to {@code projection}.
     *
     * <p>{@code viewPlane} is the retained half-space in view space: points
     * with {@code dot(normal, point) + w >= 0} stay visible, and the plane
     * itself becomes the new near plane.  The first two rows and the
     * {@code w} row of the projection are unchanged; only the third row is
     * replaced, so horizontal/vertical screen coordinates are preserved.</p>
     *
     * @return {@code destination}
     */
    public static Matrix4f obliqueProjection(Matrix4f projection, Vector4f viewPlane,
                                             Matrix4f destination) {
        Objects.requireNonNull(projection, "projection");
        validatePlane(viewPlane);
        Objects.requireNonNull(destination, "destination");
        if (!projection.isFinite()) {
            throw new IllegalArgumentException("projection must be finite");
        }
        destination.set(projection);
        double m00 = destination.m00();
        double m11 = destination.m11();
        double m20 = destination.m20();
        double m21 = destination.m21();
        double m22 = destination.m22();
        double m32 = destination.m32();
        if (Math.abs(m00) < MINIMUM_OBLIQUE_DETERMINANT
                || Math.abs(m11) < MINIMUM_OBLIQUE_DETERMINANT
                || Math.abs(m32) < MINIMUM_OBLIQUE_DETERMINANT) {
            throw new IllegalArgumentException(
                    "projection is degenerate for oblique near-plane clipping");
        }
        double nx = viewPlane.x;
        double ny = viewPlane.y;
        double nz = viewPlane.z;
        double d = viewPlane.w;
        double qx = (Math.copySign(1.0, nx) + m20) / m00;
        double qy = (Math.copySign(1.0, ny) + m21) / m11;
        double qz = -1.0;
        double qw = (1.0 + m22) / m32;
        double denominator = nx * qx + ny * qy + nz * qz + d * qw;
        if (!Double.isFinite(denominator)
                || Math.abs(denominator) < MINIMUM_OBLIQUE_DETERMINANT) {
            throw new IllegalArgumentException(
                    "view-space clip plane does not intersect the far plane");
        }
        double scale = 2.0 / denominator;
        return destination
                .m02((float) (nx * scale))
                .m12((float) (ny * scale))
                .m22((float) (nz * scale + 1.0))
                .m32((float) (d * scale));
    }

    private static void validatePlane(Vector4f plane) {
        Objects.requireNonNull(plane, "plane");
        if (!Float.isFinite(plane.x) || !Float.isFinite(plane.y)
                || !Float.isFinite(plane.z) || !Float.isFinite(plane.w)) {
            throw new IllegalArgumentException("plane equation must be finite");
        }
        float lengthSquared = plane.x * plane.x + plane.y * plane.y + plane.z * plane.z;
        if (lengthSquared < MINIMUM_NORMAL_LENGTH_SQUARED) {
            throw new IllegalArgumentException("plane normal must have a non-zero length");
        }
    }

    private static void requireFinite(Vector3f value, String name) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y)
                || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
