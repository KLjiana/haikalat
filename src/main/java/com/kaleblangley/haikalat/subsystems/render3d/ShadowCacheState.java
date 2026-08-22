package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Transactional per-generation, per-shadow-view depth cache metadata. */
final class ShadowCacheState {
    private final ShadowCacheKey[] directional = new ShadowCacheKey[4];
    private final boolean[] failedDirectional = new boolean[4];
    private final ShadowCacheKey[][] points = new ShadowCacheKey[
            LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS][PointShadowAtlas.FACE_COUNT];
    private final boolean[][] failedPoints = new boolean[
            LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS][PointShadowAtlas.FACE_COUNT];
    private final ShadowCacheKey[] spots = new ShadowCacheKey[
            LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS];
    private final boolean[] failedSpots = new boolean[LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS];
    private Pending pending;
    private boolean uncachedPending;
    private boolean uncachedDirectionalSelected;
    private boolean uncachedPointSelected;
    private boolean uncachedSpotSelected;
    private int uncachedRendered;
    private boolean directionalAtlasInitialized;
    private boolean pointAtlasInitialized;
    private boolean spotAtlasInitialized;
    private int lastTilesRendered;
    private int lastTilesReused;
    private int lastCacheHits;
    private int lastCacheMisses;

    ShadowFramePlan prepare(ShadowFramePlan source, RenderFrameContext context, Scene scene,
                            LocalShadowPipelineSettings settings,
                            DirectionalCascadeSettings cascades) {
        return prepare(source, context, scene, settings, cascades, null);
    }

