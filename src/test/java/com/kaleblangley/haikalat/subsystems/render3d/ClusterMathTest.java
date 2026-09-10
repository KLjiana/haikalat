package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent arithmetic checks for the frozen cluster conventions.  The
 * expected values here are computed directly from the specification, not by
 * calling {@link ClusterMath} helpers.
 */
class ClusterMathTest {
    @Test
    void perspectiveSlicesFollowTheLogarithmicEdgeFormula() {
        float near = 0.1f;
        float far = 100.0f;
        int slices = 24;
        for (int slice = 0; slice <= slices; slice++) {
            double edge = near * Math.pow((double) far / near, slice / (double) slices);
            assertEquals(edge, ClusterMath.sliceEdge(true, near, far, slice, slices), 1.0e-6);
        }
        for (int slice = 0; slice < slices; slice++) {
            double edge = near * Math.pow((double) far / near, slice / (double) slices);
            double inside = edge * 1.0001;
            assertEquals(slice, ClusterMath.sliceIndex(true, inside, near, far, slices));
        }
        assertEquals(0, ClusterMath.sliceIndex(true, near * 0.5, near, far, slices));
        assertEquals(slices - 1, ClusterMath.sliceIndex(true, far * 2.0, near, far, slices));
        assertEquals(-1, ClusterMath.sliceIndex(true, -1.0, near, far, slices));
    }

    @Test
    void orthographicSlicesUseLinearDepth() {
        float near = 0.5f;
        float far = 50.0f;
        int slices = 16;
        float width = (far - near) / slices;
        for (int slice = 0; slice < slices; slice++) {
            assertEquals(slice, ClusterMath.sliceIndex(false, near + width * slice + width * 0.5f,
                    near, far, slices));
        }
        assertEquals(slices - 1, ClusterMath.sliceIndex(false, far, near, far, slices));
        assertEquals(0, ClusterMath.sliceIndex(false, near, near, far, slices));
        assertEquals(near + width * 3, ClusterMath.sliceEdge(false, near, far, 3, slices), 1.0e-6);
    }

    @Test
    void clusterIdUsesXThenYThenZOrder() {
        int nx = 5;
        int ny = 3;
        assertEquals(0, ClusterMath.clusterId(0, 0, 0, nx, ny));
        assertEquals(4, ClusterMath.clusterId(4, 0, 0, nx, ny));
        assertEquals(5, ClusterMath.clusterId(0, 1, 0, nx, ny));
        assertEquals(nx * ny, ClusterMath.clusterId(0, 0, 1, nx, ny));
        assertEquals(nx * ny * 2 + 4, ClusterMath.clusterId(4, 0, 2, nx, ny));
    }

    @Test
    void tileLookupClampsStableUvToTheViewport() {
        ExternalCamera camera = perspectiveCamera();
        ClusterGrid grid = ClusterGrid.create(camera, 100, 70,
                ClusteredLightingSettings.builder().tileSize(32).zSlices(8).build(), 0.0f);
        assertEquals(4, grid.nx());
        assertEquals(3, grid.ny());
        assertEquals(96, grid.clusterCount());

        int[] left = ClusterMath.tileOf(grid, -0.5, 0.5);
        assertEquals(0, left[0]);
        int[] right = ClusterMath.tileOf(grid, 1.5, 1.5);
        assertEquals(3, right[0]);
        assertEquals(2, right[1]);
    }

    @Test
    void lookupMatchesAnalyticProjection() {
        ExternalCamera camera = perspectiveCamera();
        int width = 200;
        int height = 100;
        ClusterGrid grid = ClusterGrid.create(camera, width, height,
                ClusteredLightingSettings.builder().tileSize(50).zSlices(12).build(), 0.0f);
        Vector3f world = new Vector3f(0.0f, 0.0f, -8.0f);
        int[] lookup = ClusterMath.lookup(grid, world, camera.getViewMatrix(new Matrix4f()),
                camera.viewProjection());
        assertNotNull(lookup);
        assertEquals(2, lookup[0]);
        assertEquals(1, lookup[1]);
        int expectedSlice = (int) Math.floor(
                Math.log(8.0 / 0.1f) * 12 / Math.log(100.0f / 0.1f));
        assertEquals(expectedSlice, lookup[2]);
        assertEquals(ClusterMath.clusterId(lookup[0], lookup[1], lookup[2], grid.nx(), grid.ny()),
                lookup[3]);
    }

