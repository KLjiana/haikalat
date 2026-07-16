package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.assets.LoadedModel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_SHORT;

class TangentGeneratorTest {
    private static final float EPSILON = 0.0001f;

    @Test
    void indexedQuadProducesCanonicalPositiveXTangentWithoutMutatingInput() {
        float[] vertices = quadVertices();
        float[] original = vertices.clone();
        MeshData input = MeshData.indexed("quad", vertices, new int[]{0, 1, 2, 0, 2, 3},
                LoadedModel.VertexFormat.POSITION_NORMAL_UV.layout());

        TangentGenerator.Result result = TangentGenerator.generate(input);
        float[] output = result.mesh().vertices();

        assertArrayEquals(original, vertices);
        assertEquals(2, result.triangleCount());
        assertEquals(0, result.fallbackTriangleCount());
        assertEquals(VertexSemantic.TANGENT,
                result.mesh().layout().attributes().get(3).semantic());
        for (int vertex = 0; vertex < 4; vertex++) {
            int tangent = vertex * 12 + 8;
            assertEquals(1.0f, output[tangent], EPSILON);
            assertEquals(0.0f, output[tangent + 1], EPSILON);
            assertEquals(0.0f, output[tangent + 2], EPSILON);
            assertEquals(1.0f, output[tangent + 3], EPSILON);
        }
    }

    @Test
    void mirroredUvProducesNegativeHandedness() {
        MeshData input = MeshData.of("mirror", new float[]{
                0, 0, 0, 0, 0, 1, 0, 0,
                1, 0, 0, 0, 0, 1, -1, 0,
                0, 1, 0, 0, 0, 1, 0, 1
        }, LoadedModel.VertexFormat.POSITION_NORMAL_UV.layout());

        float[] output = TangentGenerator.generate(input).mesh().vertices();
        assertEquals(-1.0f, output[11], EPSILON);
    }

    @Test
    void degenerateUvUsesFiniteFallbackForIndexedAndNonIndexedMeshes() {
        float[] triangle = {
                0, 0, 0, 0, 0, 1, 0, 0,
                1, 0, 0, 0, 0, 1, 0, 0,
                0, 1, 0, 0, 0, 1, 0, 0
        };
        MeshData nonIndexed = MeshData.of("degenerate", triangle,
                LoadedModel.VertexFormat.POSITION_NORMAL_UV.layout());
        MeshData indexed = MeshData.indexed("degenerate-indexed", triangle,
                new int[]{0, 1, 2}, LoadedModel.VertexFormat.POSITION_NORMAL_UV.layout());

        for (MeshData mesh : new MeshData[]{nonIndexed, indexed}) {
            TangentGenerator.Result result = TangentGenerator.generate(mesh);
            assertEquals(1, result.fallbackTriangleCount());
            for (float value : result.mesh().vertices()) assertTrue(Float.isFinite(value));
        }
    }

    @Test
    void invalidSemanticTypeAndExistingTangentFailFast() {
        VertexLayout missingUv = VertexLayout.interleaved(6 * Float.BYTES,
                attribute(0, 3, GL_FLOAT, 0, VertexSemantic.POSITION),
                attribute(1, 3, GL_FLOAT, 12, VertexSemantic.NORMAL));
        MeshData missing = MeshData.of("missing", new float[]{0, 0, 0, 0, 0, 1}, missingUv);
        assertThrows(IllegalArgumentException.class, () -> TangentGenerator.generate(missing));

        VertexLayout shortUv = VertexLayout.interleaved(8 * Float.BYTES,
                attribute(0, 3, GL_FLOAT, 0, VertexSemantic.POSITION),
                attribute(1, 3, GL_FLOAT, 12, VertexSemantic.NORMAL),
                attribute(2, 2, GL_SHORT, 24, VertexSemantic.TEXCOORD_0));
        MeshData wrongType = MeshData.of("short-uv", new float[8 * 3], shortUv);
        assertThrows(IllegalArgumentException.class, () -> TangentGenerator.generate(wrongType));

        MeshData generated = TangentGenerator.generate(MeshData.indexed("quad", quadVertices(),
                new int[]{0, 1, 2, 0, 2, 3},
                LoadedModel.VertexFormat.POSITION_NORMAL_UV.layout())).mesh();
        assertThrows(IllegalArgumentException.class, () -> TangentGenerator.generate(generated));
    }

    private static float[] quadVertices() {
        return new float[]{
                -1, -1, 0, 0, 0, 1, 0, 0,
                1, -1, 0, 0, 0, 1, 1, 0,
                1, 1, 0, 0, 0, 1, 1, 1,
                -1, 1, 0, 0, 0, 1, 0, 1
        };
    }

    private static VertexAttribute attribute(int index, int size, int type, long offset,
                                             VertexSemantic semantic) {
        return VertexAttribute.builder().index(index).size(size).type(type).offsetBytes(offset)
                .semantic(semantic).build();
    }
}
