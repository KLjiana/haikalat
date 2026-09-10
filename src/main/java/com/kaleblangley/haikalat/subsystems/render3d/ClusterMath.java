package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * CPU double-precision reference for the frozen cluster math.
 *
 * <p>Tests must compare GPU output against their own independent oracle; this
 * class exists so producers and diagnostics share one documented convention
 * (clusterId = x + nx * (y + ny * z), logarithmic perspective slices, linear
 * orthographic slices).</p>
 */
final class ClusterMath {
    static final double EPSILON = 1.0e-6;

    private ClusterMath() {
    }

    static int sliceIndex(boolean perspective, double depth, double near, double far, int zSlices) {
        if (!(depth > 0.0)) return -1;
        double clamped = Math.min(Math.max(depth, near), far);
        int slice;
        if (perspective) {
            slice = (int) Math.floor(Math.log(clamped / near) * zSlices / Math.log(far / near));
        } else {
            slice = (int) Math.floor((clamped - near) / (far - near) * zSlices);
        }
        return Math.min(Math.max(slice, 0), zSlices - 1);
    }

    /** View-space linear depth of slice boundary {@code slice} in {@code [0, zSlices]}. */
    static double sliceEdge(boolean perspective, double near, double far, int slice, int zSlices) {
        if (slice <= 0) return near;
        if (slice >= zSlices) return far;
        double ratio = slice / (double) zSlices;
        return perspective ? near * Math.pow(far / near, ratio) : near + (far - near) * ratio;
    }

    static int clusterId(int x, int y, int z, int nx, int ny) {
        return x + nx * (y + ny * z);
    }

    /** Clamps a stable UV to the viewport and returns the containing tile. */
    static int[] tileOf(ClusterGrid grid, double stableU, double stableV) {
        double u = Math.min(Math.max(stableU, 0.0), 1.0 - EPSILON);
        double v = Math.min(Math.max(stableV, 0.0), 1.0 - EPSILON);
        int x = Math.min((int) Math.floor(u * grid.nx()), grid.nx() - 1);
        int y = Math.min((int) Math.floor(v * grid.ny()), grid.ny() - 1);
        return new int[]{x, y};
    }

    /**
     * Full CPU lookup for one world-space point.
     *
     * @return {@code {x, y, z, clusterId}} or {@code null} when the point is
     *         behind the camera or outside the depth range
     */
    static int[] lookup(ClusterGrid grid, Vector3f worldPosition, Matrix4f view,
                        Matrix4f stableViewProjection) {
        double viewX = view.get(0, 0) * worldPosition.x + view.get(1, 0) * worldPosition.y
                + view.get(2, 0) * worldPosition.z + view.get(3, 0);
        double viewY = view.get(0, 1) * worldPosition.x + view.get(1, 1) * worldPosition.y
                + view.get(2, 1) * worldPosition.z + view.get(3, 1);
        double viewZ = view.get(0, 2) * worldPosition.x + view.get(1, 2) * worldPosition.y
                + view.get(2, 2) * worldPosition.z + view.get(3, 2);
        double depth = -viewZ;
        int z = sliceIndex(grid.perspective(), depth, grid.nearPlane(), grid.farPlane(),
                grid.zSlices());
        if (z < 0) return null;
        double clipX = stableViewProjection.get(0, 0) * worldPosition.x
                + stableViewProjection.get(1, 0) * worldPosition.y
                + stableViewProjection.get(2, 0) * worldPosition.z
                + stableViewProjection.get(3, 0);
        double clipY = stableViewProjection.get(0, 1) * worldPosition.x
                + stableViewProjection.get(1, 1) * worldPosition.y
                + stableViewProjection.get(2, 1) * worldPosition.z
                + stableViewProjection.get(3, 1);
        double clipW = stableViewProjection.get(0, 3) * worldPosition.x
                + stableViewProjection.get(1, 3) * worldPosition.y
                + stableViewProjection.get(2, 3) * worldPosition.z
                + stableViewProjection.get(3, 3);
        if (clipW <= 0.0) return null;
        double stableU = clipX / clipW * 0.5 + 0.5;
        double stableV = clipY / clipW * 0.5 + 0.5;
        int[] tile = tileOf(grid, stableU, stableV);
        return new int[]{tile[0], tile[1], z, clusterId(tile[0], tile[1], z,
                grid.nx(), grid.ny())};
    }

