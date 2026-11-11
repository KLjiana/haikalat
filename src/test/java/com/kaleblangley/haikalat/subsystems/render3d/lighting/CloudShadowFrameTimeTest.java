package com.kaleblangley.haikalat.subsystems.render3d.lighting;

import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowFramePlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudShadowFrameTimeTest {
    @Test
    void advancesAtMostOncePerFrameSequence() {
        CloudShadowFrameTime clock = new CloudShadowFrameTime();

        assertEquals(0.0f, clock.advance(new ShadowFramePlan.FrameTiming(10L, 0.016f)));
        assertEquals(0.0f, clock.advance(new ShadowFramePlan.FrameTiming(10L, 0.016f)),
                "multiple binds in one frame must not advance time twice");
        assertEquals(0.016f, clock.advance(new ShadowFramePlan.FrameTiming(11L, 0.016f)),
                1.0e-6f);
        assertEquals(0.032f, clock.advance(new ShadowFramePlan.FrameTiming(12L, 0.016f)),
                1.0e-6f);
    }

    @Test
    void boundsDeltaAndRebasesWhenSequenceGoesBackwards() {
        CloudShadowFrameTime clock = new CloudShadowFrameTime();
        clock.advance(new ShadowFramePlan.FrameTiming(5L, 0.016f));
        assertEquals(0.1f, clock.advance(new ShadowFramePlan.FrameTiming(6L, 5.0f)),
                1.0e-6f, "a stalled frame may add at most 0.1 seconds");

        assertEquals(0.0f, clock.advance(new ShadowFramePlan.FrameTiming(1L, 0.016f)),
                "a rebased sequence starts a new clock");
        assertEquals(0.016f, clock.advance(new ShadowFramePlan.FrameTiming(2L, 0.016f)),
                1.0e-6f);
    }

    @Test
    void noneTimingKeepsZeroAndZeroDeltaDoesNotAdvance() {
        CloudShadowFrameTime clock = new CloudShadowFrameTime();
        assertEquals(0.0f, clock.advance(ShadowFramePlan.FrameTiming.NONE));
        assertEquals(0.0f, clock.advance(ShadowFramePlan.FrameTiming.NONE));
        assertEquals(0.0f, clock.advance(new ShadowFramePlan.FrameTiming(2L, 0.0f)),
                1.0e-6f);
    }
}
