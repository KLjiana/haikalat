package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GL-free contract for the production GPU-particle consumer.
 *
 * <p>The fake {@link GpuParticleVfx.Target} records exactly what the consumer
 * asks the owner to do, so the test proves the pause/resume/cancel/replay state
 * machine, transactional resize, sort selection, transparent boundary rejection
 * and deterministic replay without a window, shader or GL context.</p>
 */
class GpuParticleVfxContractTest {
    private static final int CAPACITY = 64;
    private static final GpuParticleSettings PARTICLE_SETTINGS = settings(CAPACITY);
    private static final Matrix4fc PROJECTION = new Matrix4f()
            .perspective((float) Math.toRadians(60.0), 1.0f, 0.1f, 100.0f);
    private static final Matrix4fc VIEW = new Matrix4f()
            .lookAt(new Vector3f(0.0f, 0.0f, 3.0f), new Vector3f(),
                    new Vector3f(0.0f, 1.0f, 0.0f));

    @Test
    void pauseStopsSteppingKeepsDrawingAndResumeContinuesTheRing() {
        GpuParticleVfx vfx = new GpuParticleVfx(new GpuParticleVfx.Settings(
                0.05f, 8, GpuParticleVfx.SortingMode.NONE));
        FakeTarget target = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        CommandBuffer commands = new CommandBuffer();

        record(vfx, target, commands);
        record(vfx, target, commands);
        record(vfx, target, commands);
        assertEquals(3L, vfx.statistics().steps());
        assertEquals(List.of(8, 8, 8), target.emissions);
        assertEquals(List.of(0.05f, 0.05f, 0.05f), target.deltas);
        assertEquals(0, vfx.statistics().culled());

        vfx.pause();
        assertEquals(GpuParticleVfx.State.PAUSED, vfx.state());
        record(vfx, target, commands);
        record(vfx, target, commands);
        assertEquals(3L, vfx.statistics().steps(),
                "paused records must not advance the fixed step");
        assertEquals(List.of(8, 8, 8), target.emissions,
                "paused records must not queue emission");
        assertEquals(List.of(0.05f, 0.05f, 0.05f, 0.0f, 0.0f), target.deltas);
        assertEquals(5, target.recordCalls, "paused records must still draw the last state");
        assertEquals(8, vfx.statistics().submitted());
        assertEquals(0, vfx.statistics().drawn());
        assertEquals(8, vfx.statistics().culled());
        assertEquals(GpuParticleVfx.State.PAUSED, vfx.statistics().state());

        vfx.resume();
        record(vfx, target, commands);
        assertEquals(4L, vfx.statistics().steps());
        assertEquals(8, target.emissions.get(target.emissions.size() - 1));
        assertEquals(0.05f, target.deltas.get(target.deltas.size() - 1));
        assertEquals(GpuParticleVfx.State.RUNNING, vfx.statistics().state());
    }

    @Test
    void cancelClearsTheLiveSetAndRequiresReplayBeforeRunningAgain() {
        GpuParticleVfx vfx = new GpuParticleVfx(new GpuParticleVfx.Settings(
                0.05f, 8, GpuParticleVfx.SortingMode.NONE));
        FakeTarget target = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        CommandBuffer commands = new CommandBuffer();
        record(vfx, target, commands);
        assertEquals(0, target.resetCalls);

        vfx.cancel();
        assertEquals(GpuParticleVfx.State.CANCELLED, vfx.state());
        assertEquals(1, target.resetCalls,
                "cancel must reset the owner's emission cursor/seed immediately");
        assertEquals(8, vfx.statistics().submitted());
        assertEquals(0, vfx.statistics().drawn());
        assertEquals(8, vfx.statistics().culled());

        int emissionsBefore = target.emissions.size();
        record(vfx, target, commands);
        assertEquals(emissionsBefore, target.emissions.size(),
                "a cancelled stream must not emit");
        assertEquals(0.0f, target.deltas.get(target.deltas.size() - 1));
        assertEquals(GpuParticleVfx.State.CANCELLED, vfx.statistics().state());
        assertThrows(IllegalStateException.class, vfx::resume,
                "cancelled streams require replay instead of resume");
        assertThrows(IllegalStateException.class, vfx::pause);

        vfx.replay();
        assertEquals(GpuParticleVfx.State.RUNNING, vfx.state());
        assertEquals(2, target.resetCalls, "replay must reset the owner seed/time");
        assertEquals(0L, vfx.statistics().steps());
        assertEquals(0L, vfx.statistics().framesRecorded());
        record(vfx, target, commands);
        assertEquals(1L, vfx.statistics().steps());
        assertEquals(8, target.emissions.get(target.emissions.size() - 1));
    }

