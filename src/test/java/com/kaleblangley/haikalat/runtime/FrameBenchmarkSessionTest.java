package com.kaleblangley.haikalat.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证基准会话的预热边界、正式帧计数和 present FPS 计算。 */
class FrameBenchmarkSessionTest {
    @Test
    void warmupFramesAreExcludedFromMeasurement() {
        AtomicLong clock = new AtomicLong();
        FrameBenchmarkSession session = new FrameBenchmarkSession(2, 1, clock::get);
        try (FrameDriver driver = new FrameDriver(RenderSettings.builder().build())) {
            session.recordFrame(driver, 10L);
            assertEquals(0, session.snapshot().measuredFrames());

            clock.addAndGet(20_000_000L);
            session.recordFrame(driver, 11L);
            clock.addAndGet(20_000_000L);
            session.recordFrame(driver, 12L);

            assertTrue(session.isComplete());
            assertEquals(2, session.snapshot().measuredFrames());
            assertEquals(50.0, session.snapshot().presentFps(), 0.001);
        }
    }

    @Test
    void unlimitedSessionNeverCompletes() {
        FrameBenchmarkSession session = new FrameBenchmarkSession(-1, 0, () -> 1L);
        assertFalse(session.isComplete());
    }
}
