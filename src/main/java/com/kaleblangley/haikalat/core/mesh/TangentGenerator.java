package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** 把带 position/UV/normal 语义的三角网格转换为 PBR canonical tangent layout。 */
public final class TangentGenerator {
    private static final float EPSILON = 1.0e-8f;
    private static final int OUTPUT_FLOATS = 12;
    private static final VertexLayout PBR_LAYOUT = VertexLayout.interleaved(
            OUTPUT_FLOATS * Float.BYTES,
            VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build(),
            VertexAttribute.builder().index(1).size(2).type(GL_FLOAT).offsetBytes(3L * Float.BYTES)
                    .semantic(VertexSemantic.TEXCOORD_0).build(),
            VertexAttribute.builder().index(2).size(3).type(GL_FLOAT).offsetBytes(5L * Float.BYTES)
                    .semantic(VertexSemantic.NORMAL).build(),
            VertexAttribute.builder().index(3).size(4).type(GL_FLOAT).offsetBytes(8L * Float.BYTES)
                    .semantic(VertexSemantic.TANGENT).build());

    private TangentGenerator() {
    }

    /** 返回固定的 position/UV/normal/tangent `0..3` PBR 布局。 */
    public static VertexLayout pbrLayout() {
        return PBR_LAYOUT;
    }

    /**
     * 生成 tangent frame；输入顶点和索引不会被修改，输出会重排为 PBR canonical layout。
     */
    public static Result generate(MeshData input) {
        Objects.requireNonNull(input, "input");
        if (input.primitiveMode() != GL_TRIANGLES) {
            throw new IllegalArgumentException("tangent generation requires GL_TRIANGLES");
        }
        VertexLayout layout = input.layout();
        if (layout.attribute(VertexSemantic.TANGENT).isPresent()) {
            throw new IllegalArgumentException("mesh already contains a TANGENT semantic");
        }
        VertexAttribute position = required(layout, VertexSemantic.POSITION, 3);
        VertexAttribute uv = required(layout, VertexSemantic.TEXCOORD_0, 2);
        VertexAttribute normal = required(layout, VertexSemantic.NORMAL, 3);
        int stride = floatStride(layout);
        float[] source = input.vertices();
        int vertexCount = input.vertexCount();
        int[] indices = input.indices();
        if (input.hasIndices()) {
            if (indices.length % 3 != 0) {
                throw new IllegalArgumentException("indexed tangent mesh must contain complete triangles");
            }
            for (int index : indices) {
                if (index < 0 || index >= vertexCount) {
                    throw new IllegalArgumentException("mesh index outside vertex range: " + index);
                }
            }
        } else if (vertexCount % 3 != 0) {
            throw new IllegalArgumentException("non-indexed tangent mesh must contain complete triangles");
        }

        Vector3f[] tangentSums = vectors(vertexCount);
        Vector3f[] bitangentSums = vectors(vertexCount);
        int fallbackTriangles = 0;
        int triangleCount = input.hasIndices() ? indices.length / 3 : vertexCount / 3;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int a = input.hasIndices() ? indices[triangle * 3] : triangle * 3;
            int b = input.hasIndices() ? indices[triangle * 3 + 1] : triangle * 3 + 1;
            int c = input.hasIndices() ? indices[triangle * 3 + 2] : triangle * 3 + 2;
            Vector3f p0 = vec3(source, a, stride, position);
            Vector3f p1 = vec3(source, b, stride, position);
            Vector3f p2 = vec3(source, c, stride, position);
            Vector2f uv0 = vec2(source, a, stride, uv);
            Vector2f uv1 = vec2(source, b, stride, uv);
            Vector2f uv2 = vec2(source, c, stride, uv);
            Vector3f edge1 = p1.sub(p0, new Vector3f());
            Vector3f edge2 = p2.sub(p0, new Vector3f());
            float du1 = uv1.x - uv0.x;
            float dv1 = uv1.y - uv0.y;
            float du2 = uv2.x - uv0.x;
            float dv2 = uv2.y - uv0.y;
            float determinant = du1 * dv2 - du2 * dv1;
            Vector3f tangent;
            Vector3f bitangent;
            if (!Float.isFinite(determinant) || Math.abs(determinant) <= EPSILON) {
                fallbackTriangles++;
                Vector3f n = normalizedNormal(source, a, stride, normal);
                tangent = orthogonal(n);
                bitangent = n.cross(tangent, new Vector3f()).normalize();
            } else {
                float inverse = 1.0f / determinant;
                tangent = new Vector3f(edge1).mul(dv2).sub(new Vector3f(edge2).mul(dv1)).mul(inverse);
                bitangent = new Vector3f(edge2).mul(du1).sub(new Vector3f(edge1).mul(du2)).mul(inverse);
                if (!finiteNonZero(tangent) || !finiteNonZero(bitangent)) {
                    fallbackTriangles++;
                    Vector3f n = normalizedNormal(source, a, stride, normal);
                    tangent = orthogonal(n);
                    bitangent = n.cross(tangent, new Vector3f()).normalize();
                }
            }
            for (int index : new int[]{a, b, c}) {
                tangentSums[index].add(tangent);
                bitangentSums[index].add(bitangent);
            }
        }

