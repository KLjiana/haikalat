package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded emission and replay contract of the particle owner.
 *
 * <p>The tests use clock values that are exact binary fractions so the expected
 * active-count sequence is independent of floating-point rounding, and they
 * drive the same ring-window semantics the compute shader consumes.</p>
 */
class GpuParticleEmissionTest {
    @Test
    void queueIsBoundedByCapacityAndRejectsInvalidCounts() {
        GpuParticleEmission emission = new GpuParticleEmission(8);

        assertTrue(emission.resetPending(), "a fresh owner must reset before its first dispatch");
        emission.emit(5);
        emission.emit(3);
        assertEquals(8, emission.pendingEmission());

        assertThrows(IllegalArgumentException.class, () -> emission.emit(1),
                "queued emission must never exceed capacity between records");
        assertThrows(IllegalArgumentException.class, () -> emission.emit(-1));

        emission.beginFrame(0.0f);
        assertEquals(0, emission.pendingEmission());
        assertFalse(emission.resetPending());
        emission.emit(8);
        assertEquals(8, emission.pendingEmission());
    }

    @Test
    void ringCursorWrapsAndConsumesAllQueuedEmissionPerRecord() {
        GpuParticleEmission emission = new GpuParticleEmission(4);
        List<GpuParticleEmission.Step> steps = new ArrayList<>();

        emission.emit(3);
        steps.add(emission.beginFrame(0.0f));
        emission.emit(3);
        steps.add(emission.beginFrame(0.0f));
        emission.emit(2);
        steps.add(emission.beginFrame(0.0f));
        steps.add(emission.beginFrame(0.0f));

        assertEquals(List.of(
                new GpuParticleEmission.Step(0, 3, 0, true),
                new GpuParticleEmission.Step(3, 3, 1, false),
                new GpuParticleEmission.Step(2, 2, 2, false),
                new GpuParticleEmission.Step(0, 0, 3, false)), steps);
    }

    @Test
    void resetRestartsTheCursorAndSeedFrameForDeterministicReplay() {
        GpuParticleEmission emission = new GpuParticleEmission(8);
        List<GpuParticleEmission.Step> first = new ArrayList<>();
        int[] counts = {2, 4, 1};

        for (int count : counts) {
            emission.emit(count);
            first.add(emission.beginFrame(1.0f / 60.0f));
        }

        emission.reset();
        assertTrue(emission.resetPending());
        assertEquals(0, emission.pendingEmission());

        List<GpuParticleEmission.Step> replay = new ArrayList<>();
        for (int count : counts) {
            emission.emit(count);
            replay.add(emission.beginFrame(1.0f / 60.0f));
        }

        assertEquals(first, replay, "reset must reproduce the exact step sequence");
        assertEquals(0, replay.get(0).frameIndex());
        assertTrue(replay.get(0).clearParticles());
    }

    @Test
    void invalidDeltaAndCapacityAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleEmission(0));
        GpuParticleEmission emission = new GpuParticleEmission(4);

        assertThrows(IllegalArgumentException.class, () -> emission.beginFrame(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> emission.beginFrame(Float.NaN));
        assertEquals(0, emission.beginFrame(0.0f).spawnCount());
        assertThrows(IllegalArgumentException.class,
                () -> emission.beginFrame(Float.POSITIVE_INFINITY));
    }
}
