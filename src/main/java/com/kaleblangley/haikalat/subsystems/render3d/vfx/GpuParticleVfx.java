package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Objects;

/**
 * Production VFX consumer for one {@link GpuParticleSystem} active set.
 *
 * <p>The consumer turns the owner into a fixed-step, depth-tested transparent
 * draw. Each {@link #record} either advances exactly one configured fixed step
 * (running) or records a zero-delta draw of the last state (paused/cancelled).
 * The owner still owns the particle storage and the GPU indirect command; this
 * class owns only the consumer-side lifecycle, emission budget and bounded
 * statistics. No GPU count is ever read back.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *   <li>{@link #pause()} stops stepping but keeps drawing the last state. A
 *       paused record uses a zero delta and no emission, so it cannot change
 *       life, collision or positions; the owner's update program is still
 *       recorded because the draw shares the same command stream.</li>
 *   <li>{@link #resume()} returns to fixed-step updates and continues the same
 *       emission ring; it is rejected after {@link #cancel()}.</li>
 *   <li>{@link #cancel()} drops the consumer's live set immediately on the CPU
 *       side ({@link GpuParticleSystem#reset()} drops queued emission and arms
 *       the next record to clear every slot) and then draws nothing. A
 *       cancelled stream must be restarted with {@link #replay()}.</li>
 *   <li>{@link #replay()} resets both the owner's emission cursor/seed frame
 *       and the consumer step/frame counters, then resumes fixed-step
 *       recording. The same emit sequence therefore reproduces the same
 *       active set and pixels, including any pause frames in the sequence.</li>
 * </ul>
 *
 * <h2>Transparent boundary</h2>
 *
 * <p>The GPU-particle draw is a standalone depth-tested alpha/additive billboard
 * draw. It is never part of a caller-selected weighted-OIT or
 * stochastic-transparency surface set and must be recorded before those
 * collectors when they share the same colour target. The
 * {@link Boundary#BEFORE_TRANSPARENT_COLLECTORS} value is the only accepted
 * claim; the two overlap claims fail closed with a clear exception instead of
 * silently double-compositing. The consumer cannot inspect a caller's surface
 * list, so the caller must make the ordering claim explicitly through the
 * boundary overload.</p>
 *
 * <h2>Statistics</h2>
 *
 * <p>{@link Statistics#submitted()} is the configured emission budget for the
 * record, {@link Statistics#drawn()} is the subset handed to the recorded
 * update+draw step, and {@link Statistics#culled()} is the paused/cancelled
 * remainder. These are CPU-side emission budgets, not a GPU-confirmed live or
 * visible particle count; the owner deliberately never reads that count.</p>
 */
public final class GpuParticleVfx {
    /** Default fixed simulation step, matching deterministic 60 Hz demos. */
    public static final float DEFAULT_FIXED_TIME_STEP_SECONDS = 1.0f / 60.0f;
    /** Largest accepted fixed step; prevents a single frame from stalling. */
    public static final float MAXIMUM_FIXED_TIME_STEP_SECONDS = 0.25f;
    /** Conservative default emission budget; callers opt into spawning. */
    public static final int DEFAULT_EMISSION_PER_STEP = 0;
    /** Hard upper bound for the per-step emission budget. */
    public static final int MAXIMUM_EMISSION_PER_STEP = GpuParticleSettings.MAXIMUM_CAPACITY;

    private final Matrix4f viewProjectionScratch = new Matrix4f();
    private final Matrix4f viewScratch = new Matrix4f();
    private Settings settings;
    private State state = State.RUNNING;
    private Target boundTarget;
    private long steps;
    private long framesRecorded;
    private Statistics statistics = new Statistics(0, 0, 0, SortingMode.NONE,
            0L, 0L, State.RUNNING);

    /** Creates a consumer with {@link Settings#defaults()}. */
    public GpuParticleVfx() {
        this(Settings.defaults());
    }

    /**
     * Creates a consumer configuration.
     *
     * @param settings fixed step, emission budget and sorting selection
     */
    public GpuParticleVfx(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        statistics = new Statistics(0, 0, 0, settings.sortingMode(), 0L, 0L, State.RUNNING);
    }

