package com.kaleblangley.haikalat.subsystems.render3d.scene;

import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import org.joml.Matrix4f;

/** Read-only view of the scene arena, valid until the builder starts the next frame. */
public final class SceneFrame {
    private MeshRenderer[] renderers;
    private Matrix4f[] models;
    private WorldBounds.Scratch[] bounds;
    private boolean[] mirrored;
    private int[] queueClasses;
    private int[] forwardIndices;
    private int[] shadowIndices;
    private int[] changedIndices;
    private int changedCount;
    private long frameIndex;
    private Statistics statistics = Statistics.UNAVAILABLE;

    SceneFrame() {}

    void publish(MeshRenderer[] renderers, Matrix4f[] models, WorldBounds.Scratch[] bounds,
                 boolean[] mirrored, int[] queueClasses, int[] forward, int[] shadow,
                 int[] changed, int changedCount, int frameIndex, Statistics statistics) {
        this.renderers = renderers;
        this.models = models;
        this.bounds = bounds;
        this.mirrored = mirrored;
        this.queueClasses = queueClasses;
        forwardIndices = forward;
        shadowIndices = shadow;
        changedIndices = changed;
        this.changedCount = changedCount;
        this.frameIndex = Integer.toUnsignedLong(frameIndex);
        this.statistics = statistics;
    }

    void invalidate() {
        changedCount = 0;
        statistics = Statistics.UNAVAILABLE;
    }

    public int forwardCount() { return statistics.forwardVisible(); }
    public int shadowCount() { return statistics.shadowVisible(); }
    public int changedCount() { return changedCount; }
    public int changedEntry(int index) { return changedIndices[index]; }
    public long frameIndex() { return frameIndex; }
    public Statistics statistics() { return statistics; }
    public WorldBounds bounds(int entry) { return bounds[entry].view(); }

    public int forwardEntry(int queueIndex) {
        return forwardIndices[queueIndex];
    }

    public int shadowEntry(int queueIndex) {
        return shadowIndices[queueIndex];
    }

    public MeshRenderer renderer(int entry) {
        return renderers[entry];
    }

    public int rendererCount() {
        return statistics.candidateRenderers();
    }

    boolean rendererChanged(int ordinal) {
        for (int index = 0; index < changedCount; index++) {
            if (changedIndices[index] == ordinal) return true;
        }
        return false;
    }

    public Matrix4f copyModel(int entry, Matrix4f destination) {
        Matrix4f source = models[entry];
        return destination.set(source.m00(), source.m01(), source.m02(), source.m03(),
                source.m10(), source.m11(), source.m12(), source.m13(),
                source.m20(), source.m21(), source.m22(), source.m23(),
                source.m30(), source.m31(), source.m32(), source.m33());
    }

    public boolean mirrored(int entry) {
        return mirrored[entry];
    }

    public boolean castsOpaqueShadow(int entry) {
        int queue = queueClasses[entry];
        return queue == RenderQueueClass.OPAQUE.ordinal()
                || queue == RenderQueueClass.MASKED.ordinal();
    }

    public boolean masked(int entry) {
        return queueClasses[entry] == RenderQueueClass.MASKED.ordinal();
    }

    public record Statistics(boolean cullingEnabled, long sceneRevision,
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
                      long totalQueueBuildNanos, int opaqueDraws, int maskedDraws,
                      int additiveDraws, int alphaDraws, int shaderChanges,
                      int materialChanges, int meshChanges, int blendChanges,
                      int mirroredChanges, int visibilityScanned,
                      int boundsUpdated, int missingBounds, int layerExcluded,
                      int transparentVisible, long transparentSortNanos,
                      int transparentStableTies) {
        static final Statistics UNAVAILABLE = new Statistics(false, 0L,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, false, false, false, false,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0L, 0);
    }
}
