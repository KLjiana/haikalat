package com.kaleblangley.haikalat.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking latest-value handoff for a producer thread and a render thread.
 *
 * <p>Publishing replaces any frame the consumer has not observed yet. Values must be immutable
 * snapshots: the mailbox provides atomic publication and visibility, but intentionally does not
 * lock or copy application objects.</p>
 */
public final class LatestFrameMailbox<T> {
    private final AtomicReference<Snapshot<T>> latest;

    public LatestFrameMailbox(T initialValue) {
        latest = new AtomicReference<>(new Snapshot<>(0L,
                Objects.requireNonNull(initialValue, "initialValue")));
    }

    public long publish(T value) {
        T publishedValue = Objects.requireNonNull(value, "value");
        Snapshot<T> published = latest.updateAndGet(previous ->
                new Snapshot<>(previous.sequence() + 1L, publishedValue));
        return published.sequence();
    }

    public Snapshot<T> latest() {
        return latest.get();
    }

    public record Snapshot<T>(long sequence, T value) {
        public Snapshot {
            if (sequence < 0L) {
                throw new IllegalArgumentException("sequence must be non-negative");
            }
            Objects.requireNonNull(value, "value");
        }
    }
}
