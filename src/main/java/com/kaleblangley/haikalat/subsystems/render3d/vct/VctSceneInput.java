package com.kaleblangley.haikalat.subsystems.render3d.vct;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable bounded triangle snapshot consumed by the compute voxelizer.
 *
 * <p>Triangles are already in world space and carry the surface radiance and
 * coverage written into the voxel alpha channel. The class is a value model,
 * not a scene extractor: an integrator converts visible opaque triangles once
 * per update and bumps {@link #revision()} so the pass can distinguish a new
 * scene snapshot from repeated use of the same one.</p>
 *
 * <p>The triangle count is bounded by {@link #MAX_TRIANGLES}; exceeding it is
 * rejected before any GL command is recorded.</p>
 */
public final class VctSceneInput {
    /** Maximum triangles accepted by one voxelization update. */
    public static final int MAX_TRIANGLES = 4096;
    /** Packed floats per triangle: p0, p1, p2, normal+occupancy, radiance. */
    static final int FLOATS_PER_TRIANGLE = 20;

    private final long revision;
    private final Triangle[] triangles;
    private final float[] packed;

    private VctSceneInput(long revision, Triangle[] triangles) {
        this.revision = revision;
        this.triangles = triangles.clone();
        this.packed = new float[Math.multiplyExact(triangles.length, FLOATS_PER_TRIANGLE)];
        int cursor = 0;
        for (Triangle triangle : triangles) {
            cursor = triangle.write(packed, cursor);
        }
    }

    public static Builder builder() {
        return new Builder(0L);
    }

    public static Builder builder(long revision) {
        if (revision < 0L) throw new IllegalArgumentException("scene revision must be non-negative");
        return new Builder(revision);
    }

    public long revision() {
        return revision;
    }

    public int triangleCount() {
        return triangles.length;
    }

    /** @return an independent packed copy for diagnostics and tests */
    public float[] packedCopy() {
        return packed.clone();
    }

    float[] packedInternal() {
        return packed;
    }

    Triangle[] trianglesInternal() {
        return triangles;
    }

    static final class Triangle {
        final Vector3f a;
        final Vector3f b;
        final Vector3f c;
        final Vector3f normal;
        final Vector3f radiance;
        final float occupancy;

        Triangle(Vector3f a, Vector3f b, Vector3f c, Vector3f normal,
                 float red, float green, float blue, float occupancy) {
            this.a = new Vector3f(Objects.requireNonNull(a, "a"));
            this.b = new Vector3f(Objects.requireNonNull(b, "b"));
            this.c = new Vector3f(Objects.requireNonNull(c, "c"));
            this.normal = new Vector3f(Objects.requireNonNull(normal, "normal"));
            if (!this.a.isFinite() || !this.b.isFinite() || !this.c.isFinite()
                    || !this.normal.isFinite()) {
                throw new IllegalArgumentException("voxel triangle positions and normal must be finite");
            }
            if (this.normal.lengthSquared() <= 1.0e-8f) {
                throw new IllegalArgumentException("voxel triangle normal must be non-zero");
            }
            this.normal.normalize();
            requireFiniteNonNegative(red, "red");
            requireFiniteNonNegative(green, "green");
            requireFiniteNonNegative(blue, "blue");
            if (!Float.isFinite(occupancy) || occupancy <= 0.0f || occupancy > 1.0f) {
                throw new IllegalArgumentException("triangle occupancy must be in (0,1]");
            }
            this.radiance = new Vector3f(red, green, blue);
            this.occupancy = occupancy;
        }

        int write(float[] destination, int cursor) {
            cursor = writeVec(destination, cursor, a.x, a.y, a.z, 0.0f);
            cursor = writeVec(destination, cursor, b.x, b.y, b.z, 0.0f);
            cursor = writeVec(destination, cursor, c.x, c.y, c.z, 0.0f);
            cursor = writeVec(destination, cursor, normal.x, normal.y, normal.z, occupancy);
            return writeVec(destination, cursor, radiance.x, radiance.y, radiance.z, 0.0f);
        }

        private static int writeVec(float[] destination, int cursor,
                                    float x, float y, float z, float w) {
            destination[cursor] = x;
            destination[cursor + 1] = y;
            destination[cursor + 2] = z;
            destination[cursor + 3] = w;
            return cursor + 4;
        }

        private static void requireFiniteNonNegative(float value, String name) {
            if (!Float.isFinite(value) || value < 0.0f || value > 1024.0f) {
                throw new IllegalArgumentException(name + " radiance must be in [0,1024]");
            }
        }
    }

    public static final class Builder {
        private final List<Triangle> triangles = new ArrayList<>();
        private final long revision;

        private Builder(long revision) {
            this.revision = revision;
        }

        public Builder addTriangle(Vector3f a, Vector3f b, Vector3f c, Vector3f normal,
                                   float red, float green, float blue) {
            return addTriangle(a, b, c, normal, red, green, blue, 1.0f);
        }

        public Builder addTriangle(Vector3f a, Vector3f b, Vector3f c, Vector3f normal,
                                   float red, float green, float blue, float occupancy) {
            if (triangles.size() >= MAX_TRIANGLES) {
                throw new IllegalArgumentException("at most " + MAX_TRIANGLES
                        + " voxel triangles are accepted per update");
            }
            triangles.add(new Triangle(a, b, c, normal, red, green, blue, occupancy));
            return this;
        }

        /** Adds the two triangles of a planar quad with one shared normal. */
        public Builder addQuad(Vector3f a, Vector3f b, Vector3f c, Vector3f d,
                               Vector3f normal, float red, float green, float blue) {
            return addQuad(a, b, c, d, normal, red, green, blue, 1.0f);
        }

        /** Adds the two triangles of a planar quad with explicit coverage. */
        public Builder addQuad(Vector3f a, Vector3f b, Vector3f c, Vector3f d,
                               Vector3f normal, float red, float green, float blue,
                               float occupancy) {
            addTriangle(a, b, c, normal, red, green, blue, occupancy);
            return addTriangle(a, c, d, normal, red, green, blue, occupancy);
        }

        /** Adds the twelve triangles of a closed axis-aligned box with outward normals. */
        public Builder addBox(Vector3f minimum, Vector3f maximum,
                              float red, float green, float blue) {
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(maximum, "maximum");
            Vector3f min = new Vector3f(minimum);
            Vector3f max = new Vector3f(maximum);
            if (!min.isFinite() || !max.isFinite()
                    || min.x > max.x || min.y > max.y || min.z > max.z) {
                throw new IllegalArgumentException("box bounds must be finite and ordered");
            }
            float x0 = min.x, y0 = min.y, z0 = min.z;
            float x1 = max.x, y1 = max.y, z1 = max.z;
            Vector3f p000 = new Vector3f(x0, y0, z0);
            Vector3f p100 = new Vector3f(x1, y0, z0);
            Vector3f p110 = new Vector3f(x1, y1, z0);
            Vector3f p010 = new Vector3f(x0, y1, z0);
            Vector3f p001 = new Vector3f(x0, y0, z1);
            Vector3f p101 = new Vector3f(x1, y0, z1);
            Vector3f p111 = new Vector3f(x1, y1, z1);
            Vector3f p011 = new Vector3f(x0, y1, z1);
            addQuad(p000, p001, p011, p010, new Vector3f(-1.0f, 0.0f, 0.0f), red, green, blue);
            addQuad(p100, p110, p111, p101, new Vector3f(1.0f, 0.0f, 0.0f), red, green, blue);
            addQuad(p000, p100, p101, p001, new Vector3f(0.0f, -1.0f, 0.0f), red, green, blue);
            addQuad(p010, p011, p111, p110, new Vector3f(0.0f, 1.0f, 0.0f), red, green, blue);
            addQuad(p000, p010, p110, p100, new Vector3f(0.0f, 0.0f, -1.0f), red, green, blue);
            addQuad(p001, p101, p111, p011, new Vector3f(0.0f, 0.0f, 1.0f), red, green, blue);
            return this;
        }

        public VctSceneInput build() {
            return new VctSceneInput(revision, triangles.toArray(Triangle[]::new));
        }
    }
}
