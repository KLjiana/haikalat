package com.kaleblangley.haikalat.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 用于逐帧更新的单调 runtime 时钟。
 *
 * <p>每次 tick 返回有限制的 delta，并以该值推进模拟时间，避免 debugger 暂停或窗口挂起后
 * 出现一次过大的移动或动画步长。</p>
 */
public final class FrameClock {
    public static final Duration DEFAULT_MAX_DELTA = Duration.ofMillis(100);

    private final LongSupplier nanoTime;
    private final double maxDeltaSeconds;
    private long previousNanos;
    private long tickIndex;
    private double elapsedSeconds;

    public FrameClock() {
        this(System::nanoTime, DEFAULT_MAX_DELTA);
    }

    public FrameClock(Duration maxDelta) {
        this(System::nanoTime, maxDelta);
    }

    FrameClock(LongSupplier nanoTime, Duration maxDelta) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        Objects.requireNonNull(maxDelta, "maxDelta");
        if (maxDelta.isNegative() || maxDelta.isZero()) {
            throw new IllegalArgumentException("maxDelta must be positive");
        }
        maxDeltaSeconds = maxDelta.toNanos() / 1_000_000_000.0;
        previousNanos = nanoTime.getAsLong();
    }

    public Tick tick() {
        long now = nanoTime.getAsLong();
        long elapsedNanos = Math.max(0L, now - previousNanos);
        previousNanos = now;
        double deltaSeconds = Math.min(elapsedNanos / 1_000_000_000.0, maxDeltaSeconds);
        elapsedSeconds += deltaSeconds;
        return new Tick(tickIndex++, (float) deltaSeconds, elapsedSeconds);
    }

    public record Tick(long index, float deltaSeconds, double elapsedSeconds) {
    }
}
