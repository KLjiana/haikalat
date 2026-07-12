package com.kaleblangley.haikalat.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Monotonic, non-blocking interval gate for overlays and other low-frequency runtime work. */
public final class PeriodicTimer {
    private final LongSupplier nanoTime;
    private final long intervalNanos;
    private long nextNanos;

    public PeriodicTimer(Duration interval) {
        this(System::nanoTime, interval);
    }

    PeriodicTimer(LongSupplier nanoTime, Duration interval) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        intervalNanos = interval.toNanos();
        nextNanos = nanoTime.getAsLong();
    }

    /** Returns immediately; the first call and then at most one call per interval return true. */
    public boolean poll() {
        long now = nanoTime.getAsLong();
        if (now < nextNanos) return false;
        nextNanos = now + intervalNanos;
        return true;
    }
}
