package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * 无需 OpenGL context 即可创建、解析和测试的纯网格数据。
 * 上传该数据后会创建运行时 {@link Mesh}。
 */
public record MeshData(
        String name,
        float[] vertices,
        int[] indices,
        VertexLayout layout,
        int primitiveMode
) {
    public MeshData {
        name = Objects.requireNonNull(name, "name");
        vertices = Objects.requireNonNull(vertices, "vertices").clone();
        indices = indices == null ? new int[0] : indices.clone();
        layout = Objects.requireNonNull(layout, "layout");
        validate(vertices, layout);
    }

    public static MeshData of(String name, float[] vertices, VertexLayout layout) {
        return new MeshData(name, vertices, new int[0], layout, GL_TRIANGLES);
    }

    public static MeshData indexed(String name, float[] vertices, int[] indices, VertexLayout layout) {
        return new MeshData(name, vertices, indices, layout, GL_TRIANGLES);
    }

    public boolean hasIndices() {
        return indices.length > 0;
    }

    public int vertexCount() {
        return vertices.length / floatsPerVertex(layout);
    }

    /** 查询 POSITION 是否满足指定 shader location 和最小分量数。 */
    public boolean hasPositionAttribute(int location, int minimumComponents) {
        return hasAttribute(VertexSemantic.POSITION, location, minimumComponents);
    }

    /** 查询 TEXCOORD_0 是否满足指定 shader location 和最小分量数。 */
    public boolean hasTexCoord0Attribute(int location, int minimumComponents) {
        return hasAttribute(VertexSemantic.TEXCOORD_0, location, minimumComponents);
    }

    /**
     * 按 POSITION semantic 和交错布局计算局部包围盒。
     *
     * @return 可可靠推导时为有限 AABB，否则为显式 unbounded
     */
    public Bounds3f localBounds() {
        return MeshBounds.fromInterleaved(vertices, layout);
    }

    @Override
    public float[] vertices() {
        return vertices.clone();
    }

    @Override
    public int[] indices() {
        return indices.clone();
    }

    private static void validate(float[] vertices, VertexLayout layout) {
        int floatsPerVertex = floatsPerVertex(layout);
        if (vertices.length == 0 || vertices.length % floatsPerVertex != 0) {
            throw new IllegalArgumentException("vertex data is not divisible by layout stride");
        }
    }

    private boolean hasAttribute(VertexSemantic semantic, int location, int minimumComponents) {
        if (location < 0) throw new IllegalArgumentException("location must be non-negative");
        if (minimumComponents <= 0 || minimumComponents > 4) {
            throw new IllegalArgumentException("minimumComponents must be in [1, 4]");
        }
        return layout.attribute(semantic)
                .filter(attribute -> attribute.index() == location)
                .filter(attribute -> attribute.size() >= minimumComponents)
                .isPresent();
    }

    private static int floatsPerVertex(VertexLayout layout) {
        if (layout.strideBytes() % Float.BYTES != 0) {
            throw new IllegalArgumentException("layout stride must be aligned to float size");
        }
        int floatsPerVertex = layout.strideBytes() / Float.BYTES;
        if (floatsPerVertex <= 0) {
            throw new IllegalArgumentException("layout stride must contain at least one float");
        }
        return floatsPerVertex;
    }
}
