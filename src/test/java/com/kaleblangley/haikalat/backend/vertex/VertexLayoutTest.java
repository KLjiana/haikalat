package com.kaleblangley.haikalat.backend.vertex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL11.GL_FLOAT;

class VertexLayoutTest {
    @Test
    void semanticLookupAndValidationAreDeterministic() {
        VertexAttribute position = attribute(0, 3, 0, VertexSemantic.POSITION);
        VertexAttribute uv = attribute(1, 2, 12, VertexSemantic.TEXCOORD_0);
        VertexLayout layout = VertexLayout.interleaved(20, position, uv);

        assertEquals(position, layout.attribute(VertexSemantic.POSITION).orElseThrow());
        assertEquals(uv, layout.attribute(VertexSemantic.TEXCOORD_0).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> layout.attribute(VertexSemantic.CUSTOM));
        assertThrows(IllegalArgumentException.class,
                () -> VertexLayout.interleaved(24, position,
                        attribute(0, 3, 12, VertexSemantic.NORMAL)));
        assertThrows(IllegalArgumentException.class,
                () -> VertexLayout.interleaved(24, position,
                        attribute(1, 3, 12, VertexSemantic.POSITION)));
        assertThrows(IllegalArgumentException.class,
                () -> VertexLayout.interleaved(16, position, uv));
    }

    @Test
    void tangentAndInstanceLocationsMustNotOverlap() {
        VertexLayout tangentMesh = com.kaleblangley.haikalat.core.mesh.TangentGenerator.pbrLayout();

        assertThrows(IllegalArgumentException.class,
                () -> tangentMesh.validateNoLocationOverlap(VertexLayout.instanceMatrix(3),
                        "mesh/instance"));
        assertDoesNotThrow(() -> tangentMesh.validateNoLocationOverlap(
                VertexLayout.instanceMatrix(4), "mesh/instance"));
    }

    private static VertexAttribute attribute(int location, int size, long offset,
                                             VertexSemantic semantic) {
        return VertexAttribute.builder().index(location).size(size).type(GL_FLOAT)
                .offsetBytes(offset).semantic(semantic).build();
    }
}
