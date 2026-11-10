package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderQueueClass;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderQueueSorter;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RenderQueueSorterTest {
    @Test
    void forwardPartitionsFourClassesAndSortsAlphaBackToFront() {
        int[] indices = {0, 1, 2, 3, 4, 5, 6, 7};
        int[] scratch = new int[indices.length];
        int[] queueClass = {2, 0, 3, 2, 0, 2, 3, 1};
        float[] depth = {2, 0, 0, 8, 0, 8, 0, 0};
        int[] shader = {9, 2, 1, 1, 1, 3, 1, 2};
        int[] material = {9, 1, 1, 1, 2, 2, 0, 0};
        int[] mesh = {9, 2, 2, 1, 1, 3, 1, 3};

        RenderQueueSorter.forward(indices, indices.length, scratch,
                queueClass, depth, shader, material, mesh);

        assertArrayEquals(new int[]{4, 1, 7, 3, 5, 0, 2, 6}, indices);
        assertArrayEquals(new int[]{3, 5, 0}, Arrays.stream(indices)
                .filter(index -> queueClass[index] == 2).toArray());
    }

    @Test
    void shadowUsesMeshThenInsertionAndLargeArenaIsDeterministic() {
        int count = 10_000;
        int[] indices = new int[count];
        int[] scratch = new int[count];
        int[] mesh = new int[count];
        for (int index = 0; index < count; index++) {
            indices[index] = index;
            mesh[index] = (index * 17) & 15;
        }

        RenderQueueSorter.shadow(indices, count, scratch, mesh);
        int[] first = indices.clone();
        RenderQueueSorter.shadow(indices, count, scratch, mesh);

        assertArrayEquals(first, indices);
        for (int index = 1; index < count; index++) {
            assertTrue(mesh[indices[index - 1]] <= mesh[indices[index]]);
            if (mesh[indices[index - 1]] == mesh[indices[index]]) {
                assertTrue(indices[index - 1] < indices[index]);
            }
        }
    }

    // Merged from the former RenderQueueClassTest: classification and shadow-cast
    // eligibility belong to the same render-queue contract as the sort order.
    @Test
    void classifyMapsMaterialStateToQueueAndCastingEligibility() throws Exception {
        var constructor = com.kaleblangley.haikalat.backend.shader.ShaderProgram.class
                .getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        var shader = constructor.newInstance(41);
        var opaque = com.kaleblangley.haikalat.core.material.Material.builder(shader).build();
        var masked = com.kaleblangley.haikalat.core.material.Material.builder(shader)
                .setFloat("uAlphaCutoff", 0.5f).build();
        var alpha = com.kaleblangley.haikalat.core.material.Material.builder(shader)
                .blendMode(com.kaleblangley.haikalat.core.BlendMode.ALPHA).build();
        var additive = com.kaleblangley.haikalat.core.material.Material.builder(shader)
                .blendMode(com.kaleblangley.haikalat.core.BlendMode.ADDITIVE).build();

        assertEquals(RenderQueueClass.OPAQUE, RenderQueueClass.classify(opaque.createInstance()));
        assertEquals(RenderQueueClass.MASKED, RenderQueueClass.classify(masked.createInstance()));
        assertEquals(RenderQueueClass.TRANSPARENT_ALPHA,
                RenderQueueClass.classify(alpha.createInstance()));
        assertEquals(RenderQueueClass.TRANSPARENT_ADDITIVE,
                RenderQueueClass.classify(additive.createInstance()));
        assertTrue(RenderQueueClass.OPAQUE.castsOpaqueShadow());
        assertTrue(RenderQueueClass.MASKED.castsOpaqueShadow());
        assertFalse(RenderQueueClass.TRANSPARENT_ALPHA.castsOpaqueShadow());
        assertFalse(RenderQueueClass.TRANSPARENT_ADDITIVE.castsOpaqueShadow());

        var override = opaque.createInstance().setFloat("uAlphaCutoff", 0.25f);
        assertEquals(RenderQueueClass.MASKED, RenderQueueClass.classify(override));
        override.setFloat("uAlphaCutoff", 0.0f);
        assertEquals(RenderQueueClass.OPAQUE, RenderQueueClass.classify(override));
    }
}
