package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;

/** Immutable point-light 3x2 block assignment for one frame. */
record PointShadowSlotPlan(SceneLightEntry entry, int shaderIndex, int slot, float score,
                           List<Matrix4f> faceMatrices, List<ShadowTileRect> faceTiles,
                           boolean newlyAssigned, boolean dirty,
                           ShadowFramePlan.MissReason missReason,
                           List<Boolean> dirtyFaces,
                           List<ShadowFramePlan.MissReason> faceMissReasons) {
    PointShadowSlotPlan(SceneLightEntry entry, int shaderIndex, int slot, float score,
                        List<Matrix4f> faceMatrices, List<ShadowTileRect> faceTiles,
                        boolean newlyAssigned, boolean dirty,
                        ShadowFramePlan.MissReason missReason) {
        this(entry, shaderIndex, slot, score, faceMatrices, faceTiles, newlyAssigned, dirty,
                missReason, java.util.Collections.nCopies(PointShadowAtlas.FACE_COUNT, dirty),
                java.util.Collections.nCopies(PointShadowAtlas.FACE_COUNT, missReason));
    }

    PointShadowSlotPlan {
        entry = Objects.requireNonNull(entry, "entry");
        faceMatrices = faceMatrices.stream().map(Matrix4f::new).toList();
        faceTiles = List.copyOf(faceTiles);
        missReason = Objects.requireNonNull(missReason, "missReason");
        if (slot < 0 || faceMatrices.size() != PointShadowAtlas.FACE_COUNT
                || faceTiles.size() != PointShadowAtlas.FACE_COUNT
                || dirtyFaces.size() != PointShadowAtlas.FACE_COUNT
                || faceMissReasons.size() != PointShadowAtlas.FACE_COUNT) {
            throw new IllegalArgumentException("invalid point shadow slot plan");
        }
        dirtyFaces = List.copyOf(dirtyFaces);
        faceMissReasons = List.copyOf(faceMissReasons);
        if (faceMissReasons.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("point face miss reasons must be non-null");
        }
    }

    PointShadowSlotPlan withCache(boolean nextDirty, ShadowFramePlan.MissReason reason) {
        return new PointShadowSlotPlan(entry, shaderIndex, slot, score, faceMatrices,
                faceTiles, newlyAssigned, nextDirty, reason,
                java.util.Collections.nCopies(PointShadowAtlas.FACE_COUNT, nextDirty),
                java.util.Collections.nCopies(PointShadowAtlas.FACE_COUNT, reason));
    }

    PointShadowSlotPlan withFaceCache(List<Boolean> nextDirty,
                                     List<ShadowFramePlan.MissReason> reasons) {
        boolean anyDirty = nextDirty.stream().anyMatch(Boolean::booleanValue);
        ShadowFramePlan.MissReason first = ShadowFramePlan.MissReason.NONE;
        for (ShadowFramePlan.MissReason reason : reasons) {
            if (reason != ShadowFramePlan.MissReason.NONE) {
                first = reason;
                break;
            }
        }
        return new PointShadowSlotPlan(entry, shaderIndex, slot, score, faceMatrices,
                faceTiles, newlyAssigned, anyDirty, first, nextDirty, reasons);
    }

    boolean faceDirty(int face) {
        return dirtyFaces.get(face);
    }

    ShadowFramePlan.MissReason faceMissReason(int face) {
        return faceMissReasons.get(face);
    }
}
