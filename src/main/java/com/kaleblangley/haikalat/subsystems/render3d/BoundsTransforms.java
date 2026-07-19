package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import org.joml.Matrix4fc;

import java.util.Objects;

/** allocation-free 的 affine local-AABB 到 world-AABB 变换。 */
final class BoundsTransforms {
    private BoundsTransforms() {
    }

    static void world(Bounds3f local, Matrix4fc matrix, WorldBounds destination) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(matrix, "matrix");
        Objects.requireNonNull(destination, "destination");
        if (!finiteAffine(matrix)) {
            throw new IllegalArgumentException("model matrix must be finite and affine");
        }
        if (local.isUnbounded()) {
            destination.unbounded();
            return;
        }

        float centerX = (local.minX() + local.maxX()) * 0.5f;
        float centerY = (local.minY() + local.maxY()) * 0.5f;
        float centerZ = (local.minZ() + local.maxZ()) * 0.5f;
        float extentX = (local.maxX() - local.minX()) * 0.5f;
        float extentY = (local.maxY() - local.minY()) * 0.5f;
        float extentZ = (local.maxZ() - local.minZ()) * 0.5f;

        float worldCenterX = matrix.m00() * centerX + matrix.m10() * centerY
                + matrix.m20() * centerZ + matrix.m30();
        float worldCenterY = matrix.m01() * centerX + matrix.m11() * centerY
                + matrix.m21() * centerZ + matrix.m31();
        float worldCenterZ = matrix.m02() * centerX + matrix.m12() * centerY
                + matrix.m22() * centerZ + matrix.m32();
        float worldExtentX = Math.abs(matrix.m00()) * extentX
                + Math.abs(matrix.m10()) * extentY + Math.abs(matrix.m20()) * extentZ;
        float worldExtentY = Math.abs(matrix.m01()) * extentX
                + Math.abs(matrix.m11()) * extentY + Math.abs(matrix.m21()) * extentZ;
        float worldExtentZ = Math.abs(matrix.m02()) * extentX
                + Math.abs(matrix.m12()) * extentY + Math.abs(matrix.m22()) * extentZ;

        destination.unbounded = false;
        destination.minX = worldCenterX - worldExtentX;
        destination.minY = worldCenterY - worldExtentY;
        destination.minZ = worldCenterZ - worldExtentZ;
        destination.maxX = worldCenterX + worldExtentX;
        destination.maxY = worldCenterY + worldExtentY;
        destination.maxZ = worldCenterZ + worldExtentZ;
        if (!finite(destination)) {
            throw new IllegalArgumentException("world bounds overflowed to a non-finite value");
        }
    }

    private static boolean finiteAffine(Matrix4fc matrix) {
        return finite(matrix.m00()) && finite(matrix.m01()) && finite(matrix.m02())
                && finite(matrix.m03()) && finite(matrix.m10()) && finite(matrix.m11())
                && finite(matrix.m12()) && finite(matrix.m13()) && finite(matrix.m20())
                && finite(matrix.m21()) && finite(matrix.m22()) && finite(matrix.m23())
                && finite(matrix.m30()) && finite(matrix.m31()) && finite(matrix.m32())
                && finite(matrix.m33()) && matrix.m03() == 0.0f && matrix.m13() == 0.0f
                && matrix.m23() == 0.0f && matrix.m33() == 1.0f;
    }

    private static boolean finite(WorldBounds bounds) {
        return finite(bounds.minX) && finite(bounds.minY) && finite(bounds.minZ)
                && finite(bounds.maxX) && finite(bounds.maxY) && finite(bounds.maxZ);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
