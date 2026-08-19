package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 实用切分与 world-texel 稳定化的方向光级联矩阵方案。 */
public final class DirectionalCascadePlan {
    private final List<Cascade> cascades;

    private DirectionalCascadePlan(List<Cascade> cascades) {
        this.cascades = List.copyOf(cascades);
    }

    public static DirectionalCascadePlan create(Vector3fc cameraPosition,
                                                Vector3fc cameraForward,
                                                Vector3fc lightDirection,
                                                float verticalFovRadians,
                                                float aspectRatio,
                                                float nearPlane,
                                                float farPlane,
                                                int cascadeCount,
                                                float logarithmicWeight,
                                                int resolution) {
        Vector3f position = finite(cameraPosition, "cameraPosition");
        Vector3f forward = finite(cameraForward, "cameraForward");
        Vector3f direction = finite(lightDirection, "lightDirection");
        if (forward.lengthSquared() == 0.0f || direction.lengthSquared() == 0.0f) {
            throw new IllegalArgumentException("cameraForward and lightDirection must be non-zero");
        }
        forward.normalize();
        direction.normalize();
        if (!Float.isFinite(verticalFovRadians) || verticalFovRadians <= 0.0f
                || verticalFovRadians >= Math.PI) {
            throw new IllegalArgumentException("verticalFovRadians must be in (0, PI)");
        }
        if (!Float.isFinite(aspectRatio) || aspectRatio <= 0.0f) {
            throw new IllegalArgumentException("aspectRatio must be finite and positive");
        }
        if (!Float.isFinite(nearPlane) || nearPlane <= 0.0f
                || !Float.isFinite(farPlane) || farPlane <= nearPlane) {
            throw new IllegalArgumentException("farPlane must be finite and greater than nearPlane");
        }
        if (cascadeCount < 2 || cascadeCount > 4) {
            throw new IllegalArgumentException("cascadeCount must be in [2, 4]");
        }
        if (!Float.isFinite(logarithmicWeight) || logarithmicWeight < 0.0f
                || logarithmicWeight > 1.0f) {
            throw new IllegalArgumentException("logarithmicWeight must be in [0, 1]");
        }
        if (resolution <= 0) throw new IllegalArgumentException("resolution must be positive");

        float tangent = (float) Math.tan(verticalFovRadians * 0.5f);
        float cascadeNear = nearPlane;
        List<Cascade> result = new ArrayList<>(cascadeCount);
        for (int index = 0; index < cascadeCount; index++) {
            float ratio = (index + 1.0f) / cascadeCount;
            float logarithmic = nearPlane * (float) Math.pow(farPlane / nearPlane, ratio);
            float uniform = nearPlane + (farPlane - nearPlane) * ratio;
            float cascadeFar = index == cascadeCount - 1 ? farPlane
                    : logarithmic * logarithmicWeight + uniform * (1.0f - logarithmicWeight);
            float halfHeight = cascadeFar * tangent;
            float halfWidth = halfHeight * aspectRatio;
            float halfDepth = (cascadeFar - cascadeNear) * 0.5f;
            float radius = (float) Math.ceil(Math.sqrt(halfWidth * halfWidth
                    + halfHeight * halfHeight + halfDepth * halfDepth) * 16.0f) / 16.0f;
            float centerDepth = (cascadeNear + cascadeFar) * 0.5f;
            Vector3f center = new Vector3f(position).fma(centerDepth, forward);
            float texelWorldSize = radius * 2.0f / resolution;
            Matrix4f matrix = stabilizedMatrix(center, direction, radius, texelWorldSize);
            result.add(new Cascade(index, cascadeNear, cascadeFar, radius,
                    texelWorldSize, matrix));
            cascadeNear = cascadeFar;
        }
        return new DirectionalCascadePlan(result);
    }

    public List<Cascade> cascades() {
        return cascades;
    }

    public int select(float positiveViewDepth) {
        if (!Float.isFinite(positiveViewDepth) || positiveViewDepth < 0.0f) {
            throw new IllegalArgumentException("positiveViewDepth must be finite and non-negative");
        }
        for (Cascade cascade : cascades) {
            if (positiveViewDepth <= cascade.farDistance()) return cascade.index();
        }
        return cascades.size() - 1;
    }

    private static Matrix4f stabilizedMatrix(Vector3f center, Vector3f direction,
                                              float radius, float texelWorldSize) {
        Vector3f up = Math.abs(direction.dot(0.0f, 1.0f, 0.0f)) > 0.99f
                ? new Vector3f(0.0f, 0.0f, 1.0f) : new Vector3f(0.0f, 1.0f, 0.0f);
        Matrix4f orientation = new Matrix4f().lookAt(new Vector3f(direction).negate(),
                new Vector3f(), up);
        Vector3f centerLight = orientation.transformPosition(center, new Vector3f());
        centerLight.x = Math.round(centerLight.x / texelWorldSize) * texelWorldSize;
        centerLight.y = Math.round(centerLight.y / texelWorldSize) * texelWorldSize;
        // Quantize depth as well as XY. Otherwise a sub-texel camera translation changes
        // only the light-view Z translation, defeating the exact matrix cache key even
        // though it cannot expose a different shadow texel footprint.
        centerLight.z = Math.round(centerLight.z / texelWorldSize) * texelWorldSize;
        Vector3f snappedCenter = new Matrix4f(orientation).invert()
                .transformPosition(centerLight, new Vector3f());
        Vector3f eye = new Vector3f(snappedCenter).fma(-2.0f * radius, direction);
        Matrix4f view = new Matrix4f().lookAt(eye, snappedCenter, up);
        Matrix4f projection = new Matrix4f().ortho(-radius, radius, -radius, radius,
                0.01f, radius * 4.0f);
        return projection.mul(view);
    }

    private static Vector3f finite(Vector3fc value, String name) {
        Objects.requireNonNull(value, name);
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
        return new Vector3f(value);
    }

    public record Cascade(int index, float nearDistance, float farDistance,
                          float radius, float texelWorldSize, Matrix4f lightSpaceMatrix) {
        public Cascade {
            lightSpaceMatrix = new Matrix4f(
                    Objects.requireNonNull(lightSpaceMatrix, "lightSpaceMatrix"));
        }

        @Override
        public Matrix4f lightSpaceMatrix() {
            return new Matrix4f(lightSpaceMatrix);
        }
    }
}
