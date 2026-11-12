package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import java.util.Objects;

/**
 * Deterministic fixed-step schedule and injection timeline for one
 * {@link SmokeSimulation}.
 *
 * <p>The timeline owns no GPU resource. {@link #planSteps(double)} converts a
 * frame delta into a whole number of fixed {@code timeStepSeconds} steps with a
 * hard per-frame cap, so a slow frame cannot trigger an unbounded catch-up
 * loop. While paused nothing is accumulated or stepped; resuming does not
 * replay the paused wall time. {@link #ratesForStep(int, long)} evaluates the
 * half-open source windows at the exact step time
 * {@code stepIndex * timeStepSeconds}; the shader receives the resulting rates
 * as uniforms, so the GPU sequence is a pure function of settings and step
 * count.</p>
 *
 * <p>{@link SmokeSimulation} commits a step only after its commands were
 * recorded successfully, keeping {@link #stepCount()} consistent with the
 * recorded sequence.</p>
 */
final class SmokeTimeline {
    private final SmokeSimulationSettings settings;
    private boolean paused;
    private long stepCount;
    private double accumulatorSeconds;

    SmokeTimeline(SmokeSimulationSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    SmokeSimulationSettings settings() {
        return settings;
    }

    long stepCount() {
        return stepCount;
    }

    boolean paused() {
        return paused;
    }

    void setPaused(boolean value) {
        paused = value;
    }

    /**
     * Converts one frame delta into whole fixed steps without committing them.
     * Extra time beyond the per-frame step cap is dropped instead of retained,
     * so a stalled frame cannot create an ever-growing catch-up debt.
     */
    int planSteps(double frameDeltaSeconds) {
        if (!Double.isFinite(frameDeltaSeconds) || frameDeltaSeconds < 0.0) {
            throw new IllegalArgumentException("frame delta must be finite and non-negative");
        }
        if (paused) {
            return 0;
        }
        double step = settings.timeStepSeconds();
        double cap = settings.maximumStepsPerFrame() * step;
        accumulatorSeconds += Math.min(frameDeltaSeconds, SmokeSimulationSettings.MAXIMUM_FRAME_DELTA);
        if (accumulatorSeconds > cap) {
            accumulatorSeconds = cap;
        }
        int steps = (int) Math.floor(accumulatorSeconds / step + 1.0e-9);
        if (steps > settings.maximumStepsPerFrame()) {
            steps = settings.maximumStepsPerFrame();
        }
        accumulatorSeconds -= steps * step;
        if (accumulatorSeconds < 0.0) {
            accumulatorSeconds = 0.0;
        }
        return steps;
    }

    /** Records {@code steps} committed fixed steps in the schedule. */
    void commitSteps(int steps) {
        if (steps < 0) {
            throw new IllegalArgumentException("step count must be non-negative");
        }
        stepCount += steps;
    }

    /** Restarts the deterministic sequence at step zero. */
    void reset() {
        stepCount = 0;
        accumulatorSeconds = 0.0;
    }

    double timeAtStep(long stepIndex) {
        if (stepIndex < 0) {
            throw new IllegalArgumentException("step index must be non-negative");
        }
        return stepIndex * (double) settings.timeStepSeconds();
    }

    /** @return the source's density rate for the given step, or 0 when outside its window */
    float densityRateForStep(int sourceIndex, long stepIndex) {
        if (!activeAtStep(sourceIndex, stepIndex)) {
            return 0.0f;
        }
        return settings.sources().get(sourceIndex).densityRate();
    }

    /** @return the source's temperature rate for the given step, or 0 outside its window */
    float temperatureRateForStep(int sourceIndex, long stepIndex) {
        if (!activeAtStep(sourceIndex, stepIndex)) {
            return 0.0f;
        }
        return settings.sources().get(sourceIndex).temperatureRate();
    }

    private boolean activeAtStep(int sourceIndex, long stepIndex) {
        if (sourceIndex < 0 || sourceIndex >= settings.sources().size()) {
            throw new IllegalArgumentException("source index outside the configured sources");
        }
        return settings.sources().get(sourceIndex).activeAt(timeAtStep(stepIndex));
    }
}
