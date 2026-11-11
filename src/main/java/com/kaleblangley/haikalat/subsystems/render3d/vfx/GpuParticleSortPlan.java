package com.kaleblangley.haikalat.subsystems.render3d.vfx;

/**
 * GL-free dispatch plan of the bounded bitonic particle sort.
 *
 * <p>The plan is derived once from {@link GpuParticleSortingSettings#maximumSortedParticles()}
 * and never depends on the live particle count. It pads the scratch capacity to
 * the next power of two, lists the {@code (block size, compare distance)} pairs
 * of a standard bitonic network in execution order, and bounds the dispatch
 * group counts. One compute dispatch executes one compare-exchange step, so a
 * storage barrier can separate dependent steps.</p>
 *
 * <p>The network is empty for a capacity of one ({@code 2^0} has no steps); it
 * never reads or writes particle storage, so it is testable without a GL
 * context.</p>
 */
final class GpuParticleSortPlan {
    /** Workgroup size shared by the sort compute shaders. */
    static final int LOCAL_SIZE = 64;

    private final int maximumSortedParticles;
    private final int sortCapacity;
    private final int prepareGroups;
    private final int stepGroups;
    private final int[] blockSizes;
    private final int[] compareDistances;

    GpuParticleSortPlan(int maximumSortedParticles) {
        if (maximumSortedParticles <= 0
                || maximumSortedParticles > GpuParticleSortingSettings.MAXIMUM_SORTED_PARTICLES) {
            throw new IllegalArgumentException("maximumSortedParticles must be in [1, "
                    + GpuParticleSortingSettings.MAXIMUM_SORTED_PARTICLES + "]");
        }
        this.maximumSortedParticles = maximumSortedParticles;
        sortCapacity = nextPowerOfTwo(maximumSortedParticles);
        prepareGroups = groups(sortCapacity);
        int levels = Integer.numberOfTrailingZeros(sortCapacity);
        int steps = levels * (levels + 1) / 2;
        blockSizes = new int[steps];
        compareDistances = new int[steps];
        int cursor = 0;
        for (int blockSize = 2; blockSize <= sortCapacity; blockSize <<= 1) {
            for (int distance = blockSize >> 1; distance > 0; distance >>= 1) {
                blockSizes[cursor] = blockSize;
                compareDistances[cursor] = distance;
                cursor++;
            }
        }
        stepGroups = steps == 0 ? 0 : groups(sortCapacity / 2);
    }

    /** @return the validated maximum number of sorted active indices */
    int maximumSortedParticles() {
        return maximumSortedParticles;
    }

    /** @return the scratch capacity, always a power of two */
    int sortCapacity() {
        return sortCapacity;
    }

    /** @return compute groups of the key/payload preparation dispatch */
    int prepareGroups() {
        return prepareGroups;
    }

    /** @return compute groups of one compare-exchange step, zero when no step runs */
    int stepGroups() {
        return stepGroups;
    }

    /** @return number of compare-exchange steps in the network */
    int steps() {
        return blockSizes.length;
    }

    /** @param step zero-based step index */
    int blockSize(int step) {
        return blockSizes[step];
    }

    /** @param step zero-based step index */
    int compareDistance(int step) {
        return compareDistances[step];
    }

    /** @return the smallest power of two greater than or equal to {@code value} */
    static int nextPowerOfTwo(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("value must be positive");
        }
        if (value > 1 << 30) {
            throw new IllegalArgumentException("value is too large for a sort capacity");
        }
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    private static int groups(int invocations) {
        return (invocations + LOCAL_SIZE - 1) / LOCAL_SIZE;
    }
}
