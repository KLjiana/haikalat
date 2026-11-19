package com.kaleblangley.haikalat.subsystems.render3d.vct;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent JVM double reference for voxel indexing, mip sampling and cone
 * weights on fixed inputs.
 *
 * <p>Every expected value comes either from {@link VctReference} (a separately
 * written implementation) or from a hand-computed literal; no assertion uses
 * the production helper to derive its own expected value.</p>
 */
class VctReferenceTest {
    private static final float DELTA = 1.0e-5f;

    @Test
    void voxelIndexingMatchesIndependentIntervalSearchOnFixedInputs() {
        VctSettings settings = VctSettings.enabled(32, 16.0f); // 1 world unit per voxel, [-16,16]
        float[] fixed = {-16.0f, -15.999f, -15.5f, -15.0f, -0.001f, 0.0f, 0.5f,
                15.999f, 16.0f, 100.0f, -100.0f};
        int[] expected = {0, 0, 0, 1, 15, 16, 16, 31, 31, 31, 0};
        for (int index = 0; index < fixed.length; index++) {
            assertEquals(expected[index], VctVoxelMath.voxelAxis(settings, fixed[index], 0),
                    "x=" + fixed[index]);
            assertEquals(expected[index], VctReference.voxelAxis(settings, fixed[index], 0),
                    "independent x=" + fixed[index]);
        }
        for (int index = 0; index < 400; index++) {
            float world = -40.0f + index * 0.211f;
            assertEquals(VctReference.voxelAxis(settings, world, 0),
                    VctVoxelMath.voxelAxis(settings, world, 0), "grid x=" + world);
            assertEquals(VctReference.voxelAxis(settings, world, 1),
                    VctVoxelMath.voxelAxis(settings, world, 1), "grid y=" + world);
            assertEquals(VctReference.voxelAxis(settings, world, 2),
                    VctVoxelMath.voxelAxis(settings, world, 2), "grid z=" + world);
        }
        assertTrue(VctVoxelMath.contains(settings, 0.0f, 0.0f, 0.0f));
        assertTrue(VctVoxelMath.contains(settings, -16.0f, 16.0f, 0.0f));
        assertFalse(VctVoxelMath.contains(settings, 16.0001f, 0.0f, 0.0f));
        assertFalse(VctReference.contains(settings, 0.0f, -16.0001f, 0.0f));
    }

    @Test
    void linearIndexUsesXFastestLayerOrderAndVoxelCentersStayInsideTheCube() {
        VctSettings settings = VctSettings.enabled(32, 16.0f);
        assertEquals(0L, VctVoxelMath.linearIndex(0, 0, 0, 32));
        assertEquals(31L, VctVoxelMath.linearIndex(31, 0, 0, 32));
        assertEquals(32L, VctVoxelMath.linearIndex(0, 1, 0, 32));
        assertEquals(1024L, VctVoxelMath.linearIndex(0, 0, 1, 32));
        assertEquals(32767L, VctVoxelMath.linearIndex(31, 31, 31, 32));

        Vector3f first = VctVoxelMath.voxelCenter(settings, 0, 0, 0);
        assertEquals(-15.5f, first.x, 0.0f);
        assertEquals(-15.5f, first.y, 0.0f);
        assertEquals(-15.5f, first.z, 0.0f);
        Vector3f last = VctVoxelMath.voxelCenter(settings, 31, 31, 31);
        assertEquals(15.5f, last.x, 0.0f);
        Vector3f uv = VctVoxelMath.volumeUv(settings, first);
        assertEquals(1.0f / 64.0f, uv.x, DELTA);
        assertEquals(1.0f / 64.0f, uv.y, DELTA);
        assertEquals(1.0f / 64.0f, uv.z, DELTA);
    }

    @Test
    void mipDownsampleMatchesIndependentWeightsAndHandComputedValues() {
        int dimension = 4;
        float[] base = new float[dimension * dimension * dimension * 4];
        // One lit texel at (1,1,1): it must land in destination (0,0,0) unchanged.
        int lit = (((1 * dimension) + 1) * dimension + 1) * 4;
        base[lit] = 2.0f;
        base[lit + 1] = 4.0f;
        base[lit + 2] = 6.0f;
        base[lit + 3] = 1.0f;
        float[] expected = new float[2 * 2 * 2 * 4];
        expected[0] = 2.0f;
        expected[1] = 4.0f;
        expected[2] = 6.0f;
        expected[3] = 1.0f;
        assertArrayEquals(expected, VctVoxelMath.downsampleMip(base, dimension), 0.0f);

        // Deterministic fractional coverage: weighted radiance and maximum alpha.
        float[] patterned = new float[dimension * dimension * dimension * 4];
        for (int texel = 0; texel < dimension * dimension * dimension; texel++) {
            patterned[texel * 4] = texel * 0.25f;
            patterned[texel * 4 + 1] = 1.0f;
            patterned[texel * 4 + 2] = 0.5f;
            patterned[texel * 4 + 3] = (texel % 3 == 0) ? 0.25f : 0.75f;
        }
        float[] independent = VctReference.downsample(patterned, dimension);
        assertArrayEquals(independent, VctVoxelMath.downsampleMip(patterned, dimension), DELTA);
        // Hand-computed destination (0,0,0) from source indices 0,1,4,5,16,17,20,21:
        // weighted red 13.125, weight 5.0, maximum coverage 0.75.
        assertEquals(2.625f, independent[0], DELTA);
        assertEquals(0.75f, independent[3], 0.0f);
    }

