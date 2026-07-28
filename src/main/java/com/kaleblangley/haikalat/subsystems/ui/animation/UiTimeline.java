package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic sequence/group timeline. It is owner-thread confined and emits immutable
 * signals instead of directly coupling animation to effects.
 */
public final class UiTimeline implements AutoCloseable {
    private static final float UPDATE_SLICE_SECONDS = 0.05f;

    private final UiDocument document;
    private final Thread owner = Thread.currentThread();
    private final UiAnimationRunList<GroupRun> active = new UiAnimationRunList<>();
    private final List<UiAnimationSignal> signals = new ArrayList<>();
    private long nextSequence;
    private boolean reducedMotion;
    private boolean closed;

    public UiTimeline(UiDocument document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    public UiTimeline reducedMotion(boolean value) {
        check();
        reducedMotion = value;
        return this;
    }

    public boolean reducedMotion() {
        check();
        return reducedMotion;
    }

    public long play(UiAnimationSequence sequence) {
        return play(new UiAnimationGroup(List.of(Objects.requireNonNull(sequence, "sequence")),
                UiAnimationGroup.Completion.ALL));
    }

    public long play(UiAnimationGroup group) {
        return play(group, List.of());
    }

    /** Makes a node visible and starts its enter sequence. */
    public long enter(UiNode node, UiAnimationSequence sequence) {
        check();
        validateTarget(Objects.requireNonNull(node, "node"));
        node.hitTestVisible(true);
        return play(Objects.requireNonNull(sequence, "sequence"));
    }

    /**
     * Removes a node from hit testing immediately, keeps it painted during the sequence,
     * then closes it exactly once after completion.
     */
    public long exit(UiNode node, UiAnimationSequence sequence) {
        check();
        validateTarget(Objects.requireNonNull(node, "node"));
        node.hitTestVisible(false);
        return play(new UiAnimationGroup(List.of(Objects.requireNonNull(sequence, "sequence")),
                UiAnimationGroup.Completion.ALL), List.of(node));
    }

    private long play(UiAnimationGroup group, List<UiNode> closeOnComplete) {
        check();
        ensureOpen();
        long sequence = ++nextSequence;
        List<SequenceRun> runs = new ArrayList<>(group.sequences().size());
        for (UiAnimationSequence item : group.sequences()) {
            validateTarget(item.firstTarget());
            runs.add(new SequenceRun(sequence, item));
        }
        GroupRun run = new GroupRun(sequence, group.completion(), runs, closeOnComplete);
        active.add(run);
        for (SequenceRun item : runs) item.emitStart();
        if (reducedMotion) update(0.0f);
        return sequence;
    }

    public void cancel(long sequence) {
        check();
        if (closed) return;
        for (int index = 0; index < active.size(); index++) {
            GroupRun run = active.get(index);
            if (run.sequence == sequence) {
                run.cancel();
                active.removeAt(index);
                return;
            }
        }
    }

    public void update(float deltaSeconds) {
        check();
        ensureOpen();
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        float remaining = reducedMotion ? Float.MAX_VALUE : deltaSeconds;
        do {
            float slice = reducedMotion ? Float.MAX_VALUE
                    : Math.min(remaining, UPDATE_SLICE_SECONDS);
            for (int index = 0; index < active.size();) {
                GroupRun group = active.get(index);
                if (group.advance(slice)) active.removeAt(index);
                else index++;
            }
            if (reducedMotion) break;
            remaining -= slice;
        } while (remaining > 0.0f);
    }

    public List<UiAnimationSignal> drainSignals() {
        check();
        ensureOpen();
        if (signals.isEmpty()) return List.of();
        List<UiAnimationSignal> drained = List.copyOf(signals);
        signals.clear();
        return drained;
    }

    public int activeCount() {
        check();
        ensureOpen();
        return active.size();
    }

    @Override
    public void close() {
        check();
        if (closed) return;
        for (int index = 0; index < active.size(); index++) {
            active.get(index).cancel();
        }
        active.clear();
        closed = true;
    }

    private void validateTarget(UiNode node) {
        if (node.isClosed() || node.document() != document) {
            throw new IllegalArgumentException("timeline target must be an open document member");
        }
    }

    private void check() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("UI timeline must be accessed from its owner thread");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UI timeline is closed");
    }

    private final class GroupRun {
        private final long sequence;
        private final UiAnimationGroup.Completion completion;
        private final List<SequenceRun> runs;
        private final List<UiNode> closeOnComplete;

        private GroupRun(long sequence, UiAnimationGroup.Completion completion,
                         List<SequenceRun> runs, List<UiNode> closeOnComplete) {
            this.sequence = sequence;
            this.completion = completion;
            this.runs = runs;
            this.closeOnComplete = List.copyOf(closeOnComplete);
        }

