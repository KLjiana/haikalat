package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Independent CPU reference for the compute update contract.
 *
 * <p>It re-implements the documented emission/lifetime rules with plain Java
 * floats and arrays instead of calling production helpers, so the GL active
 * count and index set can be compared against a second implementation. It does
 * not model positions or colours; those are not part of the active-set oracle.
 * The same {@code float} subtraction order as the shader is used on purpose.</p>
 */
final class GpuParticleReferenceModel {
    private final int capacity;
    private final boolean[] alive;
    private final float[] remaining;

    GpuParticleReferenceModel(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        alive = new boolean[capacity];
        remaining = new float[capacity];
    }

    int capacity() {
        return capacity;
    }

    void reset() {
        Arrays.fill(alive, false);
        Arrays.fill(remaining, 0.0f);
    }

    /**
     * Applies one update dispatch.
     *
     * @param spawnStart  first ring slot of the emission window
     * @param spawnCount  number of ring slots covered, in [0, capacity]
     * @param clear       true when every slot is cleared before emission
     * @param lifetime    lifetime assigned to a new particle in seconds
     * @param delta       frame delta in seconds
     * @return the active count after the step
     */
    int step(int spawnStart, int spawnCount, boolean clear,
             float lifetimeSeconds, float deltaSeconds) {
        if (spawnCount < 0 || spawnCount > capacity) {
            throw new IllegalArgumentException("spawnCount must be in [0, capacity]");
        }
        if (spawnStart < 0 || spawnStart >= capacity) {
            throw new IllegalArgumentException("spawnStart must be a valid ring slot");
        }
        int active = 0;
        for (int index = 0; index < capacity; index++) {
            if (clear) {
                alive[index] = false;
                remaining[index] = 0.0f;
            }
            int offset = index - spawnStart;
            if (offset < 0) {
                offset += capacity;
            }
            if (!alive[index] && offset < spawnCount) {
                alive[index] = true;
                remaining[index] = lifetimeSeconds;
            }
            if (alive[index]) {
                remaining[index] -= deltaSeconds;
                if (remaining[index] > 0.0f) {
                    active++;
                } else {
                    alive[index] = false;
                }
            }
        }
        return active;
    }

    boolean isAlive(int index) {
        return alive[index];
    }

    float remainingSeconds(int index) {
        return remaining[index];
    }

    int activeCount() {
        int count = 0;
        for (boolean value : alive) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    List<Integer> activeIndices() {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < capacity; index++) {
            if (alive[index]) {
                result.add(index);
            }
        }
        return result;
    }
}