    /**
     * View-space AABB of one cluster as
     * {@code [minX, minY, minZ, maxX, maxY, maxZ]}.
     */
    static double[] bounds(ClusterGrid grid, int x, int y, int z) {
        double x0 = -1.0 + 2.0 * x / grid.nx();
        double x1 = -1.0 + 2.0 * (x + 1) / grid.nx();
        double y0 = -1.0 + 2.0 * y / grid.ny();
        double y1 = -1.0 + 2.0 * (y + 1) / grid.ny();
        double expandX = 2.0 * grid.edgeExpansionPixels() / grid.width();
        double expandY = 2.0 * grid.edgeExpansionPixels() / grid.height();
        if (x == 0) x0 -= expandX;
        if (x == grid.nx() - 1) x1 += expandX;
        if (y == 0) y0 -= expandY;
        if (y == grid.ny() - 1) y1 += expandY;
        double nearEdge = sliceEdge(grid.perspective(), grid.nearPlane(), grid.farPlane(), z,
                grid.zSlices());
        double farEdge = sliceEdge(grid.perspective(), grid.nearPlane(), grid.farPlane(), z + 1,
                grid.zSlices());
        double[] minimum = new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY};
        double[] maximum = new double[]{Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY};
        double[] xy = {x0, y0, x1, y1};
        Matrix4f inverse = grid.inverseStableProjection();
        for (int index = 0; index < 4; index++) {
            double ndcX = xy[index & 1];
            double ndcY = xy[index >> 1];
            double[] nearPoint = unproject(inverse, ndcX, ndcY, -1.0);
            double[] farPoint = unproject(inverse, ndcX, ndcY, 1.0);
            for (double depth : new double[]{nearEdge, farEdge}) {
                double t = depthParameter(grid, depth);
                double[] point = new double[]{
                        nearPoint[0] + t * (farPoint[0] - nearPoint[0]),
                        nearPoint[1] + t * (farPoint[1] - nearPoint[1]),
                        nearPoint[2] + t * (farPoint[2] - nearPoint[2])};
                for (int axis = 0; axis < 3; axis++) {
                    minimum[axis] = Math.min(minimum[axis], point[axis]);
                    maximum[axis] = Math.max(maximum[axis], point[axis]);
                }
            }
        }
        return new double[]{minimum[0], minimum[1], minimum[2],
                maximum[0], maximum[1], maximum[2]};
    }

    private static double depthParameter(ClusterGrid grid, double depth) {
        double near = grid.nearPlane();
        double far = grid.farPlane();
        return (depth - near) / (far - near);
    }

    static double[] unproject(Matrix4f inverseProjection, double ndcX, double ndcY, double ndcZ) {
        double x = inverseProjection.get(0, 0) * ndcX + inverseProjection.get(1, 0) * ndcY
                + inverseProjection.get(2, 0) * ndcZ + inverseProjection.get(3, 0);
        double y = inverseProjection.get(0, 1) * ndcX + inverseProjection.get(1, 1) * ndcY
                + inverseProjection.get(2, 1) * ndcZ + inverseProjection.get(3, 1);
        double z = inverseProjection.get(0, 2) * ndcX + inverseProjection.get(1, 2) * ndcY
                + inverseProjection.get(2, 2) * ndcZ + inverseProjection.get(3, 2);
        double w = inverseProjection.get(0, 3) * ndcX + inverseProjection.get(1, 3) * ndcY
                + inverseProjection.get(2, 3) * ndcZ + inverseProjection.get(3, 3);
        if (Math.abs(w) < 1.0e-12) return new double[]{x, y, z};
        return new double[]{x / w, y / w, z / w};
    }

    /** Conservative sphere/AABB test; tangency counts as a hit. */
    static boolean sphereIntersectsAabb(double centerX, double centerY, double centerZ,
                                        double radius, double[] bounds) {
        double qx = clamp(centerX, bounds[0], bounds[3]);
        double qy = clamp(centerY, bounds[1], bounds[4]);
        double qz = clamp(centerZ, bounds[2], bounds[5]);
        double dx = centerX - qx;
        double dy = centerY - qy;
        double dz = centerZ - qz;
        return dx * dx + dy * dy + dz * dz <= radius * radius + EPSILON;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }
}
