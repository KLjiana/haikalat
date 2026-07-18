package com.kaleblangley.haikalat.core.mesh;

import java.util.Objects;

/** 为三角形网格生成 area-weighted smooth normal。 */
public final class NormalGenerator {
    private static final float EPSILON = 1.0e-12f;

    private NormalGenerator() {}

    public static Result generate(float[] positions, int[] indices) {
        Objects.requireNonNull(positions, "positions");
        indices = indices == null ? new int[0] : indices.clone();
        if (positions.length == 0 || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must contain vec3 vertices");
        }
        int vertexCount = positions.length / 3;
        int elementCount = indices.length == 0 ? vertexCount : indices.length;
        if (elementCount % 3 != 0) throw new IllegalArgumentException("triangle count is incomplete");
        for (float value : positions) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("position contains non-finite value");
        }
        float[] sums = new float[positions.length];
        for (int i = 0; i < elementCount; i += 3) {
            int a = indices.length == 0 ? i : checked(indices[i], vertexCount);
            int b = indices.length == 0 ? i + 1 : checked(indices[i + 1], vertexCount);
            int c = indices.length == 0 ? i + 2 : checked(indices[i + 2], vertexCount);
            int ao = a * 3;
            int bo = b * 3;
            int co = c * 3;
            float e1x = positions[bo] - positions[ao];
            float e1y = positions[bo + 1] - positions[ao + 1];
            float e1z = positions[bo + 2] - positions[ao + 2];
            float e2x = positions[co] - positions[ao];
            float e2y = positions[co + 1] - positions[ao + 1];
            float e2z = positions[co + 2] - positions[ao + 2];
            float nx = e1y * e2z - e1z * e2y;
            float ny = e1z * e2x - e1x * e2z;
            float nz = e1x * e2y - e1y * e2x;
            float lengthSquared = nx * nx + ny * ny + nz * nz;
            if (Float.isFinite(lengthSquared) && lengthSquared > EPSILON) {
                add(sums, ao, nx, ny, nz);
                add(sums, bo, nx, ny, nz);
                add(sums, co, nx, ny, nz);
            }
        }
        float[] normals = new float[positions.length];
        int fallbacks = 0;
        for (int i = 0; i < vertexCount; i++) {
            int offset = i * 3;
            float nx = sums[offset];
            float ny = sums[offset + 1];
            float nz = sums[offset + 2];
            float lengthSquared = nx * nx + ny * ny + nz * nz;
            if (!Float.isFinite(lengthSquared) || lengthSquared <= EPSILON) {
                nx = 0.0f;
                ny = 0.0f;
                nz = 1.0f;
                fallbacks++;
            } else {
                float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
                nx *= inverseLength;
                ny *= inverseLength;
                nz *= inverseLength;
            }
            normals[offset] = nx;
            normals[offset + 1] = ny;
            normals[offset + 2] = nz;
        }
        return new Result(normals, fallbacks);
    }

    private static int checked(int index, int count) {
        if (index < 0 || index >= count) throw new IllegalArgumentException("index outside vertex range: " + index);
        return index;
    }
    private static void add(float[] sums, int offset, float x, float y, float z) {
        sums[offset] += x;
        sums[offset + 1] += y;
        sums[offset + 2] += z;
    }

    public record Result(float[] normals, int fallbackVertexCount) {
        public Result { normals = normals.clone(); }
        @Override public float[] normals() { return normals.clone(); }
    }
}
