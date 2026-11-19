package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingReference.Billboard;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings.SortDirection;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings.SortKey;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Independent reference values for the sorted particle path.
 *
 * <p>Expected orders are hand-written from the documented
 * {@code (metric, active position)} contract. The pixel expectations are hand
 * computed for a camera at {@code z = 3} and centred billboards: the centre
 * fragment has {@code uv = 0}, so the falloff is exactly one and the byte
 * value is {@code round(color * intensity * 255)}. These values check the
 * oracle itself before it is used as the expectation of the GL test.</p>
 */
class GpuParticleSortingReferenceTest {
    private static final int WIDTH = 65;
    private static final int HEIGHT = 65;

    @Test
    void stableOrderFollowsTheMetricAndKeepsEqualKeysInActiveOrder() {
        int[] active = {3, 7, 1};
        float[] metrics = {2.0f, 0.5f, 2.0f};

        assertArrayEquals(new int[]{7, 3, 1},
                GpuParticleSortingReference.stableSort(active, metrics, SortDirection.FRONT_TO_BACK));
        assertArrayEquals(new int[]{3, 1, 7},
                GpuParticleSortingReference.stableSort(active, metrics, SortDirection.BACK_TO_FRONT));
        assertArrayEquals(new int[]{},
                GpuParticleSortingReference.stableSort(new int[0], new float[0],
                        SortDirection.FRONT_TO_BACK));
        assertArrayEquals(new int[]{9},
                GpuParticleSortingReference.stableSort(new int[]{9}, new float[]{1.0f},
                        SortDirection.BACK_TO_FRONT));
    }

    @Test
    void nonPowerOfTwoActiveCountsSortAsAStablePermutation() {
        int[] active = {4, 2, 6, 0, 5};
        float[] metrics = {3.0f, 1.0f, 3.0f, 2.0f, 3.0f};

        int[] sorted = GpuParticleSortingReference.stableSort(active, metrics,
                SortDirection.FRONT_TO_BACK);

        assertArrayEquals(new int[]{2, 0, 4, 6, 5}, sorted);
        int[] copy = sorted.clone();
        java.util.Arrays.sort(copy);
        assertArrayEquals(new int[]{0, 2, 4, 5, 6}, copy,
                "the sorted order must be a permutation of the active list");
        assertThrows(IllegalArgumentException.class,
                () -> GpuParticleSortingReference.stableSort(active, new float[2],
                        SortDirection.FRONT_TO_BACK));
    }

    @Test
    void metricsMatchTheDocumentedViewSpaceDefinitions() {
        Matrix4f view = view();
        assertEquals(3.0f, GpuParticleSortingReference.metric(view,
                new Vector3f(0.0f, 0.0f, 0.0f), SortKey.VIEW_DEPTH), 1.0e-6f);
        assertEquals(3.0f, GpuParticleSortingReference.metric(view,
                new Vector3f(4.0f, 0.0f, 0.0f), SortKey.VIEW_DEPTH), 1.0e-6f);
        assertEquals(5.0f, GpuParticleSortingReference.metric(view,
                new Vector3f(4.0f, 0.0f, 0.0f), SortKey.DISTANCE), 1.0e-6f);
        assertEquals(4.0f, GpuParticleSortingReference.metric(view,
                new Vector3f(0.0f, 0.0f, -1.0f), SortKey.VIEW_DEPTH), 1.0e-6f);
    }

    @Test
    void smoothstepUsesTheGlslEdgeOrder() {
        assertEquals(0.0f, GpuParticleSortingReference.smoothstep(0.0f, 0.35f, 0.0f));
        assertEquals(0.5f, GpuParticleSortingReference.smoothstep(0.0f, 0.35f, 0.175f), 1.0e-6f);
        assertEquals(1.0f, GpuParticleSortingReference.smoothstep(0.0f, 0.35f, 1.0f));
        assertEquals(1.0f, GpuParticleSortingReference.smoothstep(1.0f, 0.65f, 0.65f),
                "the reversed edges fade in at the inner radius");
        assertEquals(0.0f, GpuParticleSortingReference.smoothstep(1.0f, 0.65f, 1.0f),
                "the billboard edge is fully transparent");
        assertEquals(1.0f, GpuParticleSortingReference.smoothstep(1.0f, 0.65f, 0.0f));
    }

    @Test
    void viewDepthAndEyeDistanceOrderTheGlFixtureDifferently() {
        Matrix4f view = view();
        Vector3f[] worlds = {new Vector3f(4.0f, 0.0f, 0.0f),
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, 2.0f)};

        assertEquals(3.0f, GpuParticleSortingReference.metric(view, worlds[0],
                SortKey.VIEW_DEPTH), 1.0e-6f);
        assertEquals(3.0f, GpuParticleSortingReference.metric(view, worlds[1],
                SortKey.VIEW_DEPTH), 1.0e-6f);
        assertEquals(1.0f, GpuParticleSortingReference.metric(view, worlds[2],
                SortKey.VIEW_DEPTH), 1.0e-6f);
        assertEquals(5.0f, GpuParticleSortingReference.metric(view, worlds[0],
                SortKey.DISTANCE), 1.0e-6f);
        assertEquals(3.0f, GpuParticleSortingReference.metric(view, worlds[1],
                SortKey.DISTANCE), 1.0e-6f);
        assertEquals(1.0f, GpuParticleSortingReference.metric(view, worlds[2],
                SortKey.DISTANCE), 1.0e-6f);