        float[] output = new float[vertexCount * OUTPUT_FLOATS];
        int fallbackVertices = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            Vector3f p = vec3(source, vertex, stride, position);
            Vector2f texCoord = vec2(source, vertex, stride, uv);
            Vector3f n = normalizedNormal(source, vertex, stride, normal);
            Vector3f t = new Vector3f(tangentSums[vertex]);
            t.sub(new Vector3f(n).mul(n.dot(t)));
            if (!finiteNonZero(t)) {
                fallbackVertices++;
                t = orthogonal(n);
            } else {
                t.normalize();
            }
            Vector3f accumulatedBitangent = bitangentSums[vertex];
            float handedness = finiteNonZero(accumulatedBitangent)
                    && n.cross(t, new Vector3f()).dot(accumulatedBitangent) < 0.0f ? -1.0f : 1.0f;
            int base = vertex * OUTPUT_FLOATS;
            output[base] = p.x;
            output[base + 1] = p.y;
            output[base + 2] = p.z;
            output[base + 3] = texCoord.x;
            output[base + 4] = texCoord.y;
            output[base + 5] = n.x;
            output[base + 6] = n.y;
            output[base + 7] = n.z;
            output[base + 8] = t.x;
            output[base + 9] = t.y;
            output[base + 10] = t.z;
            output[base + 11] = handedness;
        }
        for (float value : output) {
            if (!Float.isFinite(value)) throw new IllegalStateException("generated tangent vertex is not finite");
        }
        MeshData mesh = new MeshData(input.name(), output, indices, PBR_LAYOUT, input.primitiveMode());
        return new Result(mesh, triangleCount, fallbackTriangles, fallbackVertices);
    }

    private static VertexAttribute required(VertexLayout layout, VertexSemantic semantic, int size) {
        VertexAttribute attribute = layout.attribute(semantic).orElseThrow(() ->
                new IllegalArgumentException("mesh is missing " + semantic + " semantic"));
        if (attribute.type() != GL_FLOAT || attribute.divisor() != 0 || attribute.size() != size) {
            throw new IllegalArgumentException(semantic + " must be divisor-0 GL_FLOAT vec" + size);
        }
        if (attribute.offsetBytes() % Float.BYTES != 0L) {
            throw new IllegalArgumentException(semantic + " offset must be float aligned");
        }
        return attribute;
    }

    private static int floatStride(VertexLayout layout) {
        if (layout.strideBytes() % Float.BYTES != 0) {
            throw new IllegalArgumentException("mesh stride must be float aligned");
        }
        return layout.strideBytes() / Float.BYTES;
    }

    private static Vector3f[] vectors(int count) {
        Vector3f[] result = new Vector3f[count];
        for (int index = 0; index < count; index++) result[index] = new Vector3f();
        return result;
    }

    private static Vector3f vec3(float[] values, int vertex, int stride, VertexAttribute attribute) {
        int offset = vertex * stride + Math.toIntExact(attribute.offsetBytes() / Float.BYTES);
        Vector3f result = new Vector3f(values[offset], values[offset + 1], values[offset + 2]);
        if (!finite(result)) throw new IllegalArgumentException(attribute.semantic() + " contains non-finite data");
        return result;
    }

    private static Vector2f vec2(float[] values, int vertex, int stride, VertexAttribute attribute) {
        int offset = vertex * stride + Math.toIntExact(attribute.offsetBytes() / Float.BYTES);
        Vector2f result = new Vector2f(values[offset], values[offset + 1]);
        if (!Float.isFinite(result.x) || !Float.isFinite(result.y)) {
            throw new IllegalArgumentException(attribute.semantic() + " contains non-finite data");
        }
        return result;
    }

    private static Vector3f normalizedNormal(float[] values, int vertex, int stride,
                                             VertexAttribute normal) {
        Vector3f result = vec3(values, vertex, stride, normal);
        if (result.lengthSquared() <= EPSILON) {
            throw new IllegalArgumentException("NORMAL contains a zero-length vector");
        }
        return result.normalize();
    }

    private static Vector3f orthogonal(Vector3f normal) {
        Vector3f reference = Math.abs(normal.z) < 0.999f
                ? new Vector3f(0.0f, 0.0f, 1.0f) : new Vector3f(0.0f, 1.0f, 0.0f);
        Vector3f tangent = reference.cross(normal, new Vector3f());
        if (!finiteNonZero(tangent)) tangent.set(1.0f, 0.0f, 0.0f);
        return tangent.normalize();
    }

    private static boolean finite(Vector3f value) {
        return Float.isFinite(value.x) && Float.isFinite(value.y) && Float.isFinite(value.z);
    }

    private static boolean finiteNonZero(Vector3f value) {
        return finite(value) && value.lengthSquared() > EPSILON;
    }

    /** 一次生成的稳定结果及退化 UV/顶点回退统计。 */
    public record Result(MeshData mesh, int triangleCount,
                         int fallbackTriangleCount, int fallbackVertexCount) {
        public Result {
            Objects.requireNonNull(mesh, "mesh");
            if (triangleCount < 0 || fallbackTriangleCount < 0 || fallbackVertexCount < 0) {
                throw new IllegalArgumentException("tangent statistics must be non-negative");
            }
        }
    }
}
