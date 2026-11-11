package com.kaleblangley.haikalat.subsystems.render3d.vfx;

/**
 * CPU-side emission cursor of one {@link GpuParticleSystem}.
 *
 * <p>The GPU owns the live count; this value only decides which ring slots the
 * next update dispatch may spawn into. It is deliberately GL-free so the
 * bounded emission contract can be tested without a context. The shader reads
 * the resulting {@link Step} uniforms and performs the atomic active-index
 * append on the GPU.</p>
 *
 * <p>Emission targets are a deterministic ring window
 * {@code [spawnStart, spawnStart + spawnCount)} modulo capacity. A target slot
 * that is still alive keeps its particle and the emission for that slot is
 * dropped; nothing is overwritten. The cursor still advances so the next frame
 * looks at the following slots.</p>
 */
final class GpuParticleEmission {
    private final int capacity;
    private int pendingEmission;
    private int nextSpawnSlot;
    private int frameIndex;
    private boolean resetPending = true;

    GpuParticleEmission(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    int capacity() {
        return capacity;
    }

    /** Number of particles queued for the next record. */
    int pendingEmission() {
        return pendingEmission;
    }

    boolean resetPending() {
        return resetPending;
    }

    /**
     * Queues particles for later frames.
     *
     * @param count non-negative particle count
     * @throws IllegalArgumentException when the queue would exceed capacity or
     *                                  {@code count} is negative
     */
    void emit(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("emission count must be non-negative");
        }
        if (count > capacity - pendingEmission) {
            throw new IllegalArgumentException("emission queue would exceed capacity "
                    + capacity + ": pending " + pendingEmission + ", requested " + count);
        }
        pendingEmission += count;
    }

    /**
     * Consumes all queued emission for one record.
     *
     * @param deltaSeconds finite non-negative frame delta
     * @return immutable uniforms and state transition for this frame
     */
    Step beginFrame(float deltaSeconds) {
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        Step step = new Step(nextSpawnSlot, pendingEmission, frameIndex, resetPending);
        nextSpawnSlot = (nextSpawnSlot + pendingEmission) % capacity;
        pendingEmission = 0;
        frameIndex++;
        resetPending = false;
        return step;
    }

    /** Clears queued emission and makes the next record a deterministic replay start. */
    void reset() {
        pendingEmission = 0;
        nextSpawnSlot = 0;
        frameIndex = 0;
        resetPending = true;
    }

    /**
     * Values consumed by one update dispatch.
     *
     * @param spawnStart      first ring slot the emission window covers
     * @param spawnCount      number of ring slots the window covers
     * @param frameIndex      monotonic seed frame; reset restarts it at zero
     * @param clearParticles  true when the dispatch must first zero every slot
     */
    record Step(int spawnStart, int spawnCount, int frameIndex, boolean clearParticles) {
    }
}
