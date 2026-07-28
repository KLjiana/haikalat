package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.subsystems.ui.UiNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable, stable-order sequence of property-track steps. */
public final class UiAnimationSequence {
    private final List<Step> steps;
    private final int repeatCount;
    private final boolean reverseOnRepeat;

    private UiAnimationSequence(List<Step> steps, int repeatCount, boolean reverseOnRepeat) {
        this.steps = List.copyOf(steps);
        this.repeatCount = repeatCount;
        this.reverseOnRepeat = reverseOnRepeat;
    }

    public static Builder builder() { return new Builder(); }
    public List<Step> steps() { return steps; }
    public int repeatCount() { return repeatCount; }
    public boolean reverseOnRepeat() { return reverseOnRepeat; }

    public UiNode firstTarget() {
        for (Step step : steps) if (step.target != null) return step.target;
        throw new IllegalStateException("sequence has no target");
    }

    public float durationSeconds() {
        float duration = 0.0f;
        for (Step step : steps) duration += step.durationSeconds;
        return duration * repeatCount;
    }

    public record Step(UiNode target, List<UiPropertyTrack> tracks,
                       float durationSeconds, UiEasing easing,
                       List<UiAnimationTrigger> triggers) {
        public Step {
            tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
            triggers = List.copyOf(Objects.requireNonNull(triggers, "triggers"));
            Objects.requireNonNull(easing, "easing");
            if (!Float.isFinite(durationSeconds) || durationSeconds < 0.0f) {
                throw new IllegalArgumentException("durationSeconds must be finite and non-negative");
            }
            if (target == null && !tracks.isEmpty()) {
                throw new IllegalArgumentException("a hold step cannot contain tracks");
            }
            if (target != null && tracks.isEmpty() && durationSeconds == 0.0f) {
                throw new IllegalArgumentException("empty target step must have positive duration");
            }
        }
    }

    public static final class Builder {
        private final List<Step> steps = new ArrayList<>();
        private int repeatCount = 1;
        private boolean reverseOnRepeat;

        public Builder then(UiNode target, float durationSeconds, UiEasing easing,
                            UiPropertyTrack... tracks) {
            return then(target, durationSeconds, easing, List.of(), tracks);
        }

        public Builder then(UiNode target, float durationSeconds, UiEasing easing,
                            List<UiAnimationTrigger> triggers,
                            UiPropertyTrack... tracks) {
            steps.add(new Step(Objects.requireNonNull(target, "target"),
                    Arrays.asList(Objects.requireNonNull(tracks, "tracks")),
                    durationSeconds, easing, triggers));
            return this;
        }

        public Builder hold(float durationSeconds) {
            if (durationSeconds <= 0.0f) {
                throw new IllegalArgumentException("hold duration must be positive");
            }
            steps.add(new Step(null, List.of(), durationSeconds, UiEasing.LINEAR, List.of()));
            return this;
        }

        public Builder repeat(int count, boolean reverse) {
            if (count <= 0) throw new IllegalArgumentException("repeat count must be positive");
            repeatCount = count;
            reverseOnRepeat = reverse;
            return this;
        }

        public UiAnimationSequence build() {
            if (steps.isEmpty()) throw new IllegalStateException("sequence must contain a step");
            UiAnimationSequence sequence =
                    new UiAnimationSequence(steps, repeatCount, reverseOnRepeat);
            sequence.firstTarget();
            return sequence;
        }
    }
}
