package com.kaleblangley.haikalat.core.mesh;

public record InstanceBatchStats(
        int submittedInstances,
        int drawnInstances,
        int drawCalls,
        int bufferUpdates,
        int meshGroups
) {
    public static InstanceBatchStats empty() {
        return new InstanceBatchStats(0, 0, 0, 0, 0);
    }
}