    @Test
    void lookupRejectsPointsBehindTheCamera() {
        ExternalCamera camera = perspectiveCamera();
        ClusterGrid grid = ClusterGrid.create(camera, 320, 180,
                ClusteredLightingSettings.defaults(), 0.0f);
        assertNull(ClusterMath.lookup(grid, new Vector3f(0.0f, 0.0f, 5.0f),
                camera.getViewMatrix(new Matrix4f()), camera.viewProjection()));
    }

    @Test
    void boundsCoverSliceEdgesAndRespectJitterExpansion() {
        ExternalCamera camera = perspectiveCamera();
        ClusterGrid grid = ClusterGrid.create(camera, 128, 128,
                ClusteredLightingSettings.builder().tileSize(64).zSlices(4).build(), 0.0f);
        double[] bounds = ClusterMath.bounds(grid, 0, 0, 1);
        assertTrue(bounds[0] < bounds[3]);
        assertTrue(bounds[1] < bounds[4]);
        assertTrue(bounds[2] < bounds[5]);
        // Near edge of slice 1 in view space is -(near * (far/near)^(1/4)).
        double edge = 0.1 * Math.pow(1000.0, 1.0 / 4.0);
        assertEquals(-edge, bounds[5], 1.0e-3);

        ClusterGrid expanded = ClusterGrid.create(camera, 128, 128,
                ClusteredLightingSettings.builder().tileSize(64).zSlices(4).build(), 2.0f);
        double[] expandedBounds = ClusterMath.bounds(expanded, 0, 0, 1);
        assertTrue(expandedBounds[0] < bounds[0],
                "outer tile expansion must widen the left boundary");
        assertTrue(expandedBounds[3] - expandedBounds[0] > bounds[3] - bounds[0]);
    }

    @Test
    void sphereAabbTestIsConservativeAtTangency() {
        double[] box = new double[]{-1, -1, -1, 1, 1, 1};
        assertTrue(ClusterMath.sphereIntersectsAabb(2.0, 0.0, 0.0, 1.0, box));
        assertTrue(ClusterMath.sphereIntersectsAabb(0.0, 0.0, 0.0, 0.5, box));
        assertFalse(ClusterMath.sphereIntersectsAabb(2.5, 0.0, 0.0, 1.0, box));
    }

    @Test
    void orthographicGridUsesLinearSlices() {
        Matrix4f projection = new Matrix4f().ortho(-2.0f, 2.0f, -1.5f, 1.5f, 0.1f, 40.0f);
        Matrix4f view = new Matrix4f();
        ExternalCamera camera = new ExternalCamera(view, projection,
                new Matrix4f(projection).mul(view), new Vector3f(), 0.0f,
                0.1f, 40.0f, 1L);
        ClusterGrid grid = ClusterGrid.create(camera, 256, 128,
                ClusteredLightingSettings.builder().zSlices(8).build(), 0.0f);
        assertFalse(grid.perspective());
        assertEquals(0.1f, ClusterMath.sliceEdge(false, 0.1f, 40.0f, 0, 8), 1.0e-6);
        assertEquals(40.0f, ClusterMath.sliceEdge(false, 0.1f, 40.0f, 8, 8), 1.0e-6);
        int[] lookup = ClusterMath.lookup(grid, new Vector3f(0.0f, 0.0f, -20.0f),
                camera.getViewMatrix(new Matrix4f()), camera.viewProjection());
        assertNotNull(lookup);
        int expected = (int) Math.floor((20.0 - 0.1) / (40.0 - 0.1) * 8);
        assertEquals(expected, lookup[2]);
    }

    private static ExternalCamera perspectiveCamera() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60.0),
                16.0f / 9.0f, 0.1f, 100.0f);
        Matrix4f view = new Matrix4f();
        return new ExternalCamera(view, projection, new Matrix4f(projection).mul(view),
                new Vector3f(), 0.0f, 0.1f, 100.0f, 1L);
    }
}
