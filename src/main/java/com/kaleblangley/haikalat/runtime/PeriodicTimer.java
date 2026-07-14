package com.kaleblangley.haikalat.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 为 overlay 和其他低频 runtime 工作提供单调、非阻塞的时间间隔门。 */
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

    /** @return 立即返回；首次调用以及之后每个 interval 最多一次返回 {@code true} */
    public boolean poll() {
        long now = nanoTime.getAsLong();
        if (now < nextNanos) return false;
        nextNanos = now + intervalNanos;
        return true;
    }
}
