package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.core.graph.PassProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PassBenchmarkAccumulatorTest {
    @Test
    void aggregatesCpuAndGpuSamplesByStablePassName() {
        PassBenchmarkAccumulator accumulator = new PassBenchmarkAccumulator(2);
        accumulator.add(new FrameProfile(0L, List.of(
                new PassProfile("BloomExtract", 100_000L, 200_000L),
                new PassProfile("ToneMapping", 300_000L, 400_000L))));
        accumulator.add(new FrameProfile(0L, List.of(
                new PassProfile("BloomExtract", 300_000L, 400_000L),
                new PassProfile("ToneMapping", 500_000L, 600_000L))));

        var result = accumulator.snapshot();
        assertEquals(List.of("BloomExtract", "ToneMapping"), List.copyOf(result.keySet()));
        assertEquals(0.2, result.get("BloomExtract").averageCpuMillis(), 1.0e-9);
        assertEquals(0.3, result.get("BloomExtract").averageGpuMillis(), 1.0e-9);
    }
}