    ShadowFramePlan prepare(ShadowFramePlan source, RenderFrameContext context, Scene scene,
                            LocalShadowPipelineSettings settings,
                            DirectionalCascadeSettings cascades,
                            ShadowCasterPlanner.ShadowCasterPlan planner) {
        if (!settings.cacheStaticTiles()) {
            return prepareUncached(source);
        }
        long settingsKey = settingsKey(settings, cascades);
        ShadowCacheKey[] nextDirectionalKeys = new ShadowCacheKey[directional.length];
        Optional<ShadowFramePlan.DirectionalPlan> nextDirectional = source.directional().map(plan -> {
            var dirty = new java.util.ArrayList<Boolean>(plan.matrices().size());
            var reasons = new java.util.ArrayList<ShadowFramePlan.MissReason>(plan.matrices().size());
            for (int cascade = 0; cascade < plan.matrices().size(); cascade++) {
                int view = cascade;
                ShadowCacheKey key = key(context, plan.entry(), planner, view,
                        matrixKey(plan.matrices().get(cascade)), settingsKey);
                nextDirectionalKeys[cascade] = key;
                ShadowFramePlan.MissReason reason = difference(key, directional[cascade],
                        failedDirectional[cascade]);
                boolean isDirty = reason != ShadowFramePlan.MissReason.NONE;
                dirty.add(isDirty);
                reasons.add(reason);
                if (isDirty) {
                    directional[cascade] = null;
                    failedDirectional[cascade] = false;
                }
            }
            return plan.withCache(dirty, reasons);
        });

        ShadowCacheKey[][] nextPointKeys = new ShadowCacheKey[points.length][PointShadowAtlas.FACE_COUNT];
        var nextPoints = new java.util.ArrayList<PointShadowSlotPlan>(source.points().size());
        for (PointShadowSlotPlan plan : source.points()) {
            var dirtyFaces = new java.util.ArrayList<Boolean>(PointShadowAtlas.FACE_COUNT);
            var reasons = new java.util.ArrayList<ShadowFramePlan.MissReason>(PointShadowAtlas.FACE_COUNT);
            for (int face = 0; face < PointShadowAtlas.FACE_COUNT; face++) {
                int view = 4 + plan.slot() * PointShadowAtlas.FACE_COUNT + face;
                ShadowCacheKey key = key(context, plan.entry(), planner, view,
                        matrixKey(plan.faceMatrices().get(face)), settingsKey);
                nextPointKeys[plan.slot()][face] = key;
                ShadowFramePlan.MissReason reason = difference(key, points[plan.slot()][face],
                        failedPoints[plan.slot()][face]);
                boolean isDirty = reason != ShadowFramePlan.MissReason.NONE;
                dirtyFaces.add(isDirty);
                reasons.add(reason);
                if (isDirty) {
                    points[plan.slot()][face] = null;
                    failedPoints[plan.slot()][face] = false;
                }
            }
            nextPoints.add(plan.withFaceCache(dirtyFaces, reasons));
        }

        ShadowCacheKey[] nextSpotKeys = new ShadowCacheKey[spots.length];
        var nextSpots = new java.util.ArrayList<SpotShadowSlotPlan>(source.spots().size());
        for (SpotShadowSlotPlan plan : source.spots()) {
            int view = 16 + plan.slot();
            ShadowCacheKey key = key(context, plan.entry(), planner, view,
                    matrixKey(plan.lightSpaceMatrix()), settingsKey);
            nextSpotKeys[plan.slot()] = key;
            ShadowFramePlan.MissReason reason = difference(key, spots[plan.slot()],
                    failedSpots[plan.slot()]);
            boolean isDirty = reason != ShadowFramePlan.MissReason.NONE;
            if (isDirty) {
                spots[plan.slot()] = null;
                failedSpots[plan.slot()] = false;
            }
            nextSpots.add(plan.withCache(isDirty, reason));
        }

        ShadowFramePlan result = source.withCache(nextDirectional, nextPoints, nextSpots);
        int rendered = 0;
        if (result.directional().isPresent()) {
            rendered += (int) result.directional().orElseThrow().dirtyTiles().stream()
                    .filter(Boolean::booleanValue).count();
        }
        for (PointShadowSlotPlan plan : result.points()) {
            for (int face = 0; face < PointShadowAtlas.FACE_COUNT; face++) {
                if (plan.faceDirty(face)) rendered++;
            }
        }
        rendered += (int) result.spots().stream().filter(SpotShadowSlotPlan::dirty).count();
        int selected = result.selectedTiles();
        boolean[] directionalDirty = new boolean[directional.length];
        boolean[][] pointDirty = new boolean[points.length][PointShadowAtlas.FACE_COUNT];
        boolean[] spotDirty = new boolean[spots.length];
        if (result.directional().isPresent()) {
            List<Boolean> dirtyTiles = result.directional().orElseThrow().dirtyTiles();
            for (int index = 0; index < dirtyTiles.size(); index++) {
                directionalDirty[index] = dirtyTiles.get(index);
            }
        }
        for (PointShadowSlotPlan plan : result.points()) {
            for (int face = 0; face < PointShadowAtlas.FACE_COUNT; face++) {
                pointDirty[plan.slot()][face] = plan.faceDirty(face);
            }
        }
        for (SpotShadowSlotPlan plan : result.spots()) spotDirty[plan.slot()] = plan.dirty();
        pending = new Pending(nextDirectionalKeys, nextPointKeys, nextSpotKeys,
                directionalDirty, pointDirty, spotDirty,
                result.directional().isPresent(), !result.points().isEmpty(),
                !result.spots().isEmpty(), rendered, selected - rendered);
        return result;
    }

    private ShadowFramePlan prepareUncached(ShadowFramePlan source) {
        ShadowFramePlan result = dirtyUncached(source);
        int rendered = result.selectedTiles();
        pending = null;
        uncachedPending = true;
        uncachedDirectionalSelected = result.directional().isPresent();
        uncachedPointSelected = !result.points().isEmpty();
        uncachedSpotSelected = !result.spots().isEmpty();
        uncachedRendered = rendered;
        return result;
    }