    /** @return the immutable configuration used by the next record */
    public Settings settings() {
        return settings;
    }

    /**
     * Replaces the consumer configuration before a record.
     *
     * <p>A configuration change is transactional: an invalid sorting selection
     * or an emission budget larger than the bound owner capacity throws and
     * keeps the previous settings.</p>
     *
     * @param value new configuration
     * @return this consumer
     */
    public GpuParticleVfx configure(Settings value) {
        Objects.requireNonNull(value, "value");
        if (boundTarget != null) {
            if (value.emissionPerStep() > boundTarget.capacity()
                    - boundTarget.pendingEmission()) {
                throw new IllegalArgumentException("emissionPerStep " + value.emissionPerStep()
                        + " exceeds the bound GPU particle capacity "
                        + boundTarget.capacity() + " with pending emission "
                        + boundTarget.pendingEmission());
            }
            requireMatchingSorting(value, boundTarget);
        }
        settings = value;
        if (boundTarget == null) {
            statistics = new Statistics(statistics.submitted(), statistics.drawn(),
                    statistics.culled(), value.sortingMode(), statistics.steps(),
                    statistics.framesRecorded(), state);
        }
        return this;
    }

    /** @return the lifecycle state of this consumer */
    public State state() {
        return state;
    }

    /** @return the last recorded bounded statistics snapshot */
    public Statistics statistics() {
        return statistics;
    }

    /**
     * Stops fixed-step updates while continuing to draw the last state.
     *
     * @throws IllegalStateException after {@link #cancel()}; use {@link #replay()}
     */
    public void pause() {
        if (state == State.CANCELLED) {
            throw new IllegalStateException("a cancelled GPU particle stream must be restarted "
                    + "with replay()");
        }
        if (state == State.PAUSED) {
            return;
        }
        state = State.PAUSED;
        statistics = withState(statistics, state);
    }

    /**
     * Resumes fixed-step updates from the current emission ring.
     *
     * @throws IllegalStateException after {@link #cancel()}; use {@link #replay()}
     */
    public void resume() {
        if (state == State.CANCELLED) {
            throw new IllegalStateException("a cancelled GPU particle stream must be restarted "
                    + "with replay()");
        }
        if (state == State.RUNNING) {
            return;
        }
        state = State.RUNNING;
        statistics = withState(statistics, state);
    }

    /**
     * Cancels the live set and queues its deterministic clear.
     *
     * <p>The CPU-side emission cursor is reset immediately. The bound owner
     * clears its GPU slots on the next recorded zero-step frame, which also
     * draws nothing. The stream stays cancelled until {@link #replay()}.</p>
     */
    public void cancel() {
        state = State.CANCELLED;
        if (boundTarget != null) {
            boundTarget.reset();
        }
        statistics = new Statistics(settings.emissionPerStep(), 0,
                settings.emissionPerStep(), currentSortingMode(), steps, framesRecorded, state);
    }

    /**
     * Restarts from the deterministic beginning and resumes fixed-step updates.
     *
     * <p>The owner's emission cursor, ring slot and seed frame return to zero;
     * the consumer's step/frame counters also return to zero. Recording the
     * same emit/record sequence therefore reproduces the same active set and
     * pixels.</p>
     */
    public void replay() {
        if (boundTarget != null) {
            boundTarget.reset();
        }
        state = State.RUNNING;
        steps = 0L;
        framesRecorded = 0L;
        statistics = new Statistics(0, 0, 0, currentSortingMode(), 0L, 0L, state);
    }

    /**
     * Prepares a replacement owner with another capacity through
     * {@link GpuParticleSystem#resized(int)}.
     *
     * <p>The current owner is not mutated. On success the returned candidate is
     * bound to this consumer and the consumer step/frame counters restart at
     * zero while the paused/running state is preserved. On failure the
     * exception propagates and both the published owner and this consumer's
     * state stay exactly as they were.</p>
     *
     * @param current    currently published owner
     * @param newCapacity capacity in [1, {@link GpuParticleSettings#MAXIMUM_CAPACITY}]
     * @return a complete replacement candidate to publish and pass to the next
     *         {@link #record}
     */
    public GpuParticleSystem resize(GpuParticleSystem current, int newCapacity) {
        Objects.requireNonNull(current, "current");
        Target target = boundSystem(current);
        return ((SystemTarget) resizeTarget(target, newCapacity)).system();
    }

