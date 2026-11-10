package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.core.graph.PassProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime timing accumulators share one contract: bounded samples, stable pass
 * names and explicit handling of missing GPU samples.
 *
 * <p>Merged from the former {@code FrameTimingAccumulatorTest} and
 * {@code PassBenchmarkAccumulatorTest}; every assertion is preserved.</p>
 */
class TimingAccumulatorTest {
    @Test
    void frameSamplesReportAverageAndMedianAndSkipUnavailableGpu() {
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

    @Test
    void passSamplesAggregateByStablePassName() {
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
