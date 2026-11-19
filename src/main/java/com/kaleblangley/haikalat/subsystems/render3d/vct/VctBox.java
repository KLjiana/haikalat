package com.kaleblangley.haikalat.subsystems.render3d.vct;

import org.joml.Vector3f;

import java.util.Objects;

/** One closed world-space AABB used to describe a dynamic voxel dirty region. */
public record VctBox(Vector3f minimum, Vector3f maximum) {
    public VctBox {
        minimum = new Vector3f(Objects.requireNonNull(minimum, "minimum"));
        maximum = new Vector3f(Objects.requireNonNull(maximum, "maximum"));
        if (!minimum.isFinite() || !maximum.isFinite()) {
            throw new IllegalArgumentException("dirty box must be finite");
        }
        if (minimum.x > maximum.x || minimum.y > maximum.y || minimum.z > maximum.z) {
            throw new IllegalArgumentException("dirty box minimum must not exceed maximum");
        }
    }

    @Override public Vector3f minimum() {
        return new Vector3f(minimum);
    }

    @Override public Vector3f maximum() {
        return new Vector3f(maximum);
    }

    Vector3f minimumInternal() {
        return minimum;
    }

    Vector3f maximumInternal() {
        return maximum;
    }

    public static VctBox of(float minX, float minY, float minZ,
                            float maxX, float maxY, float maxZ) {
        return new VctBox(new Vector3f(minX, minY, minZ), new Vector3f(maxX, maxY, maxZ));
    }

    /** Smallest box containing both inputs. */
    public VctBox union(VctBox other) {
        Objects.requireNonNull(other, "other");
        return new VctBox(new Vector3f(Math.min(minimum.x, other.minimum.x),
                        Math.min(minimum.y, other.minimum.y),
                        Math.min(minimum.z, other.minimum.z)),
                new Vector3f(Math.max(maximum.x, other.maximum.x),
                        Math.max(maximum.y, other.maximum.y),
                        Math.max(maximum.z, other.maximum.z)));
    }
}
