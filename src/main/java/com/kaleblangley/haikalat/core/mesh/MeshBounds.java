package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

/** 只在 CPU 几何上传边界执行的 semantic-aware bounds 计算。 */
final class MeshBounds {
    private MeshBounds() {
    }

    static Bounds3f fromInterleaved(float[] vertices, VertexLayout layout) {
        VertexAttribute position = layout.attribute(VertexSemantic.POSITION).orElse(null);
        if (position == null || position.type() != GL_FLOAT || position.size() < 3
                || position.divisor() != 0 || position.offsetBytes() % Float.BYTES != 0
                || layout.strideBytes() % Float.BYTES != 0) {
            return Bounds3f.unbounded();
        }
        int stride = layout.strideBytes() / Float.BYTES;
        int offset = Math.toIntExact(position.offsetBytes() / Float.BYTES);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int base = offset; base < vertices.length; base += stride) {
            float x = vertices[base];
            float y = vertices[base + 1];
            float z = vertices[base + 2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("POSITION contains a non-finite component at vertex "
                        + ((base - offset) / stride));
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return Bounds3f.of(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
