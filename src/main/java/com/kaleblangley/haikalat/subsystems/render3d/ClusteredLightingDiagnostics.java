package com.kaleblangley.haikalat.subsystems.render3d;

/**
 * Bounded Clustered Forward diagnostic snapshot.
 *
 * <p>CPU facts come from the active generation's frozen storage.  GPU overflow
 * counters are an asynchronous, fence-gated snapshot of an already completed
 * assignment frame: {@code gpuCountersAvailable} stays false and the counter
 * fields stay -1 until the producer fence signals, so the render hot path never
 * blocks on a readback.</p>
 */
public record ClusteredLightingDiagnostics(
        boolean available,
        boolean gpuCountersAvailable,
        long gpuSampleFrameSequence,
        int tileSize,
        int zSlices,
        int inlineCapacity,
        int localCapacity,
        int directionalCapacity,
        int directionalLights,
        int localLights,
        int clusterCount,
        int overflowClusters,
        int maxInlineCount,
        int droppedIndices,
        long lightTableBytes,
        long clusterBoundsBytes,
        long clusterHeadersBytes,
        long clusterIndicesBytes,
        long totalResourceBytes) {

    public static final ClusteredLightingDiagnostics UNAVAILABLE = new ClusteredLightingDiagnostics(
            false, false, -1L, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -1, 0L, 0L, 0L, 0L, 0L);

    public int totalLights() {
        return directionalLights + localLights;
    }
}
