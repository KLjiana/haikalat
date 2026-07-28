package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.subsystems.ui.UiNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Stable child-order stagger construction helper. */
public final class UiStagger {
    private UiStagger() {
    }

    public static UiAnimationGroup group(List<? extends UiNode> nodes, float delaySeconds,
                                         float durationSeconds, UiEasing easing,
                                         Function<UiNode, UiPropertyTrack> trackFactory) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(easing, "easing");
        Objects.requireNonNull(trackFactory, "trackFactory");
        if (!Float.isFinite(delaySeconds) || delaySeconds < 0.0f) {
            throw new IllegalArgumentException("delaySeconds must be finite and non-negative");
        }
        List<UiAnimationSequence> sequences = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            UiNode node = Objects.requireNonNull(nodes.get(index), "node");
            UiAnimationSequence.Builder builder = UiAnimationSequence.builder();
            if (index != 0 && delaySeconds != 0.0f) builder.hold(index * delaySeconds);
            builder.then(node, durationSeconds, easing,
                    Objects.requireNonNull(trackFactory.apply(node), "track"));
            sequences.add(builder.build());
        }
        return new UiAnimationGroup(sequences, UiAnimationGroup.Completion.ALL);
    }
}
