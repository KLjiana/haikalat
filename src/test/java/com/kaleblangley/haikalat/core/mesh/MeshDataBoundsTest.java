package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_SHORT;

class MeshDataBoundsTest {
    @Test
    void computesPositionBySemanticOffsetAndStrideForIndexedAndNonIndexedData() {
        VertexLayout layout = VertexLayout.interleaved(6 * Float.BYTES,
                attribute(0, 2, 0, VertexSemantic.TEXCOORD_0, GL_FLOAT),
                attribute(1, 3, 2L * Float.BYTES, VertexSemantic.POSITION, GL_FLOAT),
                attribute(2, 1, 5L * Float.BYTES, VertexSemantic.CUSTOM, GL_FLOAT));
        float[] vertices = {
                0, 0, -3, 4, 2, 9,
                1, 0, 5, -2, 7, 8,
                1, 1, 1, 3, -6, 7
        };
        Bounds3f expected = Bounds3f.of(-3, -2, -6, 5, 4, 7);

        assertEquals(expected, MeshData.of("plain", vertices, layout).localBounds());
        assertEquals(expected, MeshData.indexed("indexed", vertices,
                new int[]{0, 0, 0}, layout).localBounds());
    }

    @Test
    void includesUnreferencedVerticesAndIsIndependentOfDefensiveCopies() {
        VertexLayout layout = positionLayout();
        float[] source = {0, 0, 0, 1, 1, 1, 99, -4, 2};
        MeshData data = MeshData.indexed("conservative", source, new int[]{0, 1, 0}, layout);
        source[6] = -1000;
        float[] copy = data.vertices();
        copy[0] = -2000;

        assertEquals(Bounds3f.of(0, -4, 0, 99, 1, 2), data.localBounds());
    }

    @Test
    void missingOrUnsupportedPositionFallsBackToUnbounded() {
        VertexLayout custom = VertexLayout.interleaved(3 * Float.BYTES,
                attribute(0, 3, 0, VertexSemantic.CUSTOM, GL_FLOAT));
        VertexLayout unsupported = VertexLayout.interleaved(3 * Short.BYTES,
                attribute(0, 3, 0, VertexSemantic.POSITION, GL_SHORT));

        assertTrue(MeshData.of("custom", new float[]{1, 2, 3}, custom)
                .localBounds().isUnbounded());
        assertThrows(IllegalArgumentException.class,
                () -> MeshData.of("short", new float[]{1, 2, 3}, unsupported));
    }

    @Test
    void rejectsNonFiniteSupportedPositionsAtAssetBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> MeshData.of("nan", new float[]{0, Float.NaN, 0}, positionLayout())
                        .localBounds());
        assertThrows(IllegalArgumentException.class,
                () -> MeshData.of("infinite", new float[]{0, 0, Float.NEGATIVE_INFINITY},
                        positionLayout()).localBounds());
    }

    @Test
    void builtinMainGeometryHasFiniteBounds() {
        for (String name : BuiltinMeshData.names()) {
            assertTrue(BuiltinMeshData.named(name).localBounds().isFinite(), name);
        }
    }

    private static VertexLayout positionLayout() {
        return VertexLayout.interleaved(3 * Float.BYTES,
                attribute(0, 3, 0, VertexSemantic.POSITION, GL_FLOAT));
    }

    private static VertexAttribute attribute(int location, int size, long offset,
                                             VertexSemantic semantic, int type) {
        return VertexAttribute.builder().index(location).size(size).type(type)
                .offsetBytes(offset).semantic(semantic).build();
    }
}
