package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import org.joml.Matrix4fc;

import java.util.Objects;

/** 可复用、无 OpenGL 依赖的 OpenGL clip-space 六平面视锥。 */
final class Frustum {
    private static final int PLANE_COUNT = 6;
    private static final int COMPONENTS = 4;
    private final float[] planes = new float[PLANE_COUNT * COMPONENTS];

    Frustum set(Matrix4fc clip) {
        Objects.requireNonNull(clip, "clip");
        // JOML 使用 column-major 访问；以下分别为 row3 +/- row0/1/2。
        plane(0, clip.m03() + clip.m00(), clip.m13() + clip.m10(),
                clip.m23() + clip.m20(), clip.m33() + clip.m30());
        plane(1, clip.m03() - clip.m00(), clip.m13() - clip.m10(),
                clip.m23() - clip.m20(), clip.m33() - clip.m30());
        plane(2, clip.m03() + clip.m01(), clip.m13() + clip.m11(),
                clip.m23() + clip.m21(), clip.m33() + clip.m31());
        plane(3, clip.m03() - clip.m01(), clip.m13() - clip.m11(),
                clip.m23() - clip.m21(), clip.m33() - clip.m31());
        plane(4, clip.m03() + clip.m02(), clip.m13() + clip.m12(),
                clip.m23() + clip.m22(), clip.m33() + clip.m32());
        plane(5, clip.m03() - clip.m02(), clip.m13() - clip.m12(),
                clip.m23() - clip.m22(), clip.m33() - clip.m32());
        return this;
    }

    Classification classify(Bounds3f bounds) {
        Objects.requireNonNull(bounds, "bounds");
        if (bounds.isUnbounded()) return Classification.INTERSECTING;
        return classify(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    Classification classify(WorldBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        if (bounds.unbounded) return Classification.INTERSECTING;
        return classify(bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    boolean outside(WorldBounds bounds) {
        return classify(bounds) == Classification.OUTSIDE;
    }

    /**
     * Conservative AABB test with a world-space guard band.  Shadow views use this
     * overload so filter kernels and cascade/cube seams never turn an edge contact
     * into a false negative.
     */
    boolean outside(WorldBounds bounds, float padding) {
        Objects.requireNonNull(bounds, "bounds");
        if (bounds.unbounded) return false;
        if (!Float.isFinite(padding) || padding < 0.0f) {
            throw new IllegalArgumentException("padding must be finite and non-negative");
        }
        return classify(bounds.minX - padding, bounds.minY - padding, bounds.minZ - padding,
                bounds.maxX + padding, bounds.maxY + padding, bounds.maxZ + padding)
                == Classification.OUTSIDE;
    }

    private Classification classify(float minX, float minY, float minZ,
                                    float maxX, float maxY, float maxZ) {
        float centerX = (minX + maxX) * 0.5f;
        float centerY = (minY + maxY) * 0.5f;
        float centerZ = (minZ + maxZ) * 0.5f;
        float extentX = (maxX - minX) * 0.5f;
        float extentY = (maxY - minY) * 0.5f;
        float extentZ = (maxZ - minZ) * 0.5f;
        float coordinateScale = Math.max(1.0f, Math.max(Math.max(Math.abs(minX), Math.abs(maxX)),
                Math.max(Math.max(Math.abs(minY), Math.abs(maxY)),
                        Math.max(Math.abs(minZ), Math.abs(maxZ)))));
        float epsilon = Math.max(1.0e-6f, Math.ulp(coordinateScale) * 4.0f);
        boolean intersecting = false;
        for (int plane = 0; plane < PLANE_COUNT; plane++) {
            int offset = plane * COMPONENTS;
            float a = planes[offset];
            float b = planes[offset + 1];
            float c = planes[offset + 2];
            float distance = a * centerX + b * centerY + c * centerZ + planes[offset + 3];
            float radius = Math.abs(a) * extentX + Math.abs(b) * extentY + Math.abs(c) * extentZ;
            if (distance + radius < -epsilon) return Classification.OUTSIDE;
            if (distance - radius <= epsilon) intersecting = true;
        }
        return intersecting ? Classification.INTERSECTING : Classification.INSIDE;
    }

    private void plane(int index, float a, float b, float c, float d) {
        float lengthSquared = a * a + b * b + c * c;
        if (!Float.isFinite(lengthSquared) || lengthSquared <= 0.0f || !Float.isFinite(d)) {
            throw new IllegalArgumentException("clip matrix produced invalid frustum plane " + index);
        }
        float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
        int offset = index * COMPONENTS;
        planes[offset] = a * inverseLength;
        planes[offset + 1] = b * inverseLength;
        planes[offset + 2] = c * inverseLength;
        planes[offset + 3] = d * inverseLength;
    }

    enum Classification { OUTSIDE, INTERSECTING, INSIDE }
}
