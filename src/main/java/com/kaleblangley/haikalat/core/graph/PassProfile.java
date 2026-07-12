package com.kaleblangley.haikalat.core.graph;

import java.util.Objects;

public record PassProfile(String passName, long cpuRecordNanos, long gpuNanos) {
    public PassProfile {
        passName = Objects.requireNonNull(passName, "passName");
        if (cpuRecordNanos < 0L || gpuNanos < 0L) {
            throw new IllegalArgumentException("profile durations must be non-negative");
        }
    }

    public double gpuMillis() { return gpuNanos / 1_000_000.0; }
    public double cpuRecordMillis() { return cpuRecordNanos / 1_000_000.0; }
}
