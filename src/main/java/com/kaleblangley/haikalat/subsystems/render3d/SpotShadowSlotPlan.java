package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;

import java.util.Objects;

/** Immutable spot-light tile assignment for one frame. */
record SpotShadowSlotPlan(SceneLightEntry entry, int shaderIndex, int slot, float score,
                          Matrix4f lightSpaceMatrix, ShadowTileRect tile,
                          boolean newlyAssigned, boolean dirty,
                          ShadowFramePlan.MissReason missReason) {
    SpotShadowSlotPlan {
        entry = Objects.requireNonNull(entry, "entry");
        lightSpaceMatrix = new Matrix4f(Objects.requireNonNull(lightSpaceMatrix,
                "lightSpaceMatrix"));
        tile = Objects.requireNonNull(tile, "tile");
        missReason = Objects.requireNonNull(missReason, "missReason");
        if (slot < 0) throw new IllegalArgumentException("slot must be non-negative");
    }

    SpotShadowSlotPlan withCache(boolean nextDirty, ShadowFramePlan.MissReason reason) {
        return new SpotShadowSlotPlan(entry, shaderIndex, slot, score, lightSpaceMatrix,
                tile, newlyAssigned, nextDirty, reason);
    }
}