    private static ShadowFramePlan dirtyUncached(ShadowFramePlan source) {
        Optional<ShadowFramePlan.DirectionalPlan> directionalPlan = source.directional()
                .map(plan -> plan.withCache(
                        Collections.nCopies(plan.matrices().size(), true),
                        Collections.nCopies(plan.matrices().size(),
                                ShadowFramePlan.MissReason.SETTINGS)));
        List<PointShadowSlotPlan> points = source.points().stream()
                .map(plan -> plan.withCache(true, ShadowFramePlan.MissReason.SETTINGS)).toList();
        List<SpotShadowSlotPlan> spots = source.spots().stream()
                .map(plan -> plan.withCache(true, ShadowFramePlan.MissReason.SETTINGS)).toList();
        return source.withCache(directionalPlan, points, spots);
    }

    void frameSucceeded() {
        if (uncachedPending) {
            uncachedPending = false;
            if (uncachedDirectionalSelected) Arrays.fill(failedDirectional, false);
            if (uncachedPointSelected) {
                for (boolean[] faces : failedPoints) Arrays.fill(faces, false);
            }
            if (uncachedSpotSelected) Arrays.fill(failedSpots, false);
            directionalAtlasInitialized |= uncachedDirectionalSelected;
            pointAtlasInitialized |= uncachedPointSelected;
            spotAtlasInitialized |= uncachedSpotSelected;
            lastTilesRendered = uncachedRendered;
            lastTilesReused = 0;
            lastCacheMisses = uncachedRendered;
            lastCacheHits = 0;
            return;
        }
        Pending completed = pending;
        pending = null;
        if (completed == null) return;
        System.arraycopy(completed.directionalKeys, 0, directional, 0, directional.length);
        for (int slot = 0; slot < points.length; slot++) {
            System.arraycopy(completed.pointKeys[slot], 0, points[slot], 0, PointShadowAtlas.FACE_COUNT);
        }
        System.arraycopy(completed.spotKeys, 0, spots, 0, spots.length);
        directionalAtlasInitialized |= completed.directionalSelected;
        pointAtlasInitialized |= completed.pointSelected;
        spotAtlasInitialized |= completed.spotSelected;
        lastTilesRendered = completed.rendered;
        lastTilesReused = completed.reused;
        lastCacheMisses = completed.rendered;
        lastCacheHits = completed.reused;
    }

    void frameFailed() {
        Pending failed = pending;
        if (failed != null) {
            for (int index = 0; index < failed.directionalDirty.length; index++) {
                if (failed.directionalDirty[index]) failedDirectional[index] = true;
            }
            for (int slot = 0; slot < failed.pointDirty.length; slot++) {
                for (int face = 0; face < failed.pointDirty[slot].length; face++) {
                    if (failed.pointDirty[slot][face]) failedPoints[slot][face] = true;
                }
            }
            for (int slot = 0; slot < failed.spotDirty.length; slot++) {
                if (failed.spotDirty[slot]) failedSpots[slot] = true;
            }
        } else if (uncachedPending) {
            if (uncachedDirectionalSelected) Arrays.fill(failedDirectional, true);
            if (uncachedPointSelected) {
                for (boolean[] faces : failedPoints) Arrays.fill(faces, true);
            }
            if (uncachedSpotSelected) Arrays.fill(failedSpots, true);
        }
        // Dirty keys were invalidated in prepare(). Keeping them null forces a
        // complete redraw after a partial GPU write. The explicit flags retain
        // the reason for the next recovery frame.
        pending = null;
        uncachedPending = false;
    }

    boolean directionalAtlasInitialized() { return directionalAtlasInitialized; }
    boolean pointAtlasInitialized() { return pointAtlasInitialized; }
    boolean spotAtlasInitialized() { return spotAtlasInitialized; }
    int lastTilesRendered() { return lastTilesRendered; }
    int lastTilesReused() { return lastTilesReused; }
    int lastCacheHits() { return lastCacheHits; }
    int lastCacheMisses() { return lastCacheMisses; }

