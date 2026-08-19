package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.material.MaterialInstance;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Transactional per-generation shadow depth cache metadata. */
final class ShadowCacheState {
    private final ShadowCacheKey[] directional = new ShadowCacheKey[4];
    private final ShadowCacheKey[] points = new ShadowCacheKey[
            LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS];
    private final ShadowCacheKey[] spots = new ShadowCacheKey[
            LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS];
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
    private long observedMembershipRevision = Long.MIN_VALUE;
    private long observedTransformEpoch = Long.MIN_VALUE;
    private long observedRevisionScannedCasterEpoch = Long.MIN_VALUE;
    private long observedMaterialEpoch = Long.MIN_VALUE;
    private long casterMembershipRevision;
    private long casterTransformRevision;
    private long casterMaterialRevision;

    ShadowFramePlan prepare(ShadowFramePlan source, RenderFrameContext context, Scene scene,
                            LocalShadowPipelineSettings settings,
                            DirectionalCascadeSettings cascades) {
        if (!settings.cacheStaticTiles()) {
            return prepareUncached(source);
        }
        long settingsKey = settingsKey(settings, cascades);
        CasterRevisions casters = casterRevisions(scene, context.frameIndex());
        List<ShadowCacheKey> directionalKeys = new ArrayList<>();
        Optional<ShadowFramePlan.DirectionalPlan> nextDirectional = source.directional().map(plan -> {
            List<Boolean> dirty = new ArrayList<>();
            List<ShadowFramePlan.MissReason> reasons = new ArrayList<>();
            for (int cascade = 0; cascade < plan.matrices().size(); cascade++) {
                ShadowCacheKey key = key(context, plan.entry(), casters,
                        matrixKey(plan.matrices().get(cascade)), settingsKey);
                directionalKeys.add(key);
                ShadowFramePlan.MissReason reason = settings.cacheStaticTiles()
                        ? key.difference(directional[cascade])
                        : ShadowFramePlan.MissReason.SETTINGS;
                dirty.add(reason != ShadowFramePlan.MissReason.NONE);
                reasons.add(reason);
            }
            return plan.withCache(dirty, reasons);
        });

        ShadowCacheKey[] pointKeys = new ShadowCacheKey[points.length];
        List<PointShadowSlotPlan> nextPoints = new ArrayList<>(source.points().size());
        for (PointShadowSlotPlan plan : source.points()) {
            ShadowCacheKey key = key(context, plan.entry(), casters, 0L, settingsKey);
            pointKeys[plan.slot()] = key;
            ShadowFramePlan.MissReason reason = settings.cacheStaticTiles()
                    ? key.difference(points[plan.slot()])
                    : ShadowFramePlan.MissReason.SETTINGS;
            nextPoints.add(plan.withCache(reason != ShadowFramePlan.MissReason.NONE, reason));
        }

        ShadowCacheKey[] spotKeys = new ShadowCacheKey[spots.length];
        List<SpotShadowSlotPlan> nextSpots = new ArrayList<>(source.spots().size());
        for (SpotShadowSlotPlan plan : source.spots()) {
            ShadowCacheKey key = key(context, plan.entry(), casters, 0L, settingsKey);
            spotKeys[plan.slot()] = key;
            ShadowFramePlan.MissReason reason = settings.cacheStaticTiles()
                    ? key.difference(spots[plan.slot()])
                    : ShadowFramePlan.MissReason.SETTINGS;
            nextSpots.add(plan.withCache(reason != ShadowFramePlan.MissReason.NONE, reason));
        }

        ShadowFramePlan result = source.withCache(nextDirectional, nextPoints, nextSpots);
        int rendered = result.directional().map(value -> (int) value.dirtyTiles().stream()
                        .filter(Boolean::booleanValue).count()).orElse(0)
                + result.points().stream().mapToInt(value -> value.dirty()
                        ? PointShadowAtlas.FACE_COUNT : 0).sum()
                + (int) result.spots().stream().filter(SpotShadowSlotPlan::dirty).count();
        int selected = result.selectedTiles();
        pending = new Pending(directionalKeys, pointKeys, spotKeys,
                result.directional().isPresent(), !result.points().isEmpty(),
                !result.spots().isEmpty(), rendered, selected - rendered);
        return result;
    }

