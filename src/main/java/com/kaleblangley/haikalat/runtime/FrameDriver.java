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

/** 协调单线程帧提交、present 统计和上传队列的运行时驱动。 */
public final class FrameDriver implements AutoCloseable {
    private final RenderStatistics statistics = new RenderStatistics();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final RenderSettings settings;
    private final GlRenderDevice device;
    private final UploadSystem uploadQueue = new UploadSystem();

    /** @param settings 当前渲染配置 */
    public FrameDriver(RenderSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.device = new GlRenderDevice();
    }

    /** @return 当前渲染配置 */
    public RenderSettings settings() {
        return settings;
    }

    /** @return 当前累计渲染统计 */
    public RenderStatistics statistics() {
        return statistics;
    }

    /** @return 当前渲染设备 */
    public RenderDevice device() {
        return device;
    }

    /** @return 无需向下转型即可读取的 OpenGL 状态缓存累计统计 */
    public StateCache.Statistics stateStatistics() {
        return device.stateStatistics();
    }

    /** 同时重置帧统计和状态缓存命中统计。 */
    public void resetStatistics() {
        statistics.reset();
        device.resetStateStatistics();
    }

    /** 在外部原始 OpenGL 调用修改受管状态后，使设备状态缓存失效。 */
    public void invalidateState() {
        device.invalidateState();
    }

    /** @return 用于异步提交 GPU 数据上传的队列 */
    UploadSystem uploadQueue() {
        return uploadQueue;
    }

    /** @param buffer 立即提交并执行的命令缓冲区 */
    public void submit(CommandBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        device.execute(buffer);
    }

    /**
     * 渲染一帧：开始 CPU submit 计时、执行渲染图、记录 profile，再结束该帧。
     *
     * @param graph 本帧渲染图
     */
    public void frame(RenderGraph graph) {
        beginFrame();
        graph.execute(device);
        statistics.recordGraphProfile(graph.lastFrameProfile());
        endFrame();
    }

    /** 开始 CPU submit 计时，并刷新已经接受的上传请求。 */
    public void beginFrame() {
        statistics.beginFrame();
        uploadQueue.flush();
    }

    /** 结束 CPU submit 计时，并检查当前 OpenGL 错误。 */
    public void endFrame() {
        statistics.endFrame();
        GlDebug.checkError("FrameDriver.endFrame");
    }

    /**
     * 执行平台 present，并仅在 swap 完成后更新 present FPS。
     *
     * @param swapBuffers 平台交换缓冲区操作
     */
    public void present(Runnable swapBuffers) {
        Objects.requireNonNull(swapBuffers, "swapBuffers").run();
        statistics.recordPresent();
    }

    /** 请求停止由该驱动控制的渲染循环。 */
    public void requestStop() {
        running.set(false);
    }

    /** @return 渲染循环是否仍应运行 */
    public boolean isRunning() {
        return running.get();
    }

    /** 停止渲染循环并关闭上传队列。 */
    @Override
    public void close() {
        requestStop();
        uploadQueue.close();
    }
}
