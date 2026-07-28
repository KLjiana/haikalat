package com.kaleblangley.haikalat.subsystems.ui.animation;

import java.util.List;
import java.util.Objects;

/** Parallel animation sequences with an explicit completion condition. */
public record UiAnimationGroup(List<UiAnimationSequence> sequences, Completion completion) {
    public UiAnimationGroup {
        sequences = List.copyOf(Objects.requireNonNull(sequences, "sequences"));
        Objects.requireNonNull(completion, "completion");
        if (sequences.isEmpty()) throw new IllegalArgumentException("group must not be empty");
    }

    public enum Completion { ALL, ANY }
}
