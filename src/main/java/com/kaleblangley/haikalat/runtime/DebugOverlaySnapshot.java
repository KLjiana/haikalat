package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.AntiAliasingMode;

import java.util.Objects;

public record DebugOverlaySnapshot(
        double fps,
        int drawCalls,
        int instanceCount,
        double gpuMillis,
        AntiAliasingMode activeAntiAliasingMode
) {
    public DebugOverlaySnapshot {
        if (fps < 0.0 || drawCalls < 0 || instanceCount < 0 || gpuMillis < 0.0) {
            throw new IllegalArgumentException("overlay values must be non-negative");
        }
        activeAntiAliasingMode = Objects.requireNonNull(activeAntiAliasingMode, "activeAntiAliasingMode");
    }

    public static DebugOverlaySnapshot from(RenderStatistics statistics, int drawCalls,
                                            int instanceCount, AntiAliasingMode mode) {
        Objects.requireNonNull(statistics, "statistics");
        return new DebugOverlaySnapshot(
                statistics.averageFps(),
                drawCalls,
                instanceCount,
                statistics.lastFrameProfile().totalGpuMillis(),
                mode);
    }
}
