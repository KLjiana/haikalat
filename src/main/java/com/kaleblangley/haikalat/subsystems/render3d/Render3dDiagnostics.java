package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.List;

/** On-demand, bounded Render3D diagnostic snapshot; no per-object lists are retained. */
public record Render3dDiagnostics(
        boolean available,
        RevisionSummary revisions,
        int invalidationBits,
        List<String> invalidationReasons,
        long activeGenerationId,
        long candidateGenerationId,
        long retiredGenerationId,
        String topologyKey,
        boolean topologyRebuilt,
        QueueSummary queues,
        VisibilitySummary visibility,
        ShadowSummary shadows,
        DepthResolveSummary depthResolve,
        CacheSummary caches,
        String failureStage) {

    public static final Render3dDiagnostics UNAVAILABLE = new Render3dDiagnostics(false,
            RevisionSummary.EMPTY, 0, List.of(), 0L, 0L, 0L, "", false,
            QueueSummary.EMPTY, VisibilitySummary.EMPTY, ShadowSummary.EMPTY,
            DepthResolveSummary.EMPTY, CacheSummary.EMPTY, "");

    public Render3dDiagnostics {
        invalidationReasons = List.copyOf(invalidationReasons);
        failureStage = failureStage == null ? "" : failureStage;
    }

    public record RevisionSummary(long membership, long transformModel, long lighting,
                                  long materialRenderState, long camera,
                                  long topologySettings) {
        static final RevisionSummary EMPTY = new RevisionSummary(0, 0, 0, 0, 0, 0);
    }

    public record QueueSummary(int opaque, int masked, int alpha, int additive,
                               long transparentSortNanos, int stableDepthTies) {
        static final QueueSummary EMPTY = new QueueSummary(0, 0, 0, 0, 0L, 0);
    }

    public record VisibilitySummary(int scanned, int visible, int frustumCulled,
                                    int missingBounds, int layerExcluded,
                                    int boundsUpdated, int transparentVisible,
                                    long frustumNanos, long queueBuildNanos) {
        static final VisibilitySummary EMPTY = new VisibilitySummary(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public record ShadowSummary(String directionalSelection, String pointSelection,
                                String spotSelection, int cascadeCount,
                                List<Float> cascadeSplits, List<Integer> cascadeCasters) {
        static final ShadowSummary EMPTY = new ShadowSummary("none", "none", "none",
                0, List.of(), List.of());

        public ShadowSummary {
            cascadeSplits = List.copyOf(cascadeSplits);
            cascadeCasters = List.copyOf(cascadeCasters);
        }
    }

    public record DepthResolveSummary(boolean executed, int sourceSamples, int targetSamples,
                                      int width, int height) {
        static final DepthResolveSummary EMPTY = new DepthResolveSummary(false, 0, 0, 0, 0);
    }

    public record CacheSummary(boolean forwardQueueHit, boolean shadowQueueHit,
                               int modelHits, int modelMisses, int boundsHits,
                               int boundsMisses, long generationBuilds,
                               long generationFailures, long pipelineCacheHits,
                               long pipelineCacheMisses) {
        static final CacheSummary EMPTY = new CacheSummary(false, false,
                0, 0, 0, 0, 0L, 0L, 0L, 0L);
    }
}