        // Active order [0,1,2]: slots 0 and 1 share view depth 3, so the stable
        // policy keeps slot 0 first; eye distance orders slot 1 before slot 0.
        int[] active = {0, 1, 2};
        assertArrayEquals(new int[]{2, 0, 1},
                GpuParticleSortingReference.stableSort(active, new float[]{3.0f, 3.0f, 1.0f},
                        SortDirection.FRONT_TO_BACK));
        assertArrayEquals(new int[]{2, 1, 0},
                GpuParticleSortingReference.stableSort(active, new float[]{5.0f, 3.0f, 1.0f},
                        SortDirection.FRONT_TO_BACK));
        assertArrayEquals(new int[]{0, 1, 2},
                GpuParticleSortingReference.stableSort(active, new float[]{3.0f, 3.0f, 1.0f},
                        SortDirection.BACK_TO_FRONT));

        // When the equal-depth pair itself is published in the other order, the
        // stable oracle follows that published order instead of the slot number.
        assertArrayEquals(new int[]{2, 1, 0},
                GpuParticleSortingReference.stableSort(new int[]{1, 0, 2},
                        new float[]{3.0f, 3.0f, 1.0f}, SortDirection.FRONT_TO_BACK));
    }

    @Test
    void centredBillboardCompositesTheHandComputedByteValue() {
        byte[] pixels = GpuParticleSortingReference.composite(WIDTH, HEIGHT, viewProjection(),
                List.of(new Billboard(new Vector3f(), 10.0f,
                        new Vector3f(1.0f, 1.0f, 1.0f), 0.5f, 0.5f)));

        assertEquals(128, channel(pixels, WIDTH, 32, 32, 0));
        assertEquals(128, channel(pixels, WIDTH, 32, 32, 1));
        assertEquals(128, channel(pixels, WIDTH, 32, 32, 2));
        assertEquals(0, channel(pixels, WIDTH, 0, 0, 0),
                "a pixel outside the billboard quad stays at the clear colour");
        assertEquals(0, channel(pixels, WIDTH, 42, 42, 0),
                "a pixel inside the quad but outside the unit disc is discarded");
        assertEquals(0, channel(pixels, WIDTH, 48, 48, 0));
    }

    @Test
    void drawOrderChangesTheCompositedPixelWhenDepthAndColourDiffer() {
        Billboard far = new Billboard(new Vector3f(0.0f, 0.0f, -1.0f), 10.0f,
                new Vector3f(1.0f, 0.0f, 0.0f), 1.0f, 0.5f);
        Billboard near = new Billboard(new Vector3f(0.0f, 0.0f, 1.0f), 10.0f,
                new Vector3f(0.0f, 0.0f, 1.0f), 0.5f, 0.5f);

        byte[] farToNear = GpuParticleSortingReference.composite(WIDTH, HEIGHT,
                viewProjection(), List.of(far, near));
        byte[] nearToFar = GpuParticleSortingReference.composite(WIDTH, HEIGHT,
                viewProjection(), List.of(near, far));

        // far (red, alpha 1) then near (blue, alpha 0.5):
        // R = 0 + 255 * 0.5 = 127.5 -> 128, B = 127.5 + 0 = 127.5 -> 128.
        assertEquals(128, channel(farToNear, WIDTH, 32, 32, 0));
        assertEquals(0, channel(farToNear, WIDTH, 32, 32, 1));
        assertEquals(128, channel(farToNear, WIDTH, 32, 32, 2));
        // near then far: the opaque far red replaces everything.
        assertEquals(255, channel(nearToFar, WIDTH, 32, 32, 0));
        assertEquals(0, channel(nearToFar, WIDTH, 32, 32, 1));
        assertEquals(0, channel(nearToFar, WIDTH, 32, 32, 2));
        assertNotEquals(0, differingBytes(farToNear, nearToFar),
                "the composite must observe the draw order");
    }

    @Test
    void zeroLifetimeParticleIsInvisible() {
        byte[] pixels = GpuParticleSortingReference.composite(WIDTH, HEIGHT, viewProjection(),
                List.of(new Billboard(new Vector3f(), 0.0f,
                        new Vector3f(1.0f, 1.0f, 1.0f), 1.0f, 0.5f)));
        for (int offset = 0; offset < pixels.length; offset += 4) {
            assertEquals(0, Byte.toUnsignedInt(pixels[offset]));
        }
    }

    private static int channel(byte[] pixels, int width, int x, int y, int channel) {
        return Byte.toUnsignedInt(pixels[(y * width + x) * 4 + channel]);
    }

    private static int differingBytes(byte[] left, byte[] right) {
        int differing = 0;
        for (int offset = 0; offset < left.length; offset += 4) {
            boolean same = left[offset] == right[offset]
                    && left[offset + 1] == right[offset + 1]
                    && left[offset + 2] == right[offset + 2];
            if (!same) {
                differing++;
            }
        }
        return differing;
    }

    private static Matrix4f view() {
        return new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 3.0f),
                new Vector3f(), new Vector3f(0.0f, 1.0f, 0.0f));
    }

    private static Matrix4f viewProjection() {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(45.0), 1.0f, 0.1f, 20.0f);
        return new Matrix4f(projection).mul(view());
    }
}
