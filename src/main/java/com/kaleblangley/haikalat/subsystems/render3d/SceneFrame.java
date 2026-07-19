package com.kaleblangley.haikalat.subsystems.render3d;

/** 当前 RenderPipeline execute 内有效的借用 scene snapshot。 */
final class SceneFrame {
    MeshRenderer[] renderers;
    org.joml.Matrix4f[] models;
    WorldBounds[] worldBounds;
    boolean[] mirrored;
    int[] forwardIndices;
    int[] shadowIndices;
    int forwardCount;
    int shadowCount;
    long frameIndex;
    long sceneRevision;
    Statistics statistics = Statistics.UNAVAILABLE;
    boolean available;

    MeshRenderer forwardRenderer(int queueIndex) {
        return renderers[forwardIndices[queueIndex]];
    }

    org.joml.Matrix4f forwardModel(int queueIndex) {
        return models[forwardIndices[queueIndex]];
    }

    boolean forwardMirrored(int queueIndex) {
        return mirrored[forwardIndices[queueIndex]];
    }

    MeshRenderer shadowRenderer(int queueIndex) {
        return renderers[shadowIndices[queueIndex]];
    }

    org.joml.Matrix4f shadowModel(int queueIndex) {
        return models[shadowIndices[queueIndex]];
    }

    record Statistics(boolean cullingEnabled, long sceneRevision,
                      int candidateRenderers, int finiteBoundsRenderers,
                      int unboundedRenderers, int forwardVisible, int forwardCulled,
                      int shadowCandidates, int shadowVisible, int shadowCulled,
                      int staticRenderers, int dynamicRenderers,
                      int modelCacheHits, int modelCacheMisses,
                      int boundsCacheHits, int boundsCacheMisses,
                      boolean forwardQueueReused, boolean forwardQueueRebuilt,
                      boolean shadowQueueReused, boolean shadowQueueRebuilt,
                      long modelUpdateNanos, long boundsTransformNanos,
                      long frustumTestNanos, long queueSortNanos,
                      long totalQueueBuildNanos, int opaqueDraws,
                      int additiveDraws, int alphaDraws, int shaderChanges,
                      int materialChanges, int meshChanges, int blendChanges,
                      int mirroredChanges) {
        static final Statistics UNAVAILABLE = new Statistics(false, 0L,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, false, false, false, false,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
