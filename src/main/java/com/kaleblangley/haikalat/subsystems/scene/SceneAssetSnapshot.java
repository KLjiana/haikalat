package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;

import java.util.List;
import java.util.Map;

/** Immutable bounded diagnostics value for the CPU scene service. */
public record SceneAssetSnapshot(
        int activeSceneHandles,
        int pendingCpuRequests,
        int pendingUploadRequests,
        int readyCandidates,
        int watcherCount,
        long watcherOverflowCount,
        int dependencyEdgeCount,
        Map<AssetId, ResourceGeneration> generations,
        List<HandleSnapshot> handles,
        List<FailureSnapshot> recentFailures,
        String lastFailurePhase,
        AssetId lastFailureAsset
) {
    public SceneAssetSnapshot {
        if (activeSceneHandles < 0 || pendingCpuRequests < 0
                || pendingUploadRequests < 0 || readyCandidates < 0
                || watcherCount < 0 || watcherOverflowCount < 0
                || dependencyEdgeCount < 0) {
            throw new IllegalArgumentException("diagnostic counts must be non-negative");
        }
        generations = Map.copyOf(generations);
        handles = List.copyOf(handles);
        recentFailures = List.copyOf(recentFailures);
    }

    /** Immutable per-handle generation and failure summary without GPU wrappers. */
    public record HandleSnapshot(
            AssetId asset,
            SceneHandle.Status status,
            ResourceGeneration activeGeneration,
            ResourceGeneration candidateGeneration,
            String failureType
    ) {
        public HandleSnapshot {
            java.util.Objects.requireNonNull(asset, "asset");
            java.util.Objects.requireNonNull(status, "status");
        }
    }

    /** Bounded structured failure history with no paths, bytes or native handles. */
    public record FailureSnapshot(
            String phase,
            AssetId asset,
            ResourceGeneration generation,
            String causeType
    ) {
        public FailureSnapshot {
            java.util.Objects.requireNonNull(phase, "phase");
            java.util.Objects.requireNonNull(asset, "asset");
            java.util.Objects.requireNonNull(generation, "generation");
            java.util.Objects.requireNonNull(causeType, "causeType");
        }
    }
}
