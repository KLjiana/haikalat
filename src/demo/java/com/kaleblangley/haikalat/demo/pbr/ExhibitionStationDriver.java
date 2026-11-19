package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecal;

import java.util.List;

/**
 * Optional per-station hook for the mutable part of a preset that cannot live
 * in an immutable settings snapshot: VFX draw lists, VFX lights, decal sources
 * and similar frame data.
 */
interface ExhibitionStationDriver {
    /** Called after the station scene/settings were published and before the first frame. */
    default void onSelect(RenderPipeline pipeline, ExhibitionPhase phase) {
    }

    /** Called every frame before {@code pipeline.execute(...)}. */
    default void onFrame(RenderPipeline pipeline, int frameIndex, float deltaSeconds,
                         ExhibitionPhase phase) {
    }

    /** Called before a station re-applies its current phase, so station-local histories can restart. */
    default void onReset(RenderPipeline pipeline, ExhibitionPhase phase) {
    }

    /** Optional HDR VFX draw list recorded into the production composite pass. */
    default ExhibitionVfxFeed vfxFeed() {
        return null;
    }

    /** Optional projected-decal batch consumed by the production decal pass. */
    default List<ProjectedDecal> projectedDecals(int frameIndex, float deltaSeconds,
                                                 ExhibitionPhase phase) {
        return List.of();
    }
}
