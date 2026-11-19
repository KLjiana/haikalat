package com.kaleblangley.haikalat.subsystems.render3d.vct;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable bounded probe batch for GPU cone tracing.
 *
 * <p>One probe is a world position, a world normal, the normalized direction
 * from the surface to the camera and a roughness in {@code [0,1]}. The compute
 * trace writes two RGBA rows per probe: diffuse radiance plus remaining
 * transmittance, then specular radiance. Probes are a diagnostic and integration
 * surface: the production composite path traces per pixel instead of uploading
 * probes.</p>
 */
public final class VctProbeBatch {
    /** Maximum probes accepted by one dispatch. */
    public static final int MAX_PROBES = 4096;
    static final int FLOATS_PER_PROBE = 12;
    static final int RESULT_FLOATS_PER_PROBE = 8;

    private final int count;
    private final float[] packed;

    private VctProbeBatch(List<Probe> probes) {
        this.count = probes.size();
        this.packed = new float[Math.multiplyExact(count, FLOATS_PER_PROBE)];
        int cursor = 0;
        for (Probe probe : probes) cursor = probe.write(packed, cursor);
    }

    public static Builder builder() {
        return new Builder();
    }

    public int count() {
        return count;
    }

    public float[] packedCopy() {
        return packed.clone();
    }

    float[] packedInternal() {
        return packed;
    }

    private static final class Probe {
        final Vector3f position;
        final Vector3f normal;
        final Vector3f view;
        final float roughness;

        Probe(Vector3f position, Vector3f normal, Vector3f view, float roughness) {
            this.position = new Vector3f(Objects.requireNonNull(position, "position"));
            this.normal = new Vector3f(Objects.requireNonNull(normal, "normal"));
            this.view = new Vector3f(Objects.requireNonNull(view, "view"));
            if (!this.position.isFinite() || !this.normal.isFinite() || !this.view.isFinite()) {
                throw new IllegalArgumentException("probe position/normal/view must be finite");
            }
            if (this.normal.lengthSquared() <= 1.0e-8f || this.view.lengthSquared() <= 1.0e-8f) {
                throw new IllegalArgumentException("probe normal and view must be non-zero");
            }
            this.normal.normalize();
            this.view.normalize();
            if (!Float.isFinite(roughness) || roughness < 0.0f || roughness > 1.0f) {
                throw new IllegalArgumentException("probe roughness must be in [0,1]");
            }
            this.roughness = roughness;
        }

        int write(float[] destination, int cursor) {
            cursor = writeVec(destination, cursor, position.x, position.y, position.z, 1.0f);
            cursor = writeVec(destination, cursor, normal.x, normal.y, normal.z, roughness);
            return writeVec(destination, cursor, view.x, view.y, view.z, 0.0f);
        }

        private static int writeVec(float[] destination, int cursor,
                                    float x, float y, float z, float w) {
            destination[cursor] = x;
            destination[cursor + 1] = y;
            destination[cursor + 2] = z;
            destination[cursor + 3] = w;
            return cursor + 4;
        }
    }

    public static final class Builder {
        private final List<Probe> probes = new ArrayList<>();

        private Builder() {
        }

        public Builder addProbe(Vector3f position, Vector3f normal, Vector3f view,
                                float roughness) {
            if (probes.size() >= MAX_PROBES) {
                throw new IllegalArgumentException("at most " + MAX_PROBES + " probes are accepted");
            }
            probes.add(new Probe(position, normal, view, roughness));
            return this;
        }

        public VctProbeBatch build() {
            return new VctProbeBatch(probes);
        }
    }
}
