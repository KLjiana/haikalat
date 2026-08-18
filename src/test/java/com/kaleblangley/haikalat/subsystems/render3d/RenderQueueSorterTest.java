package com.kaleblangley.haikalat.subsystems.render3d;

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
}
