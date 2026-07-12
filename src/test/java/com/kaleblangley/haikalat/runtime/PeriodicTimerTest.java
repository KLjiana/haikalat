package com.kaleblangley.haikalat.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeriodicTimerTest {
    @Test
    void firstPollFiresAndThenUsesElapsedTimeRatherThanFrameCount() {
        AtomicLong nanos = new AtomicLong();
        PeriodicTimer timer = new PeriodicTimer(nanos::get, Duration.ofMillis(250));

        assertTrue(timer.poll());
        assertFalse(timer.poll());
        nanos.addAndGet(249_000_000L);
        assertFalse(timer.poll());
        nanos.addAndGet(1_000_000L);
        assertTrue(timer.poll());
    }

    @Test
    void rejectsNonPositiveIntervals() {
        assertThrows(IllegalArgumentException.class,
                () -> new PeriodicTimer(() -> 0L, Duration.ZERO));
    }
}