    private ShadowFramePlan prepareUncached(ShadowFramePlan source) {
        ShadowFramePlan result = fullyDirtyUncached(source) ? source : dirtyUncached(source);
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
                        java.util.Collections.nCopies(plan.matrices().size(), true),
                        java.util.Collections.nCopies(plan.matrices().size(),
                                ShadowFramePlan.MissReason.SETTINGS)));
        List<PointShadowSlotPlan> pointPlans = source.points().stream()
                .map(plan -> plan.withCache(true, ShadowFramePlan.MissReason.SETTINGS))
                .toList();
        List<SpotShadowSlotPlan> spotPlans = source.spots().stream()
                .map(plan -> plan.withCache(true, ShadowFramePlan.MissReason.SETTINGS))
                .toList();
        return source.withCache(directionalPlan, pointPlans, spotPlans);
    }

    private static boolean fullyDirtyUncached(ShadowFramePlan source) {
        if (source.directional().isPresent()) {
            ShadowFramePlan.DirectionalPlan directional = source.directional().orElseThrow();
            for (int index = 0; index < directional.dirtyTiles().size(); index++) {
                if (!directional.dirtyTiles().get(index)
                        || directional.missReasons().get(index)
                        != ShadowFramePlan.MissReason.SETTINGS) return false;
            }
        }
        for (PointShadowSlotPlan point : source.points()) {
            if (!point.dirty() || point.missReason() != ShadowFramePlan.MissReason.SETTINGS) {
                return false;
            }
        }
        for (SpotShadowSlotPlan spot : source.spots()) {
            if (!spot.dirty() || spot.missReason() != ShadowFramePlan.MissReason.SETTINGS) {
                return false;
            }
        }
        return true;
    }

    void frameSucceeded() {
        if (uncachedPending) {
            uncachedPending = false;
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
        for (int index = 0; index < completed.directionalKeys.size(); index++) {
            directional[index] = completed.directionalKeys.get(index);
        }
        for (int index = completed.directionalKeys.size(); index < directional.length; index++) {
            directional[index] = null;
        }
        commitSlots(points, completed.pointKeys);
        commitSlots(spots, completed.spotKeys);
        directionalAtlasInitialized |= completed.directionalSelected;
        pointAtlasInitialized |= completed.pointSelected;
        spotAtlasInitialized |= completed.spotSelected;
        lastTilesRendered = completed.rendered;
        lastTilesReused = completed.reused;
        lastCacheMisses = completed.rendered;
        lastCacheHits = completed.reused;
    }

    void frameFailed() {
        pending = null;
        uncachedPending = false;
    }

    boolean directionalAtlasInitialized() {
        return directionalAtlasInitialized;
    }

    boolean pointAtlasInitialized() {
        return pointAtlasInitialized;
    }

    boolean spotAtlasInitialized() {
        return spotAtlasInitialized;
    }

    int lastTilesRendered() {
        return lastTilesRendered;
    }

    int lastTilesReused() {
        return lastTilesReused;
    }

    int lastCacheHits() {
        return lastCacheHits;
    }

    int lastCacheMisses() {
        return lastCacheMisses;
    }

    private static void commitSlots(ShadowCacheKey[] destination, ShadowCacheKey[] source) {
        for (int slot = 0; slot < destination.length; slot++) {
            if (source[slot] != null) destination[slot] = source[slot];
            else destination[slot] = null;
        }
    }

    private static ShadowCacheKey key(RenderFrameContext context, SceneLightEntry entry,
                                      CasterRevisions casters, long cameraCascadeKey,
                                      long settingsKey) {
        SceneRevisionSnapshot revision = context.revisions();
        return new ShadowCacheKey(revision.sceneGeneration(), entry.stableId(), entry.revision(),
                casters.membership(), casters.transformModel(), casters.material(),
                casters.deformation(),
                cameraCascadeKey, settingsKey);
    }

    private CasterRevisions casterRevisions(Scene scene, int frameIndex) {
        long membership = scene.membershipRevision();
        long transformEpoch = Transform.mutationEpoch();
        long revisionScannedCasterEpoch = scene.revisionScannedShadowCasterEpoch();
        long materialEpoch = MaterialInstance.mutationEpoch();
        boolean membershipChanged = observedMembershipRevision != membership;
        if (membershipChanged || observedTransformEpoch != transformEpoch
                || observedRevisionScannedCasterEpoch != revisionScannedCasterEpoch
                || scene.requiresPerFrameModelRevision()) {
            long membershipHash = 0xcbf29ce484222325L;
            long transformHash = 0xcbf29ce484222325L;
            for (int index = 0; index < scene.rendererCount(); index++) {
                MeshRenderer renderer = scene.rendererAt(index);
                if (!shadowCaster(renderer)) continue;
                membershipHash = mix(membershipHash, index + 1L);
                long modelRevision = renderer.revisionedModel()
                        ? renderer.modelRevision() : Integer.toUnsignedLong(frameIndex);
                transformHash = mix(transformHash, modelRevision);
            }
            casterMembershipRevision = membershipHash;
            casterTransformRevision = transformHash;
            observedMembershipRevision = membership;
            observedTransformEpoch = transformEpoch;
            observedRevisionScannedCasterEpoch = revisionScannedCasterEpoch;
        }
        if (membershipChanged || observedMaterialEpoch != materialEpoch) {
            long materialHash = 0xcbf29ce484222325L;
            for (int index = 0; index < scene.rendererCount(); index++) {
                MeshRenderer renderer = scene.rendererAt(index);
                if (!shadowCaster(renderer)) continue;
                materialHash = mix(materialHash, renderer.material().revision());
            }
            casterMaterialRevision = materialHash;
            observedMaterialEpoch = materialEpoch;
        }
        long deformation = 0xcbf29ce484222325L;
        if (scene.requiresPerFrameShadowDeformation()) {
            for (int index = 0; index < scene.rendererCount(); index++) {
                MeshRenderer renderer = scene.rendererAt(index);
                if (shadowCaster(renderer) && renderer.drawBinding().deformsVertices()) {
                    deformation = mix(deformation, renderer.drawBinding().boundsRevision());
                }
            }
        }
        return new CasterRevisions(casterMembershipRevision, casterTransformRevision,
                casterMaterialRevision, deformation);
    }

    private static boolean shadowCaster(MeshRenderer renderer) {
        return renderer.castShadows()
                && RenderQueueClass.classify(renderer.material()).castsOpaqueShadow();
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

    private static long matrixKey(Matrix4f matrix) {
        float[] values = matrix.get(new float[16]);
        long hash = 0xcbf29ce484222325L;
        for (float value : values) hash = mix(hash, Float.floatToIntBits(value));
        return hash;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private record Pending(List<ShadowCacheKey> directionalKeys,
                           ShadowCacheKey[] pointKeys,
                           ShadowCacheKey[] spotKeys,
                           boolean directionalSelected,
                           boolean pointSelected,
                           boolean spotSelected,
                           int rendered,
                           int reused) {
        Pending {
            directionalKeys = List.copyOf(directionalKeys);
            pointKeys = Arrays.copyOf(pointKeys, pointKeys.length);
            spotKeys = Arrays.copyOf(spotKeys, spotKeys.length);
        }
    }

    private record CasterRevisions(long membership, long transformModel,
                                   long material, long deformation) { }
}
