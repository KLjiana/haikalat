package com.kaleblangley.haikalat.core.graph;

import java.util.List;
import java.util.Objects;

public record FrameProfile(long cpuFrameNanos, List<PassProfile> passes) {
    public static final FrameProfile EMPTY = new FrameProfile(0L, List.of());

    public FrameProfile {
        if (cpuFrameNanos < 0L) throw new IllegalArgumentException("cpuFrameNanos must be non-negative");
        passes = List.copyOf(Objects.requireNonNull(passes, "passes"));
    }

    public long totalGpuNanos() {
        long total = 0L;
        for (PassProfile pass : passes) total += pass.gpuNanos();
        return total;
    }

    public double cpuFrameMillis() { return cpuFrameNanos / 1_000_000.0; }
    public double totalGpuMillis() { return totalGpuNanos() / 1_000_000.0; }
}
