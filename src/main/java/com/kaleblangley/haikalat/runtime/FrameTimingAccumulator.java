package com.kaleblangley.haikalat.runtime;

import java.util.Arrays;

/** Collects measured CPU/GPU frame times and reports average and median without GL coupling. */
public final class FrameTimingAccumulator {
    private long[] cpuNanos;
    private long[] gpuNanos;
    private int cpuCount;
    private int gpuCount;

    public FrameTimingAccumulator(int expectedFrames) {
        if (expectedFrames < 0) throw new IllegalArgumentException("expectedFrames must be non-negative");
        int capacity = Math.max(16, expectedFrames);
        cpuNanos = new long[capacity];
        gpuNanos = new long[capacity];
    }

    public void add(long cpuSubmitNanos, long gpuFrameNanos) {
        if (cpuSubmitNanos < 0L || gpuFrameNanos < 0L) {
            throw new IllegalArgumentException("frame timings must be non-negative");
        }
        cpuNanos = append(cpuNanos, cpuCount, cpuSubmitNanos);
        cpuCount++;
        // Zero means that the asynchronous timer has not produced its first result yet.
        if (gpuFrameNanos > 0L) {
            gpuNanos = append(gpuNanos, gpuCount, gpuFrameNanos);
            gpuCount++;
        }
    }

    public int frameCount() {
        return cpuCount;
    }

    public Summary summary() {
        return new Summary(cpuCount, gpuCount,
                averageMillis(cpuNanos, cpuCount), medianMillis(cpuNanos, cpuCount),
                averageMillis(gpuNanos, gpuCount), medianMillis(gpuNanos, gpuCount));
    }

    public void reset() {
        cpuCount = 0;
        gpuCount = 0;
    }

    private static long[] append(long[] values, int size, long value) {
        if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
        values[size] = value;
        return values;
    }

    private static double averageMillis(long[] values, int count) {
        if (count == 0) return Double.NaN;
        double total = 0.0;
        for (int i = 0; i < count; i++) total += values[i];
        return total / count / 1_000_000.0;
    }

    private static double medianMillis(long[] values, int count) {
        if (count == 0) return Double.NaN;
        long[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        int middle = count >>> 1;
        double median = (count & 1) == 0
                ? (sorted[middle - 1] + (double) sorted[middle]) * 0.5
                : sorted[middle];
        return median / 1_000_000.0;
    }

    public record Summary(int cpuSamples, int gpuSamples,
                          double averageCpuMillis, double medianCpuMillis,
                          double averageGpuMillis, double medianGpuMillis) {
    }
}
