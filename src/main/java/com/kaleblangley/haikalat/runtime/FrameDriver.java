package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.core.graph.PassProfile;
import com.kaleblangley.haikalat.core.upload.UploadSystem;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsLevel;
import com.kaleblangley.haikalat.runtime.diagnostics.FrameDiagnostics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 协调单线程帧提交、present 统计和上传队列的运行时驱动。 */
public final class FrameDriver implements AutoCloseable {
    private final RenderStatistics statistics = new RenderStatistics();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final RenderSettings settings;
    private final GlRenderDevice device;
    private final UploadSystem uploadQueue = new UploadSystem();
    private final FrameDiagnostics diagnostics;
    private final GlDebug.ResourceTrackingLease resourceTrackingLease;
    private RenderGraph.Description pendingGraphDescription;

    /** @param settings 当前渲染配置 */
    public FrameDriver(RenderSettings settings) {
        this(settings, DiagnosticsLevel.OFF, FrameDiagnostics.DEFAULT_HISTORY_CAPACITY);
    }

    /** 创建带指定诊断层级的帧驱动。 */
    public FrameDriver(RenderSettings settings, DiagnosticsLevel diagnosticsLevel) {
        this(settings, diagnosticsLevel, FrameDiagnostics.DEFAULT_HISTORY_CAPACITY);
    }

    /** 创建带有界诊断历史的帧驱动。 */
    public FrameDriver(RenderSettings settings, DiagnosticsLevel diagnosticsLevel,
                       int diagnosticsHistoryCapacity) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.device = new GlRenderDevice();
        this.diagnostics = new FrameDiagnostics(diagnosticsLevel, diagnosticsHistoryCapacity);
        resourceTrackingLease = diagnosticsLevel == DiagnosticsLevel.DETAILED
                ? GlDebug.acquireResourceTracking() : null;
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

    /** @return 由该 driver 独占写入的运行时诊断 session。 */
    public FrameDiagnostics diagnostics() { return diagnostics; }

    /** 附加下一次发布使用的场景摘要。 */
    public void recordSceneStatistics(long drawCalls, long instanceCount,
                                      long ordinaryRenderers, long instancedRenderers) {
        diagnostics.scene(drawCalls, instanceCount, ordinaryRenderers, instancedRenderers);
    }

    /** 附加下一次发布使用的 UI 摘要；参数均为值类型，runtime 不依赖 UI subsystem。 */
    public void recordUiStatistics(long visibleNodes, long quads, long glyphs, long drawCalls,
                                   long updateNanos, long vertexBytes, long indexBytes,
                                   long atlasUploadBytes) {
        diagnostics.ui(visibleNodes, quads, glyphs, drawCalls, updateNanos,
                vertexBytes, indexBytes, atlasUploadBytes);
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
        try {
            graph.execute(device);
            recordGraph(graph);
            endFrame();
        } catch (RuntimeException | Error failure) {
            try {
                failFrame(graph, failure);
            } catch (RuntimeException | Error diagnosticsFailure) {
                failure.addSuppressed(diagnosticsFailure);
            }
            throw failure;
        }
    }

    /** 记录正式执行后的 graph profile 和只读描述，供帧边界统一发布。 */
    public void recordGraph(RenderGraph graph) {
        Objects.requireNonNull(graph, "graph");
        statistics.recordGraphProfile(graph.lastFrameProfile());
        pendingGraphDescription = diagnostics.level() == DiagnosticsLevel.DETAILED
                ? graph.description() : null;
    }

    /** 开始 CPU submit 计时，并刷新已经接受的上传请求。 */
    public void beginFrame() {
        GlDebug.frameSequence(statistics.frameCount());
        statistics.beginFrame();
        uploadQueue.flush();
    }

    /** 结束 CPU submit 计时，并检查当前 OpenGL 错误。 */
    public void endFrame() {
        statistics.endFrame();
        diagnostics.publish(statistics.snapshot(), statistics.lastFrameProfile(),
                pendingGraphDescription, device.stateStatistics(), true,
                uploadQueue.totalBytesUploaded(), uploadQueue.pendingCount(),
                uploadQueue.totalGpuUpdates());
        pendingGraphDescription = null;
        GlDebug.checkError("FrameDriver.endFrame");
    }

    /**
     * 在调用方捕获正式帧异常后发布一个可诊断的 incomplete frame，并允许下一帧继续。
     */
    public void failFrame(RenderGraph graph, Throwable failure) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(failure, "failure");
        FrameProfile source = graph.lastFrameProfile();
        FrameProfile failed = new FrameProfile(0L, source.passes().stream()
                .map(pass -> new PassProfile(pass.passName(), pass.cpuRecordNanos(), 0L,
                        PassProfile.GpuTimingStatus.FAILED, -1L, 0L,
                        pass.skippedSubmissions())).toList(), source.frameSequence());
        statistics.recordGraphProfile(failed);
        statistics.endFrame();
        RenderGraph.Description description = diagnostics.level() == DiagnosticsLevel.DETAILED
                ? graph.description() : null;
        diagnostics.publish(statistics.snapshot(), statistics.lastFrameProfile(), description,
                device.stateStatistics(), false, uploadQueue.totalBytesUploaded(),
                uploadQueue.pendingCount(), uploadQueue.totalGpuUpdates());
        pendingGraphDescription = null;
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
        Throwable failure = null;
        try {
            uploadQueue.close();
        } catch (RuntimeException | Error closeFailure) {
            failure = closeFailure;
        }
        try {
            diagnostics.close();
        } catch (RuntimeException | Error closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            if (resourceTrackingLease != null) resourceTrackingLease.close();
        } catch (RuntimeException | Error closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
    }
}
