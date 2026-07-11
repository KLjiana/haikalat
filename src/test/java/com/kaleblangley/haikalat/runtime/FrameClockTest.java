package com.kaleblangley.haikalat.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameClockTest {
    @Test
    void reportsMonotonicDeltaAndAccumulatedTime() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        FrameClock clock = new FrameClock(nanos::get, Duration.ofMillis(100));

        nanos.addAndGet(16_000_000L);
        FrameClock.Tick first = clock.tick();
        nanos.addAndGet(20_000_000L);
        FrameClock.Tick second = clock.tick();

        assertEquals(0L, first.index());
        assertEquals(0.016f, first.deltaSeconds(), 0.000_001f);
        assertEquals(0.016, first.elapsedSeconds(), 0.000_001);
        assertEquals(1L, second.index());
        assertEquals(0.020f, second.deltaSeconds(), 0.000_001f);
        assertEquals(0.036, second.elapsedSeconds(), 0.000_001);
    }

    @Test
    void capsLongFramesAndIgnoresBackwardClockMovement() {
        AtomicLong nanos = new AtomicLong(2_000_000_000L);
        FrameClock clock = new FrameClock(nanos::get, Duration.ofMillis(100));

        nanos.addAndGet(500_000_000L);
        assertEquals(0.1f, clock.tick().deltaSeconds(), 0.000_001f);
        nanos.addAndGet(-1_000_000L);
        assertEquals(0.0f, clock.tick().deltaSeconds(), 0.000_001f);
    }

    @Test
    void rejectsNonPositiveMaximumDelta() {
        assertThrows(IllegalArgumentException.class,
                () -> new FrameClock(() -> 0L, Duration.ZERO));
    }
}
