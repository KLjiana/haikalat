package com.kaleblangley.haikalat.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameTimingAccumulatorTest {
    @Test
    void reportsAverageAndMedianAndSkipsUnavailableGpuSamples() {
        FrameTimingAccumulator timings = new FrameTimingAccumulator(2);
        timings.add(1_000_000, 0);
        timings.add(3_000_000, 4_000_000);
        timings.add(2_000_000, 2_000_000);

        FrameTimingAccumulator.Summary summary = timings.summary();
        assertEquals(3, summary.cpuSamples());
        assertEquals(2, summary.gpuSamples());
        assertEquals(2.0, summary.averageCpuMillis());
        assertEquals(2.0, summary.medianCpuMillis());
        assertEquals(3.0, summary.averageGpuMillis());
        assertEquals(3.0, summary.medianGpuMillis());

        timings.reset();
        assertTrue(Double.isNaN(timings.summary().averageCpuMillis()));
    }
}
