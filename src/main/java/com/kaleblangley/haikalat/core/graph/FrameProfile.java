package com.kaleblangley.haikalat.core.graph;

import java.util.List;
import java.util.Objects;

public record FrameProfile(long cpuFrameNanos, List<PassProfile> passes, long frameSequence) {
    public static final FrameProfile EMPTY = new FrameProfile(0L, List.of(), -1L);

    public FrameProfile(long cpuFrameNanos, List<PassProfile> passes) {
        this(cpuFrameNanos, passes, -1L);
    }

    public FrameProfile {
        if (cpuFrameNanos < 0L) throw new IllegalArgumentException("cpuFrameNanos must be non-negative");
        passes = List.copyOf(Objects.requireNonNull(passes, "passes"));
    }

    public long totalGpuNanos() {
        long total = 0L;
        for (PassProfile pass : passes) total += pass.gpuNanos();
        return total;
    }

    /** @return 所有 pass 都具有可用 GPU 样本时返回 {@code true}。 */
    public boolean gpuTotalComplete() {
        return !passes.isEmpty() && passes.stream()
                .allMatch(pass -> pass.gpuStatus() == PassProfile.GpuTimingStatus.AVAILABLE);
    }

    public double cpuFrameMillis() { return cpuFrameNanos / 1_000_000.0; }
    public double totalGpuMillis() { return totalGpuNanos() / 1_000_000.0; }
}