    @Test
    void replayReproducesTheSameFixedStepSequenceAndSeed() {
        GpuParticleVfx vfx = new GpuParticleVfx(new GpuParticleVfx.Settings(
                0.05f, 6, GpuParticleVfx.SortingMode.NONE));
        FakeTarget target = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        CommandBuffer commands = new CommandBuffer();

        List<String> first = scenario(vfx, target, commands);
        assertEquals(4L, vfx.statistics().steps());
        vfx.replay();
        target.clearTrace();
        List<String> second = scenario(vfx, target, commands);

        assertEquals(first, second,
                "replay must re-run the same fixed-step emit/pause sequence");
        assertEquals(4L, vfx.statistics().steps());
        assertEquals(1, target.resetCalls,
                "replay resets the owner seed frame exactly once");
    }

    @Test
    void resizePublishesACompleteCandidateAndPreservesTheOldStateOnFailure() {
        GpuParticleVfx vfx = new GpuParticleVfx(new GpuParticleVfx.Settings(
                0.05f, 8, GpuParticleVfx.SortingMode.NONE));
        FakeTarget first = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        CommandBuffer commands = new CommandBuffer();
        record(vfx, first, commands);
        assertEquals(1L, vfx.statistics().steps());

        FakeTarget failing = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        failing.failResize = true;
        assertThrows(IllegalStateException.class, () -> vfx.resizeTarget(failing, 32));
        assertSame(first, vfx.boundTarget(),
                "a failed candidate must not replace the published owner");
        assertEquals(1L, vfx.statistics().steps());
        record(vfx, first, commands);
        assertEquals(2L, vfx.statistics().steps(),
                "the old owner must remain usable after a failed resize");

        assertThrows(IllegalArgumentException.class, () -> vfx.resizeTarget(first, 0));
        assertSame(first, vfx.boundTarget());
        assertThrows(IllegalArgumentException.class, () -> vfx.resizeTarget(first, 4),
                "the replacement capacity must fit the configured emission budget");
        assertSame(first, vfx.boundTarget());

        vfx.pause();
        GpuParticleVfx.Target candidate = vfx.resizeTarget(first, 32);
        assertEquals(32, candidate.capacity());
        assertSame(candidate, vfx.boundTarget());
        assertEquals(GpuParticleVfx.State.PAUSED, vfx.state(),
                "resize keeps the running/paused lifecycle state");
        assertEquals(0L, vfx.statistics().steps(),
                "a published candidate starts with fresh deterministic counters");
        record(vfx, (FakeTarget) candidate, commands);
        assertEquals(0L, vfx.statistics().steps(),
                "a paused candidate still draws without stepping");
        vfx.resume();
        record(vfx, (FakeTarget) candidate, commands);
        assertEquals(1L, vfx.statistics().steps());
    }

