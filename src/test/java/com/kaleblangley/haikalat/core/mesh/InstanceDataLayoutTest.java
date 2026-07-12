package com.kaleblangley.haikalat.core.mesh;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;

import com.kaleblangley.haikalat.core.buffer.InstanceUploadStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;

class InstanceDataLayoutTest {
    @Test
    void mat4TransformUsesFourInstancedVec4Attributes() {
        InstanceDataLayout layout = InstanceDataLayout.mat4Transform(3);

        assertEquals("mat4-transform", layout.name());
        assertEquals(16 * Float.BYTES, layout.strideBytes());
        assertTrue(layout.supportsMatrixTransforms());
        assertEquals(4, layout.attributes().size());

        for (int i = 0; i < 4; i++) {
            VertexAttribute attribute = layout.attributes().get(i);
            assertEquals(3 + i, attribute.index());
            assertEquals(4, attribute.size());
            assertEquals(GL_FLOAT, attribute.type());
            assertEquals((long) i * 4 * Float.BYTES, attribute.offsetBytes());
            assertEquals(1, attribute.divisor());
        }
    }

    @Test
    void packedTransformLayoutIsAvailableForCustomShaders() {
        InstanceDataLayout layout = InstanceDataLayout.packedTransform(6);

        assertEquals("packed-transform", layout.name());
        assertEquals(8 * Float.BYTES, layout.strideBytes());
        assertFalse(layout.supportsMatrixTransforms());
        assertEquals(2, layout.attributes().size());
        assertEquals(6, layout.attributes().get(0).index());
        assertEquals(7, layout.attributes().get(1).index());
    }

    @Test
    void colorLayoutAddsColorAttributeAfterTransform() {
        InstanceDataLayout layout = InstanceDataLayout.mat4TransformWithColor(2, 9);

        assertEquals(20 * Float.BYTES, layout.strideBytes());
        assertTrue(layout.supportsMatrixTransforms());
        assertEquals(5, layout.attributes().size());
        assertEquals(9, layout.attributes().get(4).index());
        assertEquals(16 * Float.BYTES, layout.attributes().get(4).offsetBytes());
    }

    @Test
    void customLayoutRequiresAttributes() {
        assertThrows(IllegalArgumentException.class,
                () -> InstanceDataLayout.custom("empty", 16));
    }

    @Test
    void batchStatsAndUploadStrategiesExposeStableDefaults() {
        assertEquals(new InstanceBatchStats(0, 0, 0, 0, 0), InstanceBatchStats.empty());
        assertFalse(InstanceUploadStrategy.TRIPLE_BUFFER_SUB_DATA.persistent());
        assertFalse(InstanceUploadStrategy.FENCE_PROTECTED_SUB_DATA.persistent());
        assertTrue(InstanceUploadStrategy.PERSISTENT_MAPPED.persistent());
    }
}
