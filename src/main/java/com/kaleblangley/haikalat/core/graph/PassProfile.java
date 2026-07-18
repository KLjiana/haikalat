package com.kaleblangley.haikalat.core.graph;

import java.util.Objects;

public record PassProfile(String passName, long cpuRecordNanos, long gpuNanos,
                          GpuTimingStatus gpuStatus, long sampleFrameSequence,
                          long sampleAgeFrames, long skippedSubmissions) {
    /** 保留旧构造语义：显式提供的 GPU 值视为可用的无身份兼容样本。 */
    public PassProfile(String passName, long cpuRecordNanos, long gpuNanos) {
        this(passName, cpuRecordNanos, gpuNanos, GpuTimingStatus.AVAILABLE,
                -1L, 0L, 0L);
    }

    public PassProfile {
        passName = Objects.requireNonNull(passName, "passName");
        gpuStatus = Objects.requireNonNull(gpuStatus, "gpuStatus");
        if (cpuRecordNanos < 0L || gpuNanos < 0L || sampleAgeFrames < 0L
                || skippedSubmissions < 0L) {
            throw new IllegalArgumentException("profile durations must be non-negative");
        }
        if (gpuStatus != GpuTimingStatus.AVAILABLE && gpuNanos != 0L) {
            throw new IllegalArgumentException("unavailable GPU samples must not expose a duration");
        }
    }

    public double gpuMillis() { return gpuNanos / 1_000_000.0; }
    public double cpuRecordMillis() { return cpuRecordNanos / 1_000_000.0; }

    /** GPU query 的可观测状态。 */
    public enum GpuTimingStatus { PENDING, AVAILABLE, SKIPPED, UNSUPPORTED, FAILED }
}
