package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent reference for the compute update semantics.
 *
 * <p>Expected values are hand-written sequences for exact binary fractions and
 * ring windows, not results read back from the production owner.</p>
 */
class GpuParticleReferenceModelTest {
    @Test
    void lifetimeBoundaryKillsExactlyOnTheCrossingFrame() {
        GpuParticleReferenceModel model = new GpuParticleReferenceModel(8);

        model.reset();
        assertEquals(8, model.step(0, 8, true, 0.375f, 0.125f));
        assertEquals(8, model.step(0, 0, false, 0.375f, 0.125f));
        assertEquals(0, model.step(0, 0, false, 0.375f, 0.125f));
        assertEquals(0, model.activeCount());
        assertFalse(model.isAlive(3));
    }

    @Test
    void emissionWindowDropsTargetsThatAreStillAlive() {
        GpuParticleReferenceModel model = new GpuParticleReferenceModel(4);

        model.reset();
        assertEquals(4, model.step(0, 4, true, 10.0f, 0.0f));
        // The ring advances by 4, so the same four live slots are targeted again
        // and the emission is dropped instead of overwriting live particles.
        assertEquals(4, model.step(0, 4, false, 10.0f, 0.0f));
        assertEquals(List.of(0, 1, 2, 3), model.activeIndices());
    }

    @Test
    void wrapAroundEmissionAndResetMatchTheDocumentedRing() {
        GpuParticleEmission emission = new GpuParticleEmission(4);
        GpuParticleReferenceModel model = new GpuParticleReferenceModel(4);

        emission.emit(3);
        GpuParticleEmission.Step first = emission.beginFrame(0.0f);
        assertEquals(3, model.step(first.spawnStart(), first.spawnCount(),
                first.clearParticles(), 1.0f, 0.0f));
        assertEquals(List.of(0, 1, 2), model.activeIndices());

        emission.emit(3);
        GpuParticleEmission.Step second = emission.beginFrame(0.0f);
        assertEquals(4, model.step(second.spawnStart(), second.spawnCount(),
                second.clearParticles(), 1.0f, 0.0f),
                "slot 3 spawns and the two still-live targets are dropped");
        assertEquals(List.of(0, 1, 2, 3), model.activeIndices());

        model.reset();
        emission.reset();
        emission.emit(3);
        GpuParticleEmission.Step replay = emission.beginFrame(0.0f);
        assertEquals(3, model.step(replay.spawnStart(), replay.spawnCount(),
                replay.clearParticles(), 1.0f, 0.0f));
        assertEquals(List.of(0, 1, 2), model.activeIndices());
    }

    @Test
    void countCanNeverExceedCapacityEvenWithContinuousEmission() {
        GpuParticleReferenceModel model = new GpuParticleReferenceModel(16);
        model.reset();

        for (int frame = 0; frame < 200; frame++) {
            int active = model.step(0, 16, frame == 0, 100.0f, 0.01f);
            assertTrue(active <= 16, "active count must stay bounded by capacity");
            assertEquals(16, active, "every slot is already alive after the first frame");
        }
        assertEquals(16, model.activeCount());
    }
}
