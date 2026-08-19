package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;

/** Immutable point-light 3x2 block assignment for one frame. */
record PointShadowSlotPlan(SceneLightEntry entry, int shaderIndex, int slot, float score,
                           List<Matrix4f> faceMatrices, List<ShadowTileRect> faceTiles,
                           boolean newlyAssigned, boolean dirty,
                           ShadowFramePlan.MissReason missReason) {
    PointShadowSlotPlan {
        entry = Objects.requireNonNull(entry, "entry");
        faceMatrices = faceMatrices.stream().map(Matrix4f::new).toList();
        faceTiles = List.copyOf(faceTiles);
        missReason = Objects.requireNonNull(missReason, "missReason");
        if (slot < 0 || faceMatrices.size() != PointShadowAtlas.FACE_COUNT
                || faceTiles.size() != PointShadowAtlas.FACE_COUNT) {
            throw new IllegalArgumentException("invalid point shadow slot plan");
        }
    }

    PointShadowSlotPlan withCache(boolean nextDirty, ShadowFramePlan.MissReason reason) {
        return new PointShadowSlotPlan(entry, shaderIndex, slot, score, faceMatrices,
                faceTiles, newlyAssigned, nextDirty, reason);
    }
}
