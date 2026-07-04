package com.kaleblangley.haikalat.runtime;

public final class RenderStatistics {
    private long frameCount;
    private long lastFrameStartNanos;
    private long lastFrameDurationNanos;
    private long accumulatedFrameNanos;

    public void beginFrame() {
        lastFrameStartNanos = System.nanoTime();
    }

    public void endFrame() {
        long now = System.nanoTime();
        lastFrameDurationNanos = now - lastFrameStartNanos;
        accumulatedFrameNanos += lastFrameDurationNanos;
        frameCount++;
    }

    public long frameCount() {
        return frameCount;
    }

    public long lastFrameDurationNanos() {
        return lastFrameDurationNanos;
    }

    public double averageFps() {
        if (frameCount == 0L || accumulatedFrameNanos <= 0L) {
            return 0.0;
        }
        double averageFrameSeconds = (accumulatedFrameNanos / (double) frameCount) / 1_000_000_000.0;
        return averageFrameSeconds == 0.0 ? 0.0 : 1.0 / averageFrameSeconds;
    }

    public void reset() {
        frameCount = 0L;
        lastFrameStartNanos = 0L;
        lastFrameDurationNanos = 0L;
        accumulatedFrameNanos = 0L;
    }
}
