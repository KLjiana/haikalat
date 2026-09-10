package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;

import java.util.Objects;

/**
 * Immutable CPU description of the frame's clustered-forward grid.
 *
 * <p>Bounds are expressed in view space and only depend on the stable
 * projection, the viewport extent and the frozen settings.  Camera rotation or
 * translation does not change this grid.</p>
 */
final class ClusterGrid {
    private final int width;
    private final int height;
    private final int tileSize;
    private final int zSlices;
    private final int nx;
    private final int ny;
    private final int nz;
    private final int clusterCount;
    private final float nearPlane;
    private final float farPlane;
    private final boolean perspective;
    private final float edgeExpansionPixels;
    private final Matrix4f view;
    private final Matrix4f stableProjection;
    private final Matrix4f inverseStableProjection;
    private final Matrix4f stableViewProjection;
    private final long signature;

    private ClusterGrid(int width, int height, int tileSize, int zSlices,
                        float nearPlane, float farPlane, boolean perspective,
                        float edgeExpansionPixels, Matrix4f view,
                        Matrix4f stableProjection, Matrix4f inverseStableProjection,
                        Matrix4f stableViewProjection) {
        this.width = width;
        this.height = height;
        this.tileSize = tileSize;
        this.zSlices = zSlices;
        this.nx = (width + tileSize - 1) / tileSize;
        this.ny = (height + tileSize - 1) / tileSize;
        this.nz = zSlices;
        this.clusterCount = Math.multiplyExact(Math.multiplyExact(nx, ny), nz);
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
        this.perspective = perspective;
        this.edgeExpansionPixels = edgeExpansionPixels;
        this.view = new Matrix4f(view);
        this.stableProjection = new Matrix4f(stableProjection);
        this.inverseStableProjection = new Matrix4f(inverseStableProjection);
        this.stableViewProjection = new Matrix4f(stableViewProjection);
        this.signature = signatureOf(width, height, tileSize, zSlices, nearPlane, farPlane,
                perspective, edgeExpansionPixels, stableProjection);
    }

    static ClusterGrid create(ExternalCamera camera, int width, int height,
                              ClusteredLightingSettings settings,
                              float edgeExpansionPixels) {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(settings, "settings");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("cluster grid extent must be positive");
        }
        if (!Float.isFinite(edgeExpansionPixels) || edgeExpansionPixels < 0.0f) {
            throw new IllegalArgumentException("edgeExpansionPixels must be finite and non-negative");
        }
        Matrix4f projection = camera.projection(new Matrix4f());
        if (!projection.isFinite()) {
            throw new IllegalArgumentException("clustered forward requires a finite camera projection");
        }
        boolean perspective = Math.abs(projection.m33()) < 0.5f;
        if (Math.abs(projection.m20()) > 1.0e-5f || Math.abs(projection.m21()) > 1.0e-5f) {
            throw new IllegalArgumentException(
                    "clustered forward does not support oblique camera projections");
        }
        if (!perspective && Math.abs(projection.m33() - 1.0f) > 1.0e-4f) {
            throw new IllegalArgumentException(
                    "clustered forward supports standard perspective or orthographic projections only");
        }
        Matrix4f inverse = new Matrix4f(projection).invert();
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        return new ClusterGrid(width, height, settings.tileSize(), settings.zSlices(),
                camera.nearPlane(), camera.farPlane(), perspective, edgeExpansionPixels,
                view, projection, inverse, viewProjection);
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int tileSize() {
        return tileSize;
    }

    int zSlices() {
        return zSlices;
    }

    int nx() {
        return nx;
    }

    int ny() {
        return ny;
    }

    int nz() {
        return nz;
    }

    int clusterCount() {
        return clusterCount;
    }

    float nearPlane() {
        return nearPlane;
    }

    float farPlane() {
        return farPlane;
    }

    boolean perspective() {
        return perspective;
    }

    float edgeExpansionPixels() {
        return edgeExpansionPixels;
    }

    Matrix4f view() {
        return new Matrix4f(view);
    }

    Matrix4f stableProjection() {
        return new Matrix4f(stableProjection);
    }

    Matrix4f inverseStableProjection() {
        return new Matrix4f(inverseStableProjection);
    }

    Matrix4f stableViewProjection() {
        return new Matrix4f(stableViewProjection);
    }

    long signature() {
        return signature;
    }

    /** Bounds are shared as long as the stable projection and grid shape are unchanged. */
    boolean sameBoundsAs(ClusterGrid other) {
        return other != null && signature == other.signature;
    }

    private static long signatureOf(int width, int height, int tileSize, int zSlices,
                                    float nearPlane, float farPlane, boolean perspective,
                                    float edgeExpansionPixels, Matrix4f projection) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, width);
        hash = mix(hash, height);
        hash = mix(hash, tileSize);
        hash = mix(hash, zSlices);
        hash = mix(hash, Float.floatToIntBits(nearPlane));
        hash = mix(hash, Float.floatToIntBits(farPlane));
        hash = mix(hash, perspective ? 1L : 0L);
        hash = mix(hash, Float.floatToIntBits(edgeExpansionPixels));
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                hash = mix(hash, Float.floatToIntBits(projection.get(column, row)));
            }
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }
}
