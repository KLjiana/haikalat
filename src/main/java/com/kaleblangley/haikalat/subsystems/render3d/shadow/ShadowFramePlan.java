package com.kaleblangley.haikalat.subsystems.render3d.shadow;

import com.kaleblangley.haikalat.subsystems.render3d.SceneLightEntry;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowFilterMode;

import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable one-frame selection, mapping, projection and cache decision snapshot. */
public record ShadowFramePlan(Optional<DirectionalPlan> directional,
                       List<PointShadowSlotPlan> points,
                       List<SpotShadowSlotPlan> spots,
                       List<ShadowDecision> decisions,
                       int directionalCandidates,
                       int pointCandidates,
                       int spotCandidates,
                       ShadowFilterMode filterMode,
                       int pointCapacity,
                       int spotCapacity,
                       FrameTiming frameTiming) {
    public static final ShadowFramePlan EMPTY = new ShadowFramePlan(Optional.empty(), List.of(),
            List.of(), List.of(), 0, 0, 0, ShadowFilterMode.PCF_3X3, 0, 0);

    public ShadowFramePlan {
        directional = Objects.requireNonNull(directional, "directional");
        points = List.copyOf(points);
        spots = List.copyOf(spots);
        decisions = List.copyOf(decisions);
        filterMode = Objects.requireNonNull(filterMode, "filterMode");
        frameTiming = Objects.requireNonNull(frameTiming, "frameTiming");
    }

    /**
     * Frame facts used by frame-derived effects that travel with this one-frame
     * snapshot.  {@code frameSequence} is the RenderFrameContext sequence and
     * {@code deltaSeconds} is the same frame's validated duration.  Old plan
     * constructors keep {@link #NONE}, which advances no effect time.
     */
    public record FrameTiming(long frameSequence, float deltaSeconds) {
        public static final FrameTiming NONE = new FrameTiming(0L, 0.0f);

        public FrameTiming {
            if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
                throw new IllegalArgumentException(
                        "deltaSeconds must be finite and non-negative");
            }
        }
    }

    /** Source-compatible constructor for callers that have no frame timing yet. */
    public ShadowFramePlan(Optional<DirectionalPlan> directional,
                           List<PointShadowSlotPlan> points,
                           List<SpotShadowSlotPlan> spots,
                           List<ShadowDecision> decisions,
                           int directionalCandidates,
                           int pointCandidates,
                           int spotCandidates,
                           ShadowFilterMode filterMode,
                           int pointCapacity,
                           int spotCapacity) {
        this(directional, points, spots, decisions, directionalCandidates, pointCandidates,
                spotCandidates, filterMode, pointCapacity, spotCapacity, FrameTiming.NONE);
    }

    ShadowFramePlan withFrameTiming(long frameSequence, float deltaSeconds) {
        return new ShadowFramePlan(directional, points, spots, decisions, directionalCandidates,
                pointCandidates, spotCandidates, filterMode, pointCapacity, spotCapacity,
                new FrameTiming(frameSequence, deltaSeconds));
    }

    ShadowFramePlan withCache(Optional<DirectionalPlan> cachedDirectional,
                              List<PointShadowSlotPlan> cachedPoints,
                              List<SpotShadowSlotPlan> cachedSpots) {
        return new ShadowFramePlan(cachedDirectional, cachedPoints, cachedSpots, decisions,
                directionalCandidates, pointCandidates, spotCandidates, filterMode,
                pointCapacity, spotCapacity, frameTiming);
    }

    int selectedTiles() {
        int cascades = directional.map(value -> value.matrices().size()).orElse(0);
        return cascades + points.size() * PointShadowAtlas.FACE_COUNT + spots.size();
    }

    public enum MissReason {
        NONE,
        NEW_ALLOCATION,
        LIGHT,
        CASTER_MEMBERSHIP,
        TRANSFORM_MODEL,
        MATERIAL,
        DEFORMATION,
        CAMERA_CASCADE,
        SETTINGS,
        GENERATION,
        FRAME_FAILURE
    }

    public record DirectionalPlan(SceneLightEntry entry, int frameLightIndex, float score,
                           List<Matrix4f> matrices, float[] splits,
                           float[] texelWorldSizes, List<ShadowTileRect> tiles,
                           List<Boolean> dirtyTiles, List<MissReason> missReasons) {
        public DirectionalPlan {
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
            return new DirectionalPlan(entry, frameLightIndex, score, matrices, splits,
                    texelWorldSizes, tiles, dirty, reasons);
        }
    }
}