    private static ShadowCacheKey key(RenderFrameContext context, SceneLightEntry entry,
                                      ShadowCasterPlanner.ShadowCasterPlan planner, int view,
                                      long cameraCascadeKey, long settingsKey) {
        long membership;
        long transform;
        long material;
        long deformation;
        if (planner == null || !planner.active(view)) {
            membership = transform = material = deformation = 0L;
        } else {
            membership = planner.membershipEpoch(view);
            transform = planner.transformEpoch(view);
            material = planner.materialEpoch(view);
            deformation = mix(planner.deformationEpoch(view), planner.instanceEpoch(view));
        }
        SceneRevisionSnapshot revision = context.revisions();
        return new ShadowCacheKey(revision.sceneGeneration(), entry.stableId(), entry.revision(),
                membership, transform, material, deformation, cameraCascadeKey, settingsKey);
    }

    private static long settingsKey(LocalShadowPipelineSettings settings,
                                    DirectionalCascadeSettings cascades) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, settings.point().hashCode());
        hash = mix(hash, settings.spot().hashCode());
        hash = mix(hash, settings.maxPointLights());
        hash = mix(hash, settings.maxSpotLights());
        hash = mix(hash, settings.filterMode().ordinal());
        hash = mix(hash, Float.floatToIntBits(settings.normalBias()));
        hash = mix(hash, cascades.hashCode());
        return hash;
    }

    private static ShadowFramePlan.MissReason difference(ShadowCacheKey next,
                                                         ShadowCacheKey previous,
                                                         boolean failed) {
        if (previous == null && failed) return ShadowFramePlan.MissReason.FRAME_FAILURE;
        return next.difference(previous);
    }

    private static long matrixKey(Matrix4fc matrix) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, Float.floatToIntBits(matrix.m00()));
        hash = mix(hash, Float.floatToIntBits(matrix.m01()));
        hash = mix(hash, Float.floatToIntBits(matrix.m02()));
        hash = mix(hash, Float.floatToIntBits(matrix.m03()));
        hash = mix(hash, Float.floatToIntBits(matrix.m10()));
        hash = mix(hash, Float.floatToIntBits(matrix.m11()));
        hash = mix(hash, Float.floatToIntBits(matrix.m12()));
        hash = mix(hash, Float.floatToIntBits(matrix.m13()));
        hash = mix(hash, Float.floatToIntBits(matrix.m20()));
        hash = mix(hash, Float.floatToIntBits(matrix.m21()));
        hash = mix(hash, Float.floatToIntBits(matrix.m22()));
        hash = mix(hash, Float.floatToIntBits(matrix.m23()));
        hash = mix(hash, Float.floatToIntBits(matrix.m30()));
        hash = mix(hash, Float.floatToIntBits(matrix.m31()));
        hash = mix(hash, Float.floatToIntBits(matrix.m32()));
        return mix(hash, Float.floatToIntBits(matrix.m33()));
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private record Pending(ShadowCacheKey[] directionalKeys,
                           ShadowCacheKey[][] pointKeys,
                           ShadowCacheKey[] spotKeys,
                           boolean[] directionalDirty,
                           boolean[][] pointDirty,
                           boolean[] spotDirty,
                           boolean directionalSelected,
                           boolean pointSelected,
                           boolean spotSelected,
                           int rendered,
                           int reused) {
        Pending {
            directionalKeys = Arrays.copyOf(directionalKeys, directionalKeys.length);
            pointKeys = Arrays.stream(pointKeys)
                    .map(value -> Arrays.copyOf(value, value.length))
                    .toArray(ShadowCacheKey[][]::new);
            spotKeys = Arrays.copyOf(spotKeys, spotKeys.length);
            directionalDirty = Arrays.copyOf(directionalDirty, directionalDirty.length);
            pointDirty = Arrays.stream(pointDirty)
                    .map(value -> Arrays.copyOf(value, value.length))
                    .toArray(boolean[][]::new);
            spotDirty = Arrays.copyOf(spotDirty, spotDirty.length);
        }
    }
}
