package com.kaleblangley.haikalat.core.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalGeneratorTest {
    @Test
    void indexedAreaWeightedNormalsAreFiniteAndInputIsUnchanged() {
        float[] positions = {0, 0, 0, 2, 0, 0, 0, 1, 0, 2, 1, 0};
        float[] original = positions.clone();
        int[] indices = {0, 1, 2, 1, 3, 2};

        NormalGenerator.Result result = NormalGenerator.generate(positions, indices);

        assertArrayEquals(original, positions);
        assertEquals(0, result.fallbackVertexCount());
        float[] normals = result.normals();
        for (int index = 2; index < normals.length; index += 3) {
            assertEquals(1.0f, normals[index], 1.0e-6f);
        }
    }

    @Test
    void degenerateVerticesUseDeterministicPositiveZFallback() {
        NormalGenerator.Result result = NormalGenerator.generate(
                new float[]{0, 0, 0, 0, 0, 0, 0, 0, 0}, new int[0]);

        assertEquals(3, result.fallbackVertexCount());
        assertArrayEquals(new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1}, result.normals());
    }
}
