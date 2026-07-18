package com.kaleblangley.haikalat.runtime.diagnostics;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderStatistics;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** FrameDriver 独占写入的有界诊断历史。 */
public final class FrameDiagnostics implements AutoCloseable {
    public static final int DEFAULT_HISTORY_CAPACITY = 240;
    private final DiagnosticsLevel level;
    private final int capacity;
    private final ArrayDeque<DiagnosticsSnapshot> history;
    private DiagnosticsSnapshot latest;
    private FrozenDiagnostics.ResourceTable latestResources = FrozenDiagnostics.ResourceTable.EMPTY;
    private FrozenDiagnostics.MessageTable latestMessages = FrozenDiagnostics.MessageTable.EMPTY;
    private GlDebug.ResourceSnapshot lastBackendResources;
    private GlDebug.MessageSnapshot lastBackendMessages;
    private long epoch;
    private long lastUploadBytes;
    private DiagnosticsSnapshot.SceneSummary pendingScene;
    private DiagnosticsSnapshot.UiSummary pendingUi;
    private boolean closed;

    public FrameDiagnostics(DiagnosticsLevel level) {
        this(level, DEFAULT_HISTORY_CAPACITY);
    }

    public FrameDiagnostics(DiagnosticsLevel level, int capacity) {
        this.level = Objects.requireNonNull(level, "level");
        if (capacity < 16 || capacity > 4096) {
            throw new IllegalArgumentException("history capacity must be between 16 and 4096");
        }
        this.capacity = capacity;
        history = new ArrayDeque<>(capacity);
        latest = DiagnosticsSnapshot.empty(level);
    }

    public DiagnosticsLevel level() { return level; }
    public int capacity() { return capacity; }
    public synchronized DiagnosticsSnapshot latest() {
        ensureOpen();
        return latest;
    }

    /** 原子返回同一已发布帧边界的概要、资源和消息。 */
    public synchronized ReadView read() {
        ensureOpen();
        return new ReadView(latest, latestResources, latestMessages);
    }

    /** 返回调用时刻的一致只读历史，按 frame sequence 递增。 */
    public synchronized History history() {
        ensureOpen();
        return new History(epoch, List.copyOf(history));
    }

    /** 捕获不再受 live ring 淘汰或 clear 影响的一致历史。 */
    public synchronized FrozenDiagnostics freeze() {
        ensureOpen();
        return new FrozenDiagnostics(1, epoch, List.copyOf(history),
                latestResources, latestMessages, DiagnosticsBuildInfo.capture(GlDebug.contextInfo()));
    }

    /** 开启新 epoch；全局 frame sequence 不回退。 */
    public synchronized void clear() {
        ensureOpen();
        history.clear();
        epoch++;
        latest = DiagnosticsSnapshot.empty(level);
        latestResources = FrozenDiagnostics.ResourceTable.EMPTY;
        latestMessages = FrozenDiagnostics.MessageTable.EMPTY;
        lastBackendResources = null;
        lastBackendMessages = null;
        pendingScene = null;
        pendingUi = null;
    }

    /** 仅供 owner FrameDriver 在帧边界发布。 */
    public synchronized void publish(RenderStatistics.Snapshot statistics,
                                     FrameProfile profile, RenderGraph.Description graph,
                                     StateCache.Statistics state) {
        publish(statistics, profile, graph, state, true, 0L, 0L, 0L);
    }

    /** 发布成功或失败帧；失败帧保留身份和 CPU 数据但明确标记 incomplete。 */
    public synchronized void publish(RenderStatistics.Snapshot statistics,
                                     FrameProfile profile, RenderGraph.Description graph,
                                     StateCache.Statistics state, boolean complete) {
        publish(statistics, profile, graph, state, complete, 0L, 0L, 0L);
    }

    /** 为下一次帧发布附加不依赖具体 subsystem 类型的场景摘要。 */
    public synchronized void scene(long drawCalls, long instanceCount,
                                   long ordinaryRenderers, long instancedRenderers) {
        ensureOpen();
        if (drawCalls < 0L || instanceCount < 0L || ordinaryRenderers < 0L || instancedRenderers < 0L) {
            throw new IllegalArgumentException("scene statistics must be non-negative");
        }
        pendingScene = new DiagnosticsSnapshot.SceneSummary(drawCalls, instanceCount,
                ordinaryRenderers, instancedRenderers);
    }

    /** 为下一次帧发布附加值类型 UI 摘要，避免 runtime 反向依赖 UI subsystem。 */
    public synchronized void ui(long visibleNodes, long quads, long glyphs, long drawCalls,
                                long updateNanos, long vertexBytes, long indexBytes,
                                long atlasUploadBytes) {
        ensureOpen();
        long[] values = {visibleNodes, quads, glyphs, drawCalls, updateNanos,
                vertexBytes, indexBytes, atlasUploadBytes};
        for (long value : values) {
            if (value < 0L) throw new IllegalArgumentException("UI statistics must be non-negative");
        }
        pendingUi = new DiagnosticsSnapshot.UiSummary(visibleNodes, quads, glyphs, drawCalls,
                updateNanos, vertexBytes, indexBytes, atlasUploadBytes);
    }

