package com.kaleblangley.haikalat.subsystems.animation;

import java.util.Objects;

/** Controller/Layer 输出给上层编排的纯值信号。 */
public record AnimationSignal(long sequence, Type type, Source source, String state,
                              String motion, float normalizedTime, String name,
                              String payload, long loopIndex,
                              AnimationMarker.Priority priority) {
    public AnimationSignal {
        if (sequence < 0L) throw new IllegalArgumentException("sequence must be non-negative");
        type = Objects.requireNonNull(type, "type");
        source = Objects.requireNonNull(source, "source");
        state = state == null ? "" : state;
        motion = motion == null ? "" : motion;
        name = name == null ? "" : name;
        payload = payload == null ? "" : payload;
        if (!Float.isFinite(normalizedTime)) {
            throw new IllegalArgumentException("normalizedTime must be finite");
        }
        priority = Objects.requireNonNull(priority, "priority");
    }

    public enum Type {
        STATE_ENTER,
        STATE_EXIT,
        EVENT,
        MARKER,
        TRANSITION_START,
        TRANSITION_COMPLETE,
        LAYER_COMPLETE
    }

    public record Source(String graph, int layerIndex) {
        public Source {
            graph = Objects.requireNonNull(graph, "graph");
        }
    }
}