        private boolean advance(float delta) {
            boolean any = false;
            boolean all = true;
            for (SequenceRun run : runs) {
                if (!run.finished) run.advance(delta);
                any |= run.finished;
                all &= run.finished;
            }
            boolean complete = completion == UiAnimationGroup.Completion.ALL ? all : any;
            if (complete && completion == UiAnimationGroup.Completion.ANY) {
                for (SequenceRun run : runs) if (!run.finished) run.cancel();
            }
            if (complete) {
                for (UiNode node : closeOnComplete) {
                    if (!node.isClosed()) node.close();
                }
            }
            return complete;
        }

        private void cancel() {
            for (SequenceRun run : runs) if (!run.finished) run.cancel();
        }
    }

    private final class SequenceRun {
        private final long sequence;
        private final UiAnimationSequence definition;
        private final boolean[][] fired;
        private int iteration;
        private int stepIndex;
        private float elapsed;
        private boolean finished;

        private SequenceRun(long sequence, UiAnimationSequence definition) {
            this.sequence = sequence;
            this.definition = definition;
            fired = new boolean[definition.steps().size()][];
            for (int index = 0; index < fired.length; index++) {
                fired[index] = new boolean[definition.steps().get(index).triggers().size()];
            }
        }

        private void emitStart() {
            emit(UiAnimationSignal.Type.START, 0.0f, "");
        }

        private void advance(float delta) {
            float remaining = delta;
            while (!finished) {
                UiAnimationSequence.Step step = currentStep();
                if (step.target() != null && step.target().isClosed()) {
                    cancel();
                    return;
                }
                float duration = reducedMotion ? 0.0f : step.durationSeconds();
                float before = duration == 0.0f ? 0.0f : elapsed / duration;
                float consumed = duration == 0.0f ? 0.0f : Math.min(remaining, duration - elapsed);
                elapsed += consumed;
                remaining -= consumed;
                float raw = duration == 0.0f ? 1.0f : Math.min(1.0f, elapsed / duration);
                float directed = reverseIteration() ? 1.0f - raw : raw;
                float eased = raw >= 1.0f ? directed
                        : step.easing().apply(directed);
                if (step.target() != null) {
                    for (UiPropertyTrack track : step.tracks()) track.apply(step.target(), eased);
                }
                emitMarkers(step, before, raw);
                if (raw < 1.0f) return;
                elapsed = 0.0f;
                if (!nextStep()) {
                    finished = true;
                    emit(UiAnimationSignal.Type.COMPLETE, 1.0f, "");
                    return;
                }
                if (remaining <= 0.0f && !reducedMotion) return;
            }
        }

        private boolean nextStep() {
            stepIndex++;
            if (stepIndex < definition.steps().size()) return true;
            iteration++;
            if (iteration >= definition.repeatCount()) return false;
            stepIndex = 0;
            for (boolean[] markerSet : fired) java.util.Arrays.fill(markerSet, false);
            return true;
        }

        private UiAnimationSequence.Step currentStep() {
            int index = reverseIteration()
                    ? definition.steps().size() - 1 - stepIndex : stepIndex;
            return definition.steps().get(index);
        }

        private boolean reverseIteration() {
            return definition.reverseOnRepeat() && (iteration & 1) != 0;
        }

        private void emitMarkers(UiAnimationSequence.Step step, float before, float after) {
            int actualIndex = reverseIteration()
                    ? definition.steps().size() - 1 - stepIndex : stepIndex;
            List<UiAnimationTrigger> triggers = step.triggers();
            for (int index = 0; index < triggers.size(); index++) {
                UiAnimationTrigger trigger = triggers.get(index);
                if (!fired[actualIndex][index] && trigger.normalizedTime() > before
                        && trigger.normalizedTime() <= after) {
                    fired[actualIndex][index] = true;
                    emit(UiAnimationSignal.Type.MARKER,
                            trigger.normalizedTime(), trigger.payload());
                }
            }
        }

        private void cancel() {
            if (finished) return;
            finished = true;
            emit(UiAnimationSignal.Type.CANCEL, normalizedProgress(), "");
        }

        private float normalizedProgress() {
            float total = definition.durationSeconds();
            if (total <= 0.0f) return finished ? 1.0f : 0.0f;
            float prior = iteration * (total / definition.repeatCount());
            for (int index = 0; index < stepIndex; index++) {
                prior += definition.steps().get(index).durationSeconds();
            }
            return Math.max(0.0f, Math.min(1.0f,
                    (prior + elapsed) / total));
        }

        private void emit(UiAnimationSignal.Type type, float time, String payload) {
            signals.add(new UiAnimationSignal(definition.firstTarget().id(), sequence,
                    type, time, payload));
        }
    }
}