    @Test
    void trilinearMipSamplingMatchesIndependentCornerWeightsOnFixedInputs() {
        int dimension = 4;
        float[] base = new float[dimension * dimension * dimension * 4];
        int lit = ((1 * dimension + 2) * dimension + 1) * 4; // (x=1,y=2,z=1)
        base[lit] = 1.5f;
        base[lit + 1] = 2.5f;
        base[lit + 2] = 3.5f;
        base[lit + 3] = 1.0f;
        float[] level1 = VctVoxelMath.downsampleMip(base, dimension);
        float[][] levels = {base, level1};

        for (float u : new float[]{0.125f, 0.3125f, 0.5f, 0.8125f}) {
            for (float v : new float[]{0.125f, 0.4375f, 0.75f}) {
                for (float w : new float[]{0.125f, 0.625f}) {
                    float[] expected = VctReference.sample(base, dimension, u, v, w);
                    assertArrayEquals(expected,
                            VctVoxelMath.sampleTrilinear(levels, dimension, u, v, w, 0.0f), DELTA,
                            "lod0 u=" + u + " v=" + v + " w=" + w);
                }
            }
        }
        for (float lod : new float[]{0.0f, 0.25f, 0.5f, 0.75f, 1.0f}) {
            float[] expected = VctReference.sampleMip(base, dimension, 0.375f, 0.625f, 0.375f, lod);
            float[] actual = VctVoxelMath.sampleTrilinear(levels, dimension,
                    0.375f, 0.625f, 0.375f, lod);
            assertArrayEquals(expected, actual, DELTA, "lod=" + lod);
        }
        // Hand-computed texel center at lod 0.
        float[] center = VctVoxelMath.sampleTrilinear(levels, dimension,
                0.375f, 0.625f, 0.375f, 0.0f);
        assertEquals(1.5f, center[0], DELTA);
        assertEquals(2.5f, center[1], DELTA);
        assertEquals(3.5f, center[2], DELTA);
        assertEquals(1.0f, center[3], DELTA);
    }

    @Test
    void coneFootprintApertureAndStepMatchIndependentFormulasAndLiterals() {
        float voxelSize = 0.5f;
        float[] radii = {0.0f, 0.25f, 0.5f, 1.0f, 2.0f, 5.0f};
        for (float radius : radii) {
            assertEquals(VctReference.mipLevelForCone(radius, voxelSize),
                    VctConeMath.mipLevelForCone(radius, voxelSize), DELTA,
                    "radius=" + radius);
        }
        assertEquals(0.0f, VctConeMath.mipLevelForCone(0.25f, 0.5f), 0.0f);
        assertEquals(1.0f, VctConeMath.mipLevelForCone(0.5f, 0.5f), DELTA);
        assertEquals(2.0f, VctConeMath.mipLevelForCone(1.0f, 0.5f), DELTA);
        assertEquals((float) (Math.log(6.0) / Math.log(2.0)),
                VctConeMath.mipLevelForCone(1.5f, 0.5f), DELTA);

        for (float roughness : new float[]{0.0f, 0.25f, 0.5f, 0.75f, 1.0f}) {
            assertEquals(VctReference.apertureForRoughness(roughness, 1.0f),
                    VctConeMath.apertureForRoughness(roughness, 1.0f), 0.0f);
        }
        assertEquals(0.02f, VctConeMath.apertureForRoughness(0.0f, 1.0f), 0.0f);
        assertEquals(0.25f, VctConeMath.apertureForRoughness(0.5f, 1.0f), DELTA);
        assertEquals(1.125f, VctConeMath.apertureForRoughness(0.75f, 2.0f), DELTA);
        assertEquals(1.2f, VctConeMath.apertureForRoughness(1.0f, 4.0f), 0.0f);

        assertEquals(1.0f, VctConeMath.stepDistance(1.0f, 0.5f), 0.0f);
        assertEquals(3.0f, VctConeMath.stepDistance(1.0f, 4.0f), 0.0f);
        assertEquals(0.375f, VctConeMath.stepDistance(0.25f, 0.5f), 0.0f);
        assertEquals(1.0f, VctConeMath.stepDistance(1.0f, 0.0f), 0.0f);

        float[] weights = VctConeMath.mipWeights(2.25f);
        assertEquals(2.0f, weights[0], 0.0f);
        assertEquals(0.25f, weights[1], 0.0f);
        float[] integral = VctConeMath.mipWeights(3.0f);
        assertEquals(3.0f, integral[0], 0.0f);
        assertEquals(0.0f, integral[1], 0.0f);
    }
}