    /**
     * Records one fixed-step update and the indirect particle draw against a
     * caller-owned scene colour target.
     *
     * <p>This overload uses
     * {@link Boundary#BEFORE_TRANSPARENT_COLLECTORS}; callers that share the
     * colour target with an OIT or stochastic-transparency collector must use
     * the boundary overload and record before that collector.</p>
     *
     * @param commands        target command buffer
     * @param system          published GPU particle owner
     * @param particleSettings settings with the same capacity as the owner
     * @param projection      camera projection matrix
     * @param view            camera view matrix
     * @param sceneDepthTexture same-frame single-sample opaque scene depth, or
     *                          0 for a depth-less/free-flight owner
     * @param viewportWidth   colour target width in pixels
     * @param viewportHeight  colour target height in pixels
     * @return the bounded statistics snapshot for this record
     */
    public Statistics record(CommandBuffer commands, GpuParticleSystem system,
                             GpuParticleSettings particleSettings, Matrix4fc projection,
                             Matrix4fc view, int sceneDepthTexture, int viewportWidth,
                             int viewportHeight) {
        return record(commands, system, particleSettings, projection, view,
                sceneDepthTexture, viewportWidth, viewportHeight,
                Boundary.BEFORE_TRANSPARENT_COLLECTORS);
    }

    /**
     * Records one fixed-step update and the indirect particle draw with an
     * explicit transparent-collector boundary claim.
     *
     * <p>Only {@link Boundary#BEFORE_TRANSPARENT_COLLECTORS} is accepted. A
     * caller that claims the same draw is part of an OIT or stochastic surface
     * set is rejected before any command is recorded, because that would
     * double-composite the particle colour.</p>
     *
     * @param commands        target command buffer
     * @param system          published GPU particle owner
     * @param particleSettings settings with the same capacity as the owner
     * @param projection      camera projection matrix
     * @param view            camera view matrix
     * @param sceneDepthTexture same-frame single-sample opaque scene depth, or
     *                          0 for a depth-less/free-flight owner
     * @param viewportWidth   colour target width in pixels
     * @param viewportHeight  colour target height in pixels
     * @param boundary        caller claim about the transparent collectors
     * @return the bounded statistics snapshot for this record
     * @throws IllegalArgumentException when the claim overlaps a transparent
     *                                  collector or the owner generation does
     *                                  not match the configuration
     */
    public Statistics record(CommandBuffer commands, GpuParticleSystem system,
                             GpuParticleSettings particleSettings, Matrix4fc projection,
                             Matrix4fc view, int sceneDepthTexture, int viewportWidth,
                             int viewportHeight, Boundary boundary) {
        Objects.requireNonNull(system, "system");
        return record(boundSystem(system), commands, particleSettings, projection, view,
                sceneDepthTexture, viewportWidth, viewportHeight, boundary);
    }

