package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.upload.UploadSystem;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FrameDriver {
    private final RenderStatistics statistics = new RenderStatistics();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final RenderSettings settings;
    private final RenderDevice device;
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

    /** @return 上传队列，用于异步提交 GPU 数据上传 */
    public UploadSystem uploadQueue() {
        return uploadQueue;
    }

    /**
     * 提交并立即执行一个命令缓冲区。
     *
     * @param buffer 命令缓冲区
     */
    public void submit(CommandBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        executeCommandBuffer(buffer);
    }

    /**
     * 执行一个命令缓冲区通过渲染设备。
     *
     * @param buffer 命令缓冲区
     */
    public void executeCommandBuffer(CommandBuffer buffer) {
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

    /** 新帧开始，先执行待处理上传，再开始统计。 */
    public void beginFrame() {
        uploadQueue.flush();
        statistics.beginFrame();
    }

    /** 帧结束，记录统计信息，可选检查 GL 错误。 */
    public void endFrame() {
        statistics.endFrame();
        if (settings.debugErrors()) {
            GlDebug.checkError("FrameDriver.endFrame");
        }
    }

    /** 请求停止渲染循环。 */
    public void requestStop() {
        running.set(false);
    }

    /** @return 渲染循环是否仍在运行 */
    public boolean isRunning() {
        return running.get();
    }
}
