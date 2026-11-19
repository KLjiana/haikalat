package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import java.util.Objects;

/**
 * Bounded, GL-free sorting configuration of one {@link GpuParticleSystem}.
 *
 * <p>The particle owner already publishes a compacted active-index list on the
 * GPU. This value decides whether that list is reordered by a bounded GPU
 * bitonic sort before the indirect draw, which distance metric is used, and how
 * equal keys are treated. It never exposes a live count and never asks the CPU
 * to read the active set: the sort limit is a static bound, not a per-frame
 * decision.</p>
 *
 * <p>The sort key is computed from the world-space particle position through
 * the caller-supplied view matrix: {@link SortKey#VIEW_DEPTH} is the
 * view-space forward depth {@code -(view * position).z}, and
 * {@link SortKey#DISTANCE} is the Euclidean distance to the eye
 * {@code length((view * position).xyz)}. Both are positive in front of the
 * camera and work for perspective and orthographic views alike.</p>
 *
 * <p>{@link EqualKeyPolicy#STABLE} appends the particle's position in the
 * active-index list as a secondary key, so particles with the same depth keep
 * the order the update dispatch published for them. {@link EqualKeyPolicy#UNSTABLE}
 * leaves the order between equal keys unspecified; the network still produces a
 * deterministic permutation for one fixed input, but no order is promised.</p>
 *
 * <p>When sorting is enabled the draw switches from the order-insensitive
 * additive blend of the indirect owner to premultiplied alpha-over, because
 * only an order-dependent blend can observe the sorted order. A disabled value
 * keeps the exact {@link GpuParticleSystem} indirect path: no sort buffers are
 * allocated and no sort command is recorded.</p>
 *
 * <p>{@code maximumSortedParticles} bounds the sort scratch and the number of
 * active indices that participate in the sort. The scratch capacity is padded
 * to the next power of two with keys that sort after every valid key. If the
 * active set is larger than the bound, only its first
 * {@code maximumSortedParticles} entries are sorted and the remaining entries
 * follow in the order the update dispatch published; the owner validates the
 * bound against its own capacity.</p>
 */
public record GpuParticleSortingSettings(
        boolean enabled,
        SortKey key,
        SortDirection direction,
        int maximumSortedParticles,
        EqualKeyPolicy equalKeys) {
    /** Hard upper bound for one sort scratch allocation. */
    public static final int MAXIMUM_SORTED_PARTICLES = GpuParticleSettings.MAXIMUM_CAPACITY;

    /** Distance metric used as the primary sort key. */
    public enum SortKey {
        /** View-space forward depth {@code -(view * position).z}. */
        VIEW_DEPTH,
        /** Euclidean distance from the eye to the particle centre. */
        DISTANCE
    }

    /** Draw order of increasing or decreasing primary key. */
    public enum SortDirection {
        /** Near particles first; the primary key increases with distance. */
        FRONT_TO_BACK,
        /** Far particles first; the primary key decreases with distance. */
        BACK_TO_FRONT
    }

    /** Contract for particles that share the same primary key. */
    public enum EqualKeyPolicy {
        /** Equal keys keep their relative active-index order. */
        STABLE,
        /** Equal keys have no promised order. */
        UNSTABLE
    }

    public GpuParticleSortingSettings {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(equalKeys, "equalKeys");
        if (enabled) {
            if (maximumSortedParticles <= 0
                    || maximumSortedParticles > MAXIMUM_SORTED_PARTICLES) {
                throw new IllegalArgumentException("maximumSortedParticles must be in [1, "
                        + MAXIMUM_SORTED_PARTICLES + "] when sorting is enabled");
            }
        } else if (maximumSortedParticles != 0) {
            throw new IllegalArgumentException(
                    "maximumSortedParticles must be zero when sorting is disabled");
        }
    }

    /** @return the exact indirect path of the unsorted owner */
    public static GpuParticleSortingSettings disabled() {
        return new GpuParticleSortingSettings(false, SortKey.VIEW_DEPTH,
                SortDirection.FRONT_TO_BACK, 0, EqualKeyPolicy.STABLE);
    }

    /**
     * Bounded enabled value with an explicit sort limit.
     *
     * @param key                   primary distance metric
     * @param direction             near-first or far-first draw order
     * @param maximumSortedParticles sort scratch bound in [1, {@link #MAXIMUM_SORTED_PARTICLES}]
     * @param equalKeys             tie contract for equal primary keys
     * @return a validated enabled value
     */
    public static GpuParticleSortingSettings enabled(SortKey key, SortDirection direction,
                                                     int maximumSortedParticles,
                                                     EqualKeyPolicy equalKeys) {
        return new GpuParticleSortingSettings(true, key, direction,
                maximumSortedParticles, equalKeys);
    }

    /** @return a copy with only the sort scratch bound changed */
    public GpuParticleSortingSettings withMaximumSortedParticles(int value) {
        return new GpuParticleSortingSettings(enabled, key, direction, value, equalKeys);
    }
}
