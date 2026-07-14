package com.kaleblangley.haikalat.runtime;

import java.util.function.LongSupplier;

/**
 * 管理帧基准测试的预热区间、正式采样区间和统计快照。
 *
 * <p>该类不依赖窗口或 OpenGL 查询。调用方在完成 clear、submit 和 present 后调用
 * {@link #recordFrame(FrameDriver, long)}，即可保证空窗口与压力测试使用相同的计时语义。</p>
 */
public final class FrameBenchmarkSession {
    private final int maximumMeasuredFrames;
    private final FrameTimingAccumulator timings;
    private final LongSupplier nanoTime;
    private int warmupRemaining;
    private int measuredFrames;
    private long measurementStart;
    private long measurementEnd;

    /**
     * 创建使用系统单调时钟的基准会话。
     *
     * @param maximumMeasuredFrames 正式采样帧数；负数表示持续运行
     * @param warmupFrames          正式采样前跳过的帧数
     */
    public FrameBenchmarkSession(int maximumMeasuredFrames, int warmupFrames) {
        this(maximumMeasuredFrames, warmupFrames, System::nanoTime);
    }

    FrameBenchmarkSession(int maximumMeasuredFrames, int warmupFrames, LongSupplier nanoTime) {
        if (maximumMeasuredFrames == 0 || maximumMeasuredFrames < -1) {
            throw new IllegalArgumentException("maximumMeasuredFrames must be positive or -1");
        }
        if (warmupFrames < 0) throw new IllegalArgumentException("warmupFrames must be non-negative");
        this.maximumMeasuredFrames = maximumMeasuredFrames;
        this.warmupRemaining = warmupFrames;
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
        this.timings = new FrameTimingAccumulator(
                maximumMeasuredFrames > 0 ? maximumMeasuredFrames : 4096);
        if (warmupFrames == 0) startMeasurement();
    }

    /**
     * 记录一个已经完成 present 的帧。
     *
     * <p>预热结束时会重置 {@link FrameDriver} 的累计统计，且最后一个预热帧不会进入正式样本。</p>
     *
     * @param driver        当前帧驱动
     * @param gpuFrameNanos 异步 GPU 帧耗时；尚无结果时传入 {@code 0}
     */
    public void recordFrame(FrameDriver driver, long gpuFrameNanos) {
        java.util.Objects.requireNonNull(driver, "driver");
        if (warmupRemaining > 0) {
            warmupRemaining--;
            if (warmupRemaining == 0) {
                driver.resetStatistics();
                timings.reset();
                startMeasurement();
            }
            return;
        }
        measuredFrames++;
        timings.add(driver.statistics().lastFrameDurationNanos(), gpuFrameNanos);
        measurementEnd = nanoTime.getAsLong();
    }

    /** @return 正式采样是否达到配置的帧数 */
    public boolean isComplete() {
        return maximumMeasuredFrames > 0 && measuredFrames >= maximumMeasuredFrames;
    }

    /** @return 当前不可变统计快照 */
    public Snapshot snapshot() {
        long duration = measurementEnd - measurementStart;
        double fps = measuredFrames == 0 || duration <= 0L
                ? 0.0 : measuredFrames * 1_000_000_000.0 / duration;
        return new Snapshot(measuredFrames, warmupRemaining, fps, timings.summary());
    }

    private void startMeasurement() {
        measurementStart = nanoTime.getAsLong();
        measurementEnd = measurementStart;
    }

    /**
     * 基准会话在某一时刻的只读统计。
     *
     * @param measuredFrames  已采样帧数
     * @param warmupRemaining 剩余预热帧数
     * @param presentFps      正式区间内包含 present 的帧率
     * @param timings         CPU/GPU 帧时间汇总
     */
    public record Snapshot(int measuredFrames, int warmupRemaining, double presentFps,
                           FrameTimingAccumulator.Summary timings) {
    }
}
