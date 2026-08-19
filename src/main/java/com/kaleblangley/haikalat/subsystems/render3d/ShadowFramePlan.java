package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable one-frame selection, mapping, projection and cache decision snapshot. */
record ShadowFramePlan(Optional<DirectionalPlan> directional,
                       List<PointShadowSlotPlan> points,
                       List<SpotShadowSlotPlan> spots,
                       List<ShadowDecision> decisions,
                       int directionalCandidates,
                       int pointCandidates,
                       int spotCandidates,
                       ShadowFilterMode filterMode,
                       int pointCapacity,
                       int spotCapacity) {
    static final ShadowFramePlan EMPTY = new ShadowFramePlan(Optional.empty(), List.of(),
            List.of(), List.of(), 0, 0, 0, ShadowFilterMode.PCF_3X3, 0, 0);

    ShadowFramePlan {
        directional = Objects.requireNonNull(directional, "directional");
        points = List.copyOf(points);
        spots = List.copyOf(spots);
        decisions = List.copyOf(decisions);
        filterMode = Objects.requireNonNull(filterMode, "filterMode");
    }

    ShadowFramePlan withCache(Optional<DirectionalPlan> cachedDirectional,
                              List<PointShadowSlotPlan> cachedPoints,
                              List<SpotShadowSlotPlan> cachedSpots) {
        return new ShadowFramePlan(cachedDirectional, cachedPoints, cachedSpots, decisions,
                directionalCandidates, pointCandidates, spotCandidates, filterMode,
                pointCapacity, spotCapacity);
    }

    int selectedTiles() {
        int cascades = directional.map(value -> value.matrices().size()).orElse(0);
        return cascades + points.size() * PointShadowAtlas.FACE_COUNT + spots.size();
    }

    enum MissReason {
        NONE,
        NEW_ALLOCATION,
        LIGHT,
        CASTER_MEMBERSHIP,
        TRANSFORM_MODEL,
        MATERIAL,
        DEFORMATION,
        CAMERA_CASCADE,
        SETTINGS,
        GENERATION
    }

    record DirectionalPlan(SceneLightEntry entry, int shaderIndex, float score,
                           List<Matrix4f> matrices, float[] splits,
                           float[] texelWorldSizes, List<ShadowTileRect> tiles,
                           List<Boolean> dirtyTiles, List<MissReason> missReasons) {
        DirectionalPlan {
            entry = Objects.requireNonNull(entry, "entry");
            matrices = matrices.stream().map(Matrix4f::new).toList();
            splits = splits.clone();
            texelWorldSizes = texelWorldSizes.clone();
            tiles = List.copyOf(tiles);
            dirtyTiles = List.copyOf(dirtyTiles);
            missReasons = List.copyOf(missReasons);
            int count = matrices.size();
            if (count == 0 || splits.length != count || texelWorldSizes.length != count
                    || tiles.size() != count || dirtyTiles.size() != count
                    || missReasons.size() != count) {
                throw new IllegalArgumentException("directional cascade arrays must align");
            }
        }

        DirectionalPlan withCache(List<Boolean> dirty, List<MissReason> reasons) {
            return new DirectionalPlan(entry, shaderIndex, score, matrices, splits,
                    texelWorldSizes, tiles, dirty, reasons);
        }
    }
}
