package com.kaleblangley.haikalat.subsystems.animation;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded animation signal queue with priority-aware eviction.
 *
 * <p>Signal sequence allocation and overflow policy are kept out of the graph
 * evaluator. The queue uses an explicit scan for normal-signal eviction so the
 * hot path does not create a stream pipeline.</p>
 */
final class AnimationSignalQueue {
    private final ArrayDeque<AnimationSignal> values;
    private final int capacity;
    private long nextSequence;
    private long emitted;
    private long dropped;

    AnimationSignalQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("signal capacity must be positive");
        this.capacity = capacity;
        values = new ArrayDeque<>(capacity);
    }

    long nextSequence() {
        return nextSequence++;
    }

    boolean offer(AnimationSignal signal) {
        Objects.requireNonNull(signal, "signal");
        if (values.size() >= capacity) {
            if (signal.priority() == AnimationMarker.Priority.HIGH) {
                AnimationSignal removable = null;
                for (AnimationSignal existing : values) {
                    if (existing.priority() == AnimationMarker.Priority.NORMAL) {
                        removable = existing;
                        break;
                    }
                }
                if (removable != null) {
                    values.remove(removable);
                } else {
                    dropped++;
                    return false;
                }
            } else {
                dropped++;
                return false;
            }
        }
        values.addLast(signal);
        emitted++;
        return true;
    }

    List<AnimationSignal> pending() {
        return List.copyOf(values);
    }

    List<AnimationSignal> drain() {
        List<AnimationSignal> result = List.copyOf(values);
        values.clear();
        return result;
    }

    void clear() {
        values.clear();
    }

    long emittedCount() {
        return emitted;
    }

    long droppedCount() {
        return dropped;
    }
}
