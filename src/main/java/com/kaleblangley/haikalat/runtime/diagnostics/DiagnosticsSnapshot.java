package com.kaleblangley.haikalat.runtime.diagnostics;

import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.core.graph.RenderGraph;

import java.util.Objects;
import java.util.Optional;

/** 单个已发布帧的不可变诊断快照。 */
public record DiagnosticsSnapshot(long epoch, long frameSequence, long presentedFrameSequence,
                                  DiagnosticsLevel level, boolean complete,
                                  double presentFps, long presentIntervalNanos,
                                  FrameProfile frameProfile, State state,
                                  ResourceSummary resources, MessageSummary messages,
                                  Optional<SceneSummary> scene, UploadSummary upload,
                                  Optional<UiSummary> ui,
                                  Optional<RenderGraph.Description> graph) {
    public DiagnosticsSnapshot {
        level = Objects.requireNonNull(level, "level");
        frameProfile = Objects.requireNonNull(frameProfile, "frameProfile");
        state = Objects.requireNonNull(state, "state");
        resources = Objects.requireNonNull(resources, "resources");
        messages = Objects.requireNonNull(messages, "messages");
        scene = Objects.requireNonNull(scene, "scene");
        upload = Objects.requireNonNull(upload, "upload");
        ui = Objects.requireNonNull(ui, "ui");
        graph = Objects.requireNonNull(graph, "graph");
    }

    /** 尚未发布第一帧时使用的显式空值。 */
    public static DiagnosticsSnapshot empty(DiagnosticsLevel level) {
        return new DiagnosticsSnapshot(0L, -1L, 0L, level, false, 0.0, 0L,
                FrameProfile.EMPTY, State.EMPTY, Optional.empty());
    }

    private DiagnosticsSnapshot(long epoch, long frameSequence, long presentedFrameSequence,
                                DiagnosticsLevel level, boolean complete, double presentFps,
                                long presentIntervalNanos, FrameProfile frameProfile, State state,
                                Optional<RenderGraph.Description> graph) {
        this(epoch, frameSequence, presentedFrameSequence, level, complete, presentFps,
                presentIntervalNanos, frameProfile, state, ResourceSummary.EMPTY,
                MessageSummary.EMPTY, Optional.empty(), UploadSummary.EMPTY,
                Optional.empty(), graph);
    }

    /** OpenGL 状态缓存累计命中摘要。 */
    public record State(long appliedChanges, long avoidedChanges) {
        static final State EMPTY = new State(0L, 0L);

        public double skipRatio() {
            long total = appliedChanges + avoidedChanges;
            return total == 0L ? 0.0 : avoidedChanges / (double) total;
        }
    }


    public record ResourceSummary(int liveCount, long estimatedBytes, long createdCount,
                                  long closedCount, long highWaterMark) {
        static final ResourceSummary EMPTY = new ResourceSummary(0, 0L, 0L, 0L, 0L);
    }

    public record MessageSummary(long high, long medium, long low, long notification,
                                 long dropped) {
        static final MessageSummary EMPTY = new MessageSummary(0L, 0L, 0L, 0L, 0L);
        public long total() { return high + medium + low + notification; }
    }

    public record SceneSummary(long drawCalls, long instanceCount,
                               long ordinaryRenderers, long instancedRenderers,
                               Optional<VisibilitySummary> visibility) {
        public SceneSummary(long drawCalls, long instanceCount,
                            long ordinaryRenderers, long instancedRenderers) {
            this(drawCalls, instanceCount, ordinaryRenderers, instancedRenderers, Optional.empty());
        }

        public SceneSummary {
            visibility = Objects.requireNonNull(visibility, "visibility");
        }

        SceneSummary withVisibility(VisibilitySummary summary) {
            return new SceneSummary(drawCalls, instanceCount, ordinaryRenderers,
                    instancedRenderers, Optional.of(summary));
        }
    }

    /** 不包含逐对象数据的普通 scene visibility/queue 值摘要。 */
    public record VisibilitySummary(boolean cullingEnabled, long sceneRevision,
                                    long candidateRenderers, long finiteBoundsRenderers,
                                    long unboundedRenderers, long forwardVisible,
                                    long forwardCulled, long shadowCandidates,
                                    long shadowVisible, long shadowCulled,
                                    long staticRenderers, long dynamicRenderers,
                                    long modelCacheHits, long modelCacheMisses,
                                    long boundsCacheHits, long boundsCacheMisses,
                                    boolean forwardQueueReused, boolean forwardQueueRebuilt,
                                    boolean shadowQueueReused, boolean shadowQueueRebuilt,
                                    long modelUpdateNanos, long boundsTransformNanos,
                                    long frustumTestNanos, long queueSortNanos,
                                    long totalQueueBuildNanos, long opaqueDraws,
                                    long additiveDraws, long alphaDraws,
                                    long shaderChanges, long materialChanges,
                                    long meshChanges, long blendChanges,
                                    long mirroredChanges, long commandRecordNanos,
                                    long recordedCommands, long recordedMatrixSnapshots,
                                    long recordedObjectPayloads) {
        public VisibilitySummary(boolean cullingEnabled, long sceneRevision,
                                 long candidateRenderers, long finiteBoundsRenderers,
                                 long unboundedRenderers, long forwardVisible,
                                 long forwardCulled, long shadowCandidates,
                                 long shadowVisible, long shadowCulled,
                                 long modelUpdateNanos, long boundsTransformNanos,
                                 long frustumTestNanos, long queueSortNanos,
                                 long totalQueueBuildNanos, long opaqueDraws,
                                 long additiveDraws, long alphaDraws,
                                 long shaderChanges, long materialChanges,
                                 long meshChanges, long blendChanges,
                                 long mirroredChanges) {
            this(cullingEnabled, sceneRevision, candidateRenderers, finiteBoundsRenderers,
                    unboundedRenderers, forwardVisible, forwardCulled, shadowCandidates,
                    shadowVisible, shadowCulled, 0L, candidateRenderers, 0L,
                    candidateRenderers, 0L, candidateRenderers, false, true,
                    false, true, modelUpdateNanos, boundsTransformNanos,
                    frustumTestNanos, queueSortNanos, totalQueueBuildNanos,
                    opaqueDraws, additiveDraws, alphaDraws, shaderChanges,
                    materialChanges, meshChanges, blendChanges, mirroredChanges,
                    0L, 0L, 0L, 0L);
        }

        VisibilitySummary basic() {
            return new VisibilitySummary(cullingEnabled, sceneRevision, candidateRenderers,
                    finiteBoundsRenderers, unboundedRenderers, forwardVisible, forwardCulled,
                    shadowCandidates, shadowVisible, shadowCulled, staticRenderers,
                    dynamicRenderers, modelCacheHits, modelCacheMisses, boundsCacheHits,
                    boundsCacheMisses, forwardQueueReused, forwardQueueRebuilt,
                    shadowQueueReused, shadowQueueRebuilt, 0L, 0L, 0L, 0L,
                    totalQueueBuildNanos, opaqueDraws, additiveDraws, alphaDraws,
                    0L, 0L, 0L, 0L, 0L, commandRecordNanos, recordedCommands,
                    recordedMatrixSnapshots, recordedObjectPayloads);
        }
    }

    public record UploadSummary(long frameBytes, long totalBytes, long queueDepth,
                                long gpuUpdates) {
        static final UploadSummary EMPTY = new UploadSummary(0L, 0L, 0L, 0L);
    }

    public record UiSummary(long visibleNodes, long quads, long glyphs, long drawCalls,
                            long updateNanos, long vertexBytes, long indexBytes,
                            long atlasUploadBytes) {
    }
}