    @Test
    void sortingSelectionMustMatchTheOwnerGenerationAndCpuSortingIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuParticleVfx.Settings(0.05f, 8, GpuParticleVfx.SortingMode.CPU),
                "the GPU owner never exposes an active set for CPU sorting");

        CommandBuffer commands = new CommandBuffer();
        GpuParticleVfx unsorted = new GpuParticleVfx(new GpuParticleVfx.Settings(
                0.05f, 4, GpuParticleVfx.SortingMode.NONE));
        FakeTarget unsortedTarget = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        unsorted.record(unsortedTarget, commands, PARTICLE_SETTINGS, PROJECTION, VIEW,
                0, 32, 32, GpuParticleVfx.Boundary.BEFORE_TRANSPARENT_COLLECTORS);
        assertEquals(GpuParticleVfx.SortingMode.NONE, unsorted.statistics().sortingMode());

        GpuParticleSortingSettings gpuSorting = GpuParticleSortingSettings.enabled(
                GpuParticleSortingSettings.SortKey.VIEW_DEPTH,
                GpuParticleSortingSettings.SortDirection.BACK_TO_FRONT,
                32, GpuParticleSortingSettings.EqualKeyPolicy.STABLE);
        GpuParticleVfx sorted = new GpuParticleVfx(new GpuParticleVfx.Settings(
                0.05f, 4, GpuParticleVfx.SortingMode.GPU));
        FakeTarget sortedTarget = new FakeTarget(CAPACITY, gpuSorting);
        sorted.record(sortedTarget, commands, PARTICLE_SETTINGS, PROJECTION, VIEW,
                0, 32, 32, GpuParticleVfx.Boundary.BEFORE_TRANSPARENT_COLLECTORS);
        assertEquals(GpuParticleVfx.SortingMode.GPU, sorted.statistics().sortingMode());

        GpuParticleVfx mismatched = new GpuParticleVfx(new GpuParticleVfx.Settings(
                0.05f, 4, GpuParticleVfx.SortingMode.GPU));
        FakeTarget mismatchTarget = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        assertThrows(IllegalArgumentException.class,
                () -> mismatched.record(mismatchTarget, commands, PARTICLE_SETTINGS,
                        PROJECTION, VIEW, 0, 32, 32,
                        GpuParticleVfx.Boundary.BEFORE_TRANSPARENT_COLLECTORS),
                "GPU sorting cannot be claimed for an owner created without it");
        assertNull(mismatched.boundTarget());
        assertEquals(0, mismatchTarget.recordCalls,
                "a rejected sorting generation must record no command");
    }

    @Test
    void oitAndStochasticSurfaceClaimsFailClosedBeforeAnyCommand() {
        CommandBuffer commands = new CommandBuffer();
        for (GpuParticleVfx.Boundary boundary : List.of(
                GpuParticleVfx.Boundary.WEIGHTED_OIT_SURFACE_SET,
                GpuParticleVfx.Boundary.STOCHASTIC_TRANSPARENCY_SURFACE_SET)) {
            GpuParticleVfx vfx = new GpuParticleVfx(new GpuParticleVfx.Settings(
                    0.05f, 4, GpuParticleVfx.SortingMode.NONE));
            FakeTarget target = new FakeTarget(CAPACITY,
                    GpuParticleSortingSettings.disabled());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> vfx.record(target, commands, PARTICLE_SETTINGS, PROJECTION, VIEW,
                            0, 32, 32, boundary));
            assertTrue(failure.getMessage().contains("cannot be part of"),
                    "rejection must explain the double-composite boundary: "
                            + failure.getMessage());
            assertTrue(failure.getMessage().contains("recorded before"),
                    "rejection must state the before-collector requirement: "
                            + failure.getMessage());
            assertNull(vfx.boundTarget());
            assertEquals(0, target.recordCalls);
            assertEquals(0, target.emissions.size());
        }
    }

    @Test
    void recordValidatesTheOwnerCapacityDepthViewportAndEmissionBudget() {
        GpuParticleVfx vfx = new GpuParticleVfx(new GpuParticleVfx.Settings(
                0.05f, 100, GpuParticleVfx.SortingMode.NONE));
        FakeTarget target = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        CommandBuffer commands = new CommandBuffer();

        assertThrows(IllegalArgumentException.class,
                () -> vfx.record(target, commands, settings(32), PROJECTION, VIEW,
                        0, 32, 32, GpuParticleVfx.Boundary.BEFORE_TRANSPARENT_COLLECTORS),
                "capacity changes must go through resize");
        assertThrows(IllegalArgumentException.class,
                () -> vfx.record(target, commands, PARTICLE_SETTINGS, PROJECTION, VIEW,
                        -1, 32, 32, GpuParticleVfx.Boundary.BEFORE_TRANSPARENT_COLLECTORS));
        assertThrows(IllegalArgumentException.class,
                () -> vfx.record(target, commands, PARTICLE_SETTINGS, PROJECTION, VIEW,
                        0, 0, 32, GpuParticleVfx.Boundary.BEFORE_TRANSPARENT_COLLECTORS));
        assertThrows(IllegalArgumentException.class,
                () -> vfx.record(target, commands, PARTICLE_SETTINGS, PROJECTION, VIEW,
                        0, 32, 32, GpuParticleVfx.Boundary.BEFORE_TRANSPARENT_COLLECTORS),
                "an emission budget above capacity must fail before recording");
        assertEquals(0, target.recordCalls);
        assertEquals(0, target.emissions.size());
    }

    @Test
    void configurationIsTransactionalAndBoundsAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuParticleVfx.Settings(0.0f, 1, GpuParticleVfx.SortingMode.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuParticleVfx.Settings(0.5f, 1, GpuParticleVfx.SortingMode.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuParticleVfx.Settings(0.05f, -1, GpuParticleVfx.SortingMode.NONE));

        GpuParticleVfx vfx = new GpuParticleVfx(new GpuParticleVfx.Settings(
                0.05f, 8, GpuParticleVfx.SortingMode.NONE));
        FakeTarget target = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        record(vfx, target, new CommandBuffer());
        GpuParticleVfx.Settings original = vfx.settings();
        assertThrows(IllegalArgumentException.class,
                () -> vfx.configure(new GpuParticleVfx.Settings(
                        0.05f, CAPACITY + 1, GpuParticleVfx.SortingMode.NONE)));
        assertSame(original, vfx.settings(),
                "a rejected configuration must leave the previous value intact");
        vfx.configure(original.withEmissionPerStep(16));
        assertEquals(16, vfx.settings().emissionPerStep());
    }

    @Test
    void externallyQueuedEmissionIsReportedAndBoundedByCapacity() {
        GpuParticleVfx vfx = new GpuParticleVfx(new GpuParticleVfx.Settings(
                0.05f, 4, GpuParticleVfx.SortingMode.NONE));
        FakeTarget target = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        target.emit(6);
        record(vfx, target, new CommandBuffer());
        assertEquals(10, vfx.statistics().submitted(),
                "pre-existing owner emission must be part of the record budget");
        assertEquals(10, vfx.statistics().drawn());
        assertEquals(List.of("0.05/10"), target.trace());
        assertEquals(0, target.pendingEmission());

        FakeTarget full = new FakeTarget(CAPACITY,
                GpuParticleSortingSettings.disabled());
        full.emit(CAPACITY);
        assertThrows(IllegalArgumentException.class,
                () -> record(vfx, full, new CommandBuffer()),
                "pending plus configured emission may not exceed owner capacity");
        assertEquals(0, full.recordCalls);
        assertEquals(CAPACITY, full.pendingEmission());
    }

    private static List<String> scenario(GpuParticleVfx vfx, FakeTarget target,
                                         CommandBuffer commands) {
        record(vfx, target, commands);
        record(vfx, target, commands);
        vfx.pause();
        record(vfx, target, commands);
        vfx.resume();
        record(vfx, target, commands);
        record(vfx, target, commands);
        return List.copyOf(target.trace());
    }

    private static void record(GpuParticleVfx vfx, FakeTarget target, CommandBuffer commands) {
        vfx.record(target, commands, settings(target.capacity()), PROJECTION, VIEW,
                0, 32, 32, GpuParticleVfx.Boundary.BEFORE_TRANSPARENT_COLLECTORS);
    }

    private static GpuParticleSettings settings(int capacity) {
        return new GpuParticleSettings(capacity,
                new Vector3f(-0.5f, -0.5f, 0.0f), new Vector3f(0.5f, 0.5f, 0.0f),
                1.0f, 0.0f, 0.0f, 0.0f, 0.05f,
                new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
    }

    private static final class FakeTarget implements GpuParticleVfx.Target {
        private final int capacity;
        private final GpuParticleSortingSettings sorting;
        private final List<Integer> emissions = new ArrayList<>();
        private final List<Float> deltas = new ArrayList<>();
        private final List<String> trace = new ArrayList<>();
        private int pendingEmission;
        private int recordCalls;
        private int resetCalls;
        private int updateSettingsCalls;
        private boolean failResize;
        private boolean closed;

        private FakeTarget(int capacity, GpuParticleSortingSettings sorting) {
            this.capacity = capacity;
            this.sorting = sorting;
        }

        @Override
        public int capacity() {
            return capacity;
        }

        @Override
        public GpuParticleSortingSettings sortingSettings() {
            return sorting;
        }

        @Override
        public void updateSettings(GpuParticleSettings settings) {
            updateSettingsCalls++;
        }

        @Override
        public void emit(int count) {
            if (closed) throw new IllegalStateException("fake target is closed");
            emissions.add(count);
            pendingEmission += count;
        }

        @Override
        public void reset() {
            resetCalls++;
            pendingEmission = 0;
        }

        @Override
        public int pendingEmission() {
            return pendingEmission;
        }

        @Override
        public void record(CommandBuffer commands, float deltaSeconds, Matrix4fc viewProjection,
                           Matrix4fc view, float viewportAspect, int sceneDepthTexture,
                           int sceneWidth, int sceneHeight) {
            if (closed) throw new IllegalStateException("fake target is closed");
            recordCalls++;
            deltas.add(deltaSeconds);
            trace.add(deltaSeconds + "/" + pendingEmission);
            pendingEmission = 0;
        }

        @Override
        public GpuParticleVfx.Target resized(int newCapacity) {
            if (failResize) {
                throw new IllegalStateException("injected resize failure");
            }
            return new FakeTarget(newCapacity, sorting);
        }

        @Override
        public void close() {
            closed = true;
        }

        private List<String> trace() {
            return List.copyOf(trace);
        }

        private void clearTrace() {
            emissions.clear();
            deltas.clear();
            trace.clear();
            pendingEmission = 0;
            recordCalls = 0;
            updateSettingsCalls = 0;
        }
    }
}
