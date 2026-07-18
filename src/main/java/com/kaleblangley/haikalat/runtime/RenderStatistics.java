package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.graph.FrameProfile;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class RenderStatistics {
    private static final long PRESENT_SAMPLE_NANOS = 1_000_000_000L;

    private final LongSupplier nanoTime;
    private long frameCount;
    private long presentedFrameCount;
    private long lastFrameStartNanos;
    private long lastFrameDurationNanos;
    private long accumulatedFrameNanos;
    private long lastPresentNanos = -1L;
    private long lastPresentIntervalNanos;
    private long presentSampleStartNanos = -1L;
    private long presentSampleIntervals;
    private double sampledPresentFps;
    private FrameProfile lastFrameProfile = FrameProfile.EMPTY;

    public RenderStatistics() {
        this(System::nanoTime);
    }

    RenderStatistics(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public synchronized void beginFrame() {
        lastFrameStartNanos = nanoTime.getAsLong();
    }

    public synchronized void endFrame() {
        long now = nanoTime.getAsLong();
        lastFrameDurationNanos = now - lastFrameStartNanos;
        accumulatedFrameNanos += lastFrameDurationNanos;
        frameCount++;
        lastFrameProfile = new FrameProfile(lastFrameDurationNanos, lastFrameProfile.passes(),
                lastFrameProfile.frameSequence());
    }

    public synchronized void recordGraphProfile(FrameProfile profile) {
        lastFrameProfile = new FrameProfile(lastFrameDurationNanos, profile.passes(),
                profile.frameSequence());
    }

    /** 记录一次已经完成的 buffer swap/present。 */
    public synchronized void recordPresent() {
        long now = nanoTime.getAsLong();
        presentedFrameCount++;
        if (lastPresentNanos >= 0L) {
            lastPresentIntervalNanos = Math.max(0L, now - lastPresentNanos);
        }
        lastPresentNanos = now;

        if (presentSampleStartNanos < 0L) {
            presentSampleStartNanos = now;
            return;
        }
        presentSampleIntervals++;
        long sampleNanos = now - presentSampleStartNanos;
        if (sampleNanos >= PRESENT_SAMPLE_NANOS) {
            sampledPresentFps = ratePerSecond(presentSampleIntervals, sampleNanos);
            presentSampleStartNanos = now;
            presentSampleIntervals = 0L;
        }
    }

    public synchronized long frameCount() {
        return frameCount;
    }

    public synchronized long presentedFrameCount() {
        return presentedFrameCount;
    }

    public synchronized long lastFrameDurationNanos() {
        return lastFrameDurationNanos;
    }

    public synchronized FrameProfile lastFrameProfile() {
        return lastFrameProfile;
    }

    /** @return 以完成的 buffer swap 计算、可与外部 overlay 对比的 FPS */
    public synchronized double presentFps() {
        if (sampledPresentFps > 0.0) {
            return sampledPresentFps;
        }
        if (presentSampleStartNanos < 0L || presentSampleIntervals == 0L) return 0.0;
        return ratePerSecond(presentSampleIntervals,
                Math.max(0L, lastPresentNanos - presentSampleStartNanos));
    }

    /** @return 兼容别名；现在表示 present FPS，而不是 CPU submit 吞吐率 */
    public synchronized double averageFps() {
        return presentFps();
    }

    public synchronized double lastCpuSubmitMillis() {
        return lastFrameDurationNanos / 1_000_000.0;
    }

    public synchronized double averageCpuSubmitMillis() {
        return frameCount == 0L ? 0.0 : accumulatedFrameNanos / (double) frameCount / 1_000_000.0;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(presentFps(), lastCpuSubmitMillis(), averageCpuSubmitMillis(),
                lastFrameProfile.totalGpuMillis(), frameCount, presentedFrameCount,
                lastPresentIntervalNanos);
    }

    public synchronized void reset() {
        frameCount = 0L;
        presentedFrameCount = 0L;
        lastFrameStartNanos = 0L;
        lastFrameDurationNanos = 0L;
        accumulatedFrameNanos = 0L;
        lastPresentNanos = -1L;
        lastPresentIntervalNanos = 0L;
        presentSampleStartNanos = -1L;
        presentSampleIntervals = 0L;
        sampledPresentFps = 0.0;
        lastFrameProfile = FrameProfile.EMPTY;
    }

    private static double ratePerSecond(long count, long durationNanos) {
        return durationNanos <= 0L ? 0.0 : count * 1_000_000_000.0 / durationNanos;
    }

    public record Snapshot(double presentFps, double cpuSubmitMillis, double averageCpuSubmitMillis,
                           double gpuMillis, long submittedFrames, long presentedFrames,
                           long lastPresentIntervalNanos) {
    }
}