    /**
     * Package-private recording seam used by the GL-free contract test.
     *
     * <p>It has the same validation, lifecycle and statistics semantics as the
     * public system overload, but writes through a {@link Target} so no GL
     * context is needed to prove the consumer state machine.</p>
     */
    Statistics record(Target target, CommandBuffer commands, GpuParticleSettings particleSettings,
                      Matrix4fc projection, Matrix4fc view, int sceneDepthTexture,
                      int viewportWidth, int viewportHeight, Boundary boundary) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(particleSettings, "particleSettings");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(boundary, "boundary");
        requireAcceptedBoundary(boundary);
        if (sceneDepthTexture < 0) {
            throw new IllegalArgumentException("sceneDepthTexture must be >= 0");
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("viewport dimensions must be positive");
        }
        if (particleSettings.capacity() != target.capacity()) {
            throw new IllegalArgumentException("particle settings capacity "
                    + particleSettings.capacity() + " does not match the owner capacity "
                    + target.capacity() + "; create the owner with the same capacity and resize "
                    + "through GpuParticleVfx.resize(...)");
        }
        SortingMode sortingMode = requireMatchingSorting(settings, target);
        if (settings.emissionPerStep() > target.capacity()) {
            throw new IllegalArgumentException("emissionPerStep " + settings.emissionPerStep()
                    + " exceeds the GPU particle capacity " + target.capacity());
        }
        int pending = target.pendingEmission();
        if (pending > target.capacity() - settings.emissionPerStep()) {
            throw new IllegalArgumentException("the owner's pending emission " + pending
                    + " plus the configured emissionPerStep " + settings.emissionPerStep()
                    + " exceeds the GPU particle capacity " + target.capacity());
        }
        if (boundTarget != target) {
            bind(target);
        }
        target.updateSettings(particleSettings);
        viewProjectionScratch.set(projection).mul(viewScratch.set(view));
        float aspect = viewportWidth / (float) viewportHeight;
        int submitted = pending + settings.emissionPerStep();
        int drawn = 0;
        if (state == State.RUNNING) {
            target.emit(settings.emissionPerStep());
            target.record(commands, settings.fixedTimeStepSeconds(), viewProjectionScratch,
                    viewScratch, aspect, sceneDepthTexture, viewportWidth, viewportHeight);
            drawn = submitted;
            steps = Math.incrementExact(steps);
        } else {
            // A zero-delta, no-emission record is a draw of the last state.
            // Passing no depth also prevents the collision program from
            // modifying a paused particle when the owner has collision enabled.
            target.record(commands, 0.0f, viewProjectionScratch, viewScratch, aspect,
                    0, viewportWidth, viewportHeight);
        }
        framesRecorded = Math.incrementExact(framesRecorded);
        statistics = new Statistics(submitted, drawn, submitted - drawn, sortingMode,
                steps, framesRecorded, state);
        return statistics;
    }

    /**
     * Package-private transactional resize seam used by the GL-free contract
     * test.
     */
    Target resizeTarget(Target current, int newCapacity) {
        Objects.requireNonNull(current, "current");
        if (newCapacity <= 0 || newCapacity > GpuParticleSettings.MAXIMUM_CAPACITY) {
            throw new IllegalArgumentException("newCapacity must be in [1, "
                    + GpuParticleSettings.MAXIMUM_CAPACITY + "]: " + newCapacity);
        }
        SortingMode sortingMode = requireMatchingSorting(settings, current);
        if (settings.emissionPerStep() > newCapacity) {
            throw new IllegalArgumentException("emissionPerStep " + settings.emissionPerStep()
                    + " exceeds the replacement capacity " + newCapacity);
        }
        Target candidate = current.resized(newCapacity);
        if (candidate == null) {
            throw new IllegalStateException("GPU particle resize candidate must not be null");
        }
        if (candidate.capacity() != newCapacity) {
            candidate.close();
            throw new IllegalStateException("GPU particle resize candidate capacity "
                    + candidate.capacity() + " does not match requested " + newCapacity);
        }
        SortingMode candidateSorting = candidate.sortingSettings().enabled()
                ? SortingMode.GPU : SortingMode.NONE;
        if (candidateSorting != sortingMode) {
            candidate.close();
            throw new IllegalArgumentException("GPU particle resize changed the sorting "
                    + "generation from " + sortingMode + " to " + candidateSorting);
        }
        bind(candidate);
        return candidate;
    }

    /** @return the currently bound owner, or null before the first record/resize */
    Target boundTarget() {
        return boundTarget;
    }

    private Target boundSystem(GpuParticleSystem system) {
        if (boundTarget instanceof SystemTarget existing && existing.system() == system) {
            return existing;
        }
        return new SystemTarget(system);
    }

    private void bind(Target target) {
        boundTarget = target;
        steps = 0L;
        framesRecorded = 0L;
        statistics = new Statistics(0, 0, 0, target.sortingSettings().enabled()
                ? SortingMode.GPU : SortingMode.NONE, 0L, 0L, state);
    }

    private SortingMode currentSortingMode() {
        if (boundTarget != null) {
            return boundTarget.sortingSettings().enabled() ? SortingMode.GPU : SortingMode.NONE;
        }
        return settings.sortingMode();
    }

    private static SortingMode requireMatchingSorting(Settings settings, Target target) {
        Objects.requireNonNull(settings, "settings");
        SortingMode actual = target.sortingSettings().enabled()
                ? SortingMode.GPU : SortingMode.NONE;
        if (settings.sortingMode() != actual) {
            throw new IllegalArgumentException("configured GPU particle sorting "
                    + settings.sortingMode() + " does not match the owner generation " + actual
                    + "; recreate the GpuParticleSystem with the same GpuParticleSortingSettings");
        }
        return actual;
    }

    private static void requireAcceptedBoundary(Boundary boundary) {
        if (boundary == Boundary.BEFORE_TRANSPARENT_COLLECTORS) {
            return;
        }
        throw new IllegalArgumentException("GPU particles are a standalone depth-tested draw "
                + "and must be recorded before the " + boundary.collectorName
                + " collector; they cannot be part of its selected surface set. Claim "
                + Boundary.BEFORE_TRANSPARENT_COLLECTORS + " when the particle draw precedes "
                + "that collector on the shared colour target");
    }

    private static Statistics withState(Statistics value, State state) {
        return new Statistics(value.submitted(), value.drawn(), value.culled(),
                value.sortingMode(), value.steps(), value.framesRecorded(), state);
    }

    /** Lifecycle state of one GPU-particle consumer. */
    public enum State {
        /** One fixed step is recorded per record and emission is submitted. */
        RUNNING,
        /** Last state is drawn with a zero delta and no emission. */
        PAUSED,
        /** The live set is cleared and nothing is emitted until replay. */
        CANCELLED
    }

    /**
     * Sorting strategy selected by the consumer.
     *
     * <p>{@link GpuParticleSystem} supports the exact no-sort indirect path and
     * the bounded GPU bitonic sort. CPU sorting is intentionally rejected at
     * configuration time because the owner never exposes its active set to the
     * CPU.</p>
     */
    public enum SortingMode {
        /** Exact #27 indirect path with no sort scratch or sort dispatches. */
        NONE,
        /** Bounded GPU bitonic sort before the indirect draw. */
        GPU,
        /** Unsupported by the GPU owner; retained only to fail closed clearly. */
        CPU
    }

    /**
     * Caller claim about transparent collectors sharing the particle colour
     * target.
     */
    public enum Boundary {
        /** Particles are recorded before the OIT/stochastic collectors. */
        BEFORE_TRANSPARENT_COLLECTORS("weighted-OIT/stochastic-transparency"),
        /** Invalid: the particle draw claims membership in the OIT surface set. */
        WEIGHTED_OIT_SURFACE_SET("weighted-OIT"),
        /** Invalid: the particle draw claims membership in the stochastic set. */
        STOCHASTIC_TRANSPARENCY_SURFACE_SET("stochastic-transparency");

        private final String collectorName;

        Boundary(String collectorName) {
            this.collectorName = collectorName;
        }
    }

    /**
     * Bounded consumer configuration.
     *
     * @param fixedTimeStepSeconds exact delta recorded by every running step
     * @param emissionPerStep      particles queued for each running step
     * @param sortingMode          configured owner sorting generation
     */
    public record Settings(float fixedTimeStepSeconds, int emissionPerStep,
                           SortingMode sortingMode) {
        public Settings {
            if (!Float.isFinite(fixedTimeStepSeconds) || fixedTimeStepSeconds <= 0.0f
                    || fixedTimeStepSeconds > MAXIMUM_FIXED_TIME_STEP_SECONDS) {
                throw new IllegalArgumentException("fixedTimeStepSeconds must be finite and in "
                        + "(0, " + MAXIMUM_FIXED_TIME_STEP_SECONDS + "]");
            }
            if (emissionPerStep < 0 || emissionPerStep > MAXIMUM_EMISSION_PER_STEP) {
                throw new IllegalArgumentException("emissionPerStep must be in [0, "
                        + MAXIMUM_EMISSION_PER_STEP + "]");
            }
            Objects.requireNonNull(sortingMode, "sortingMode");
            if (sortingMode == SortingMode.CPU) {
                throw new IllegalArgumentException("CPU GPU-particle sorting is not supported "
                        + "by GpuParticleSystem; configure NONE or GPU to match the owner's "
                        + "GpuParticleSortingSettings");
            }
        }

        /** @return conservative 60 Hz configuration with no emission */
        public static Settings defaults() {
            return new Settings(DEFAULT_FIXED_TIME_STEP_SECONDS, DEFAULT_EMISSION_PER_STEP,
                    SortingMode.NONE);
        }

        public Settings withFixedTimeStepSeconds(float value) {
            return new Settings(value, emissionPerStep, sortingMode);
        }

        public Settings withEmissionPerStep(int value) {
            return new Settings(fixedTimeStepSeconds, value, sortingMode);
        }

        public Settings withSortingMode(SortingMode value) {
            return new Settings(fixedTimeStepSeconds, emissionPerStep, value);
        }
    }

    /**
     * Per-record bounded statistics.
     *
     * @param submitted        configured emission budget for this record
     * @param drawn            budget handed to the recorded update+draw step
     * @param culled           budget withheld while paused or cancelled
     * @param sortingMode      actual owner sorting generation
     * @param steps            fixed steps recorded since construction/replay
     * @param framesRecorded   records since construction/replay
     * @param state            lifecycle state after the record
     */
    public record Statistics(int submitted, int drawn, int culled, SortingMode sortingMode,
                             long steps, long framesRecorded, State state) {
        public Statistics {
            if (submitted < 0 || drawn < 0 || culled < 0 || steps < 0L
                    || framesRecorded < 0L) {
                throw new IllegalArgumentException("particle statistics must be non-negative");
            }
            if (drawn + culled != submitted) {
                throw new IllegalArgumentException("drawn plus culled must equal submitted");
            }
            Objects.requireNonNull(sortingMode, "sortingMode");
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * Package-private recording seam over a real owner or a GL-free fake.
     */
    interface Target {
        int capacity();

        GpuParticleSortingSettings sortingSettings();

        void updateSettings(GpuParticleSettings settings);

        void emit(int count);

        int pendingEmission();

        void reset();

        void record(CommandBuffer commands, float deltaSeconds, Matrix4fc viewProjection,
                    Matrix4fc view, float viewportAspect, int sceneDepthTexture,
                    int sceneWidth, int sceneHeight);

        Target resized(int newCapacity);

        void close();
    }

    /** Adapter over the real GPU owner. */
    private static final class SystemTarget implements Target {
        private final GpuParticleSystem system;

        private SystemTarget(GpuParticleSystem system) {
            this.system = Objects.requireNonNull(system, "system");
        }

        private GpuParticleSystem system() {
            return system;
        }

        @Override
        public int capacity() {
            return system.capacity();
        }

        @Override
        public GpuParticleSortingSettings sortingSettings() {
            return system.sortingSettings();
        }

        @Override
        public void updateSettings(GpuParticleSettings settings) {
            system.updateSettings(settings);
        }

        @Override
        public void emit(int count) {
            system.emit(count);
        }

        @Override
        public int pendingEmission() {
            return system.pendingEmission();
        }

        @Override
        public void reset() {
            system.reset();
        }

        @Override
        public void record(CommandBuffer commands, float deltaSeconds, Matrix4fc viewProjection,
                           Matrix4fc view, float viewportAspect, int sceneDepthTexture,
                           int sceneWidth, int sceneHeight) {
            system.record(commands, deltaSeconds, viewProjection, view, viewportAspect,
                    sceneDepthTexture, sceneWidth, sceneHeight);
        }

        @Override
        public Target resized(int newCapacity) {
            return new SystemTarget(system.resized(newCapacity));
        }

        @Override
        public void close() {
            system.close();
        }
    }
}