    /** owner 使用的完整发布入口。 */
    public synchronized void publish(RenderStatistics.Snapshot statistics,
                                     FrameProfile profile, RenderGraph.Description graph,
                                     StateCache.Statistics state, boolean complete,
                                     long totalUploadBytes, long uploadQueueDepth,
                                     long uploadGpuUpdates) {
        ensureOpen();
        if (level == DiagnosticsLevel.OFF) {
            pendingScene = null;
            pendingUi = null;
            return;
        }
        long sequence = profile.frameSequence() >= 0L
                ? profile.frameSequence() : statistics.submittedFrames() - 1L;
        if (!history.isEmpty() && sequence <= history.getLast().frameSequence()) {
            throw new IllegalStateException("diagnostic frame sequence must increase: "
                    + sequence + " <= " + history.getLast().frameSequence());
        }
        GlDebug.ResourceSnapshot backendResources = level == DiagnosticsLevel.DETAILED
                ? GlDebug.resources() : GlDebug.ResourceSnapshot.EMPTY;
        GlDebug.MessageSnapshot backendMessages = GlDebug.messages();
        if (backendResources != lastBackendResources) {
            latestResources = adaptResources(backendResources);
            lastBackendResources = backendResources;
        }
        if (level == DiagnosticsLevel.DETAILED && backendMessages != lastBackendMessages) {
            latestMessages = adaptMessages(backendMessages);
            lastBackendMessages = backendMessages;
        }
        DiagnosticsSnapshot snapshot = new DiagnosticsSnapshot(epoch, sequence,
                statistics.presentedFrames(), level, complete, statistics.presentFps(),
                statistics.lastPresentIntervalNanos(), profile,
                new DiagnosticsSnapshot.State(state.appliedChanges(), state.avoidedChanges()),
                resourceSummary(latestResources), messageSummary(backendMessages),
                Optional.ofNullable(pendingScene),
                new DiagnosticsSnapshot.UploadSummary(Math.max(0L, totalUploadBytes - lastUploadBytes),
                        totalUploadBytes, uploadQueueDepth, uploadGpuUpdates),
                Optional.ofNullable(pendingUi),
                level == DiagnosticsLevel.DETAILED ? Optional.ofNullable(graph) : Optional.empty());
        lastUploadBytes = totalUploadBytes;
        pendingScene = null;
        pendingUi = null;
        if (history.size() == capacity) history.removeFirst();
        history.addLast(snapshot);
        latest = snapshot;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        history.clear();
        latestResources = FrozenDiagnostics.ResourceTable.EMPTY;
        latestMessages = FrozenDiagnostics.MessageTable.EMPTY;
        lastBackendResources = null;
        lastBackendMessages = null;
        pendingScene = null;
        pendingUi = null;
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("FrameDiagnostics is closed");
    }

    private static DiagnosticsSnapshot.ResourceSummary resourceSummary(
            FrozenDiagnostics.ResourceTable snapshot) {
        return new DiagnosticsSnapshot.ResourceSummary(snapshot.live().size(),
                snapshot.estimatedBytes(), snapshot.createdCount(), snapshot.closedCount(),
                snapshot.highWaterMark());
    }

    private DiagnosticsSnapshot.MessageSummary messageSummary(GlDebug.MessageSnapshot snapshot) {
        long high = 0L, medium = 0L, low = 0L, notification = 0L;
        for (GlDebug.DebugMessage message : snapshot.messages()) {
            switch (message.severity()) {
                case "HIGH" -> high += message.repeatCount();
                case "MEDIUM" -> medium += message.repeatCount();
                case "LOW" -> low += message.repeatCount();
                default -> notification += message.repeatCount();
            }
        }
        return new DiagnosticsSnapshot.MessageSummary(high, medium, low,
                level == DiagnosticsLevel.DETAILED ? notification : 0L, snapshot.droppedCount());
    }

    private static FrozenDiagnostics.ResourceTable adaptResources(GlDebug.ResourceSnapshot source) {
        List<FrozenDiagnostics.Resource> resources = source.liveResources().stream()
                .map(item -> new FrozenDiagnostics.Resource(item.resourceSequence(), item.kind(),
                        item.label(), item.nativeId(), item.createdFrameSequence(),
                        item.estimatedBytes(), item.contextIdentity()))
                .toList();
        return new FrozenDiagnostics.ResourceTable(resources, source.estimatedBytes(),
                source.createdCount(), source.closedCount(), source.highWaterMark());
    }

    private static FrozenDiagnostics.MessageTable adaptMessages(GlDebug.MessageSnapshot source) {
        List<FrozenDiagnostics.Message> messages = source.messages().stream()
                .map(item -> new FrozenDiagnostics.Message(item.sequence(), item.source(), item.type(),
                        item.severity(), item.driverId(), item.message(),
                        item.firstFrameSequence(), item.lastFrameSequence(), item.repeatCount(),
                        item.contextIdentity(), item.phase()))
                .toList();
        return new FrozenDiagnostics.MessageTable(messages, source.droppedCount());
    }

    public record History(long epoch, List<DiagnosticsSnapshot> frames) {
        public History {
            frames = List.copyOf(frames);
        }
    }

    public record ReadView(DiagnosticsSnapshot snapshot,
                           FrozenDiagnostics.ResourceTable resources,
                           FrozenDiagnostics.MessageTable messages) {
        public ReadView {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(resources, "resources");
            Objects.requireNonNull(messages, "messages");
        }
    }
}
