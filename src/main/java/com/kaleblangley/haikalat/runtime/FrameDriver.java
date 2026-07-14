package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.upload.UploadSystem;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FrameDriver implements AutoCloseable {
    private final RenderStatistics statistics = new RenderStatistics();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final RenderSettings settings;
    private final GlRenderDevice device;
    private final UploadSystem uploadQueue = new UploadSystem();

    public FrameDriver(RenderSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.device = new GlRenderDevice();
    }

    /** @return 渲染配置 */
    public RenderSettings settings() {
        return settings;
    }

    /** @return 渲染统计数据 */
    public RenderStatistics statistics() {
        return statistics;
    }

    /** @return 渲染设备 */
    public RenderDevice device() {
        return device;
    }

    /** Returns lifetime OpenGL state-cache counters without requiring a backend cast. */
    public StateCache.Statistics stateStatistics() {
        return device.stateStatistics();
    }

    public void resetStatistics() {
        statistics.reset();
        device.resetStateStatistics();
    }

    /** Call after external raw GL code changes state managed by the command system. */
    public void invalidateState() {
        device.invalidateState();
    }

    /** @return 上传队列，用于异步提交 GPU 数据上传 */
    UploadSystem uploadQueue() {
        return uploadQueue;
    }

    /**
     * 提交并立即执行一个命令缓冲区。
     *
     * @param buffer 命令缓冲区
     */
    public void submit(CommandBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        device.execute(buffer);
    }

    /**
     * 渲染一帧：先开始帧、执行渲染图、再结束帧。
     *
     * @param graph 渲染图
     */
    public void frame(RenderGraph graph) {
        beginFrame();
        graph.execute(device);
        statistics.recordGraphProfile(graph.lastFrameProfile());
        endFrame();
    }

    /** Starts CPU submission timing before flushing accepted uploads. */
    public void beginFrame() {
        statistics.beginFrame();
        uploadQueue.flush();
    }

    /** 帧结束，记录统计信息，可选检查 GL 错误。 */
    public void endFrame() {
        statistics.endFrame();
        GlDebug.checkError("FrameDriver.endFrame");
    }

    /** Executes the platform swap and records FPS only after the present call completes. */
    public void present(Runnable swapBuffers) {
        Objects.requireNonNull(swapBuffers, "swapBuffers").run();
        statistics.recordPresent();
    }

    /** 请求停止渲染循环。 */
    public void requestStop() {
        running.set(false);
    }

    /** @return 渲染循环是否仍在运行 */
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        requestStop();
        uploadQueue.close();
    }
}
