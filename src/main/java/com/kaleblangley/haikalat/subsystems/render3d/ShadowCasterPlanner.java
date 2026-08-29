package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.Objects;

/**
 * Package-private, allocation-stable shadow caster planner.
 *
 * <p>The planner owns the fixed twenty-view arena used by Render3D: four
 * directional cascades, twelve point-light faces and four spot tiles.  It
 * keeps membership as a bit mask per caster and as candidate-rank bitsets for
 * each view.  A changed caster is re-tested against the active views while
 * unchanged views retain their previous membership; this is what keeps the
 * sparse dynamic path proportional to changed casters rather than N x V.  The
 * rank bitsets also preserve the deterministic shared candidate order without
 * moving an entire dense slice when one caster enters or leaves a view.</p>
 */
final class ShadowCasterPlanner {
    static final int MAX_VIEWS = 20;
    private static final int DIRECTIONAL_BASE = 0;
    private static final int POINT_BASE = 4;
    private static final int SPOT_BASE = 16;
    private static final long EMPTY_MASK = 0L;

    private final Frustum[] frustums = new Frustum[MAX_VIEWS];
    private final boolean[] active = new boolean[MAX_VIEWS];
    private final boolean[] viewChanged = new boolean[MAX_VIEWS];
    private final boolean[] membershipChanged = new boolean[MAX_VIEWS];
    private final boolean[] transformChanged = new boolean[MAX_VIEWS];
    private final boolean[] materialChanged = new boolean[MAX_VIEWS];
    private final boolean[] deformationChanged = new boolean[MAX_VIEWS];
    private final long[] viewSignatures = new long[MAX_VIEWS];
    private final long[] previousViewSignatures = new long[MAX_VIEWS];
    private final long[] membershipEpoch = new long[MAX_VIEWS];
    private final long[] transformEpoch = new long[MAX_VIEWS];
    private final long[] materialEpoch = new long[MAX_VIEWS];
    private final long[] deformationEpoch = new long[MAX_VIEWS];
    private final long[] instanceEpoch = new long[MAX_VIEWS];
    private final long[] membershipMasks = new long[0];
    private long[] masks = membershipMasks;
    private long[] modelRevisions = new long[0];
    private long[] modelKeys = new long[0];
    private long[] boundsKeys = new long[0];
    private long[] materialRevisions = new long[0];
    private long[] deformationRevisions = new long[0];
    private boolean[] volatileCasters = new boolean[0];
    private boolean[] modelChangedEntries = new boolean[0];
    private boolean[] materialChangedEntries = new boolean[0];
    private boolean[] deformationChangedEntries = new boolean[0];
    private long[] oldMasks = new long[0];
    private final long[][] viewMembershipWords = new long[MAX_VIEWS][];
    private int[] candidateOrder = new int[0];
    /** Entry ordinal -> rank in candidateOrder; valid until the next full rebuild. */
    private int[] candidateRanks = new int[0];
    /** Current shared shadow-queue membership, kept separate from per-view masks. */
    private boolean[] candidateMembership = new boolean[0];
    private int candidateCount;
    private int[] counts = new int[MAX_VIEWS];
    private int[] viewKinds = new int[MAX_VIEWS];
    private int[] viewOwners = new int[MAX_VIEWS];
    private int[] viewFaces = new int[MAX_VIEWS];
    private float[] viewPadding = new float[MAX_VIEWS];
    private float[] lightX = new float[MAX_VIEWS];
    private float[] lightY = new float[MAX_VIEWS];
    private float[] lightZ = new float[MAX_VIEWS];
    private float[] lightRange = new float[MAX_VIEWS];
    private boolean[] instanceMembership = new boolean[MAX_VIEWS];
    private final ShadowCasterPlan published = new ShadowCasterPlan(this);
    private boolean initialized;
    private long previousSceneGeneration = Long.MIN_VALUE;
    private int previousEntryCount = -1;
    private int previousCandidateCount = -1;
    private int previousWidth = -1;
    private int previousHeight = -1;
    private long previousCameraRevision = Long.MIN_VALUE;
    private long previousLightingRevision = Long.MIN_VALUE;
    private long previousMembershipRevision = Long.MIN_VALUE;
    private long previousTransformModelRevision = Long.MIN_VALUE;
    private long previousMaterialRenderStateRevision = Long.MIN_VALUE;
    private long previousActiveMask;
    private boolean previousVisibility;
    private long lastViewLayoutSignature;
    private int activeViewCount;
    private int candidateCasters;
    private int volatileCount;
    private int casterViewTests;
    private int casterViewReferences;
    private int directionalReferences;
    private int pointReferences;
    private int spotReferences;
    private boolean lastPlanReused;
    private boolean lastFullRebuild;
    private int lastDirtyViews;
    private int lastReusedViews;
    private int lastEmptyViews;
    private long lastBuildNanos;
    private boolean instancePlanChanged;

    ShadowCasterPlanner() {
        for (int index = 0; index < MAX_VIEWS; index++) {
            frustums[index] = new Frustum();
            viewMembershipWords[index] = new long[0];
        }
    }

    ShadowCasterPlan plan(ShadowFramePlan shadowPlan, RenderFrameContext context,
                          SceneFrame frame, boolean sceneVisibility,
                          InstancedRenderer instanced) {
        Objects.requireNonNull(shadowPlan, "shadowPlan");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(frame, "frame");
        long start = System.nanoTime();
        int entries = frame.rendererCount();
        ensureEntryCapacity(entries);
        candidateCount = frame.shadowCount;
        ensureCandidateCapacity(candidateCount);
        configureViews(shadowPlan);
        SceneRevisionSnapshot revisions = context.revisions();
        long sceneGeneration = revisions.sceneGeneration();
        boolean sameAspectResize = initialized && previousWidth > 0 && previousHeight > 0
                && ((long) previousWidth * context.height()
                == (long) context.width() * previousHeight)
                && previousCameraRevision == revisions.cameraRevision()
                && previousLightingRevision == revisions.lightingRevision();
        if (sameAspectResize) Arrays.fill(viewChanged, false);
        boolean full = !initialized || previousSceneGeneration != sceneGeneration
                || previousEntryCount != entries || previousVisibility != sceneVisibility
                || previousCandidateCount != candidateCount
                || previousActiveMask != published.activeMask
                // Material and membership revisions are global classification
                // boundaries; movement remains sparse below.  A queue rebuild
                // alone does not force an N x V rebuild because changedIndices
                // remains the authoritative changed-entry frontier.
                || previousMembershipRevision != revisions.membershipRevision()
                || previousMaterialRenderStateRevision != revisions.materialRenderStateRevision()
                || frame.forceShadowPlanRebuild
                // A rebuilt candidate queue may have changed rank or membership.
                // Sparse view bitsets are keyed by the last published candidate
                // rank, so refresh them transactionally with the queue.
                || (initialized && !frame.statistics.shadowQueueReused());
        Arrays.fill(membershipChanged, false);
        Arrays.fill(transformChanged, false);
        Arrays.fill(materialChanged, false);
        Arrays.fill(deformationChanged, false);

        // An immutable, queue-reused frame with no changed entries has already
        // proven all model/bounds keys in SceneFrameBuilder.  Keep the published
        // slices and masks untouched instead of walking every renderer and every
        // active view again.  Global revisions cover mutations which are not
        // represented by SceneFrame.changedIndices (material state and membership).
        boolean viewsStable = true;
        for (int view = 0; view < MAX_VIEWS; view++) {
            if (active[view] && viewChanged[view]) {
                viewsStable = false;
                break;
            }
        }
        boolean instanceMembershipUpdated = false;
        boolean staticReuse = initialized && !full && viewsStable
                && frame.changedCount == 0
                && frame.statistics.shadowQueueReused()
                && frame.statistics.dynamicRenderers() == 0
                && previousMembershipRevision == revisions.membershipRevision()
                && previousTransformModelRevision == revisions.transformModelRevision()
                && previousMaterialRenderStateRevision == revisions.materialRenderStateRevision()
                && !frame.forceShadowPlanRebuild;
        if (staticReuse) {
            updateInstanceMembership(shadowPlan, frame, sceneVisibility, instanced);
            instanceMembershipUpdated = true;
            if (!instancePlanChanged) {
                casterViewTests = 0;
                lastPlanReused = true;
                lastFullRebuild = false;
                lastBuildNanos = System.nanoTime() - start;
                previousSceneGeneration = sceneGeneration;
                previousEntryCount = entries;
                previousCandidateCount = candidateCount;
                previousWidth = context.width();
                previousHeight = context.height();
                previousCameraRevision = revisions.cameraRevision();
                previousLightingRevision = revisions.lightingRevision();
                previousMembershipRevision = revisions.membershipRevision();
                previousTransformModelRevision = revisions.transformModelRevision();
                previousMaterialRenderStateRevision = revisions.materialRenderStateRevision();
                previousActiveMask = published.activeMask;
                previousVisibility = sceneVisibility;
                lastViewLayoutSignature = published.layoutSignature;
                System.arraycopy(viewSignatures, 0, previousViewSignatures, 0, MAX_VIEWS);
                published.fullRebuild = false;
                published.planReused = true;
                published.buildNanos = lastBuildNanos;
                return published;
            }
        }

        // Sparse membership bitsets are keyed by candidate rank.  Queue rebuilds
        // are therefore full planner boundaries; ordinary model/bounds changes
        // retain this order and only touch changedIndices below.
        if (full) {
            Arrays.fill(candidateMembership, 0, entries, false);
            Arrays.fill(candidateRanks, 0, entries, -1);
            for (int order = 0; order < candidateCount; order++) {
                int index = frame.shadowEntry(order);
                candidateOrder[order] = index;
                candidateRanks[index] = order;
                if (isCaster(frame, index)) candidateMembership[index] = true;
            }
        }
        boolean anyChanged = full;
        candidateCasters = full ? 0 : candidateCasters;
        volatileCount = full ? 0 : volatileCount;
        casterViewTests = 0;
        Arrays.fill(modelChangedEntries, 0, entries, false);
        Arrays.fill(materialChangedEntries, 0, entries, false);
        Arrays.fill(deformationChangedEntries, 0, entries, false);

        if (full) {
            Arrays.fill(masks, 0, entries, EMPTY_MASK);
            clearMembershipSlices();
            for (int index = 0; index < entries; index++) {
                MeshRenderer renderer = frame.renderer(index);
                boolean caster = isCaster(frame, index);
                boolean volatileCaster = caster && (!renderer.revisionedModel()
                        || frame.worldBounds[index].unbounded
                        || degenerate(frame.worldBounds[index]));
                volatileCasters[index] = volatileCaster;
                updateEntryRevisions(frame, index);
                modelChangedEntries[index] = true;
                materialChangedEntries[index] = true;
                deformationChangedEntries[index] = true;
            }
            // Count the actual shared shadow candidate queue, not renderers that
            // an optional padded legacy queue has already rejected.
            for (int order = 0; order < candidateCount; order++) {
                int index = candidateOrder[order];
                if (!isCaster(frame, index)) continue;
                candidateCasters++;
                if (volatileCasters[index]) volatileCount++;
            }
        } else {
            // SceneFrame.changedIndices is the sparse invalidation frontier.  A
            // queue/material/membership change made the plan full above, so this
            // loop only touches changed model/bounds/deformation entries.
            for (int changedIndex = 0; changedIndex < frame.changedCount; changedIndex++) {
                int index = frame.changedIndices[changedIndex];
                if (index < 0 || index >= entries) continue;
                oldMasks[index] = masks[index];
                MeshRenderer renderer = frame.renderer(index);
                boolean caster = isCaster(frame, index);
                boolean volatileCaster = caster && (!renderer.revisionedModel()
                        || frame.worldBounds[index].unbounded
                        || degenerate(frame.worldBounds[index]));
                if (volatileCasters[index] != volatileCaster) {
                    volatileCount += volatileCaster ? 1 : -1;
                }
                volatileCasters[index] = volatileCaster;
                long model = renderer.revisionedModel() ? renderer.modelRevision() : 0L;
                long material = renderer.material().revision();
                long deformation = renderer.drawBinding().boundsRevision();
                long modelKey = matrixKey(frame.models[index]);
                long boundsKey = boundsKey(frame.worldBounds[index]);
                boolean modelChanged = !renderer.revisionedModel()
                        || modelKeys[index] != modelKey
                        || boundsKeys[index] != boundsKey
                        || modelRevisions[index] != model;
                boolean materialChanged = materialRevisions[index] != material;
                boolean deformationChanged = deformationRevisions[index] != deformation;
                modelChangedEntries[index] = modelChanged;
                materialChangedEntries[index] = materialChanged;
                deformationChangedEntries[index] = deformationChanged;
                if (modelChanged || materialChanged || deformationChanged) anyChanged = true;
                modelRevisions[index] = model;
                modelKeys[index] = modelKey;
                boundsKeys[index] = boundsKey;
                materialRevisions[index] = material;
                deformationRevisions[index] = deformation;
            }
        }

        if (full) {
            for (int view = 0; view < MAX_VIEWS; view++) {
                if (!active[view]) continue;
                for (int order = 0; order < candidateCount; order++) {
                    int index = candidateOrder[order];
                    if (!isCaster(frame, index)) continue;
                    boolean included = include(view, frame.worldBounds[index], sceneVisibility);
                    setMembershipBit(view, index, included);
                    if (sceneVisibility && !frame.worldBounds[index].unbounded) casterViewTests++;
                }
                membershipChanged[view] = true;
                transformChanged[view] = true;
                materialChanged[view] = true;
                deformationChanged[view] = true;
            }
        } else {
            // A changed view must retest the stable candidate queue, while a
            // changed caster only updates its own membership in each stable view.
            for (int view = 0; view < MAX_VIEWS; view++) {
                if (!active[view] || !viewChanged[view]) continue;
                anyChanged = true;
                transformChanged[view] = true;
                for (int order = 0; order < candidateCount; order++) {
                    int index = candidateOrder[order];
                    if (!isCaster(frame, index)) continue;
                    boolean included = include(view, frame.worldBounds[index], sceneVisibility);
                    long before = masks[index];
                    setMembershipBit(view, index, included);
                    if (before != masks[index]) membershipChanged[view] = true;
                    if (sceneVisibility && !frame.worldBounds[index].unbounded) casterViewTests++;
                }
            }
            for (int changedIndex = 0; changedIndex < frame.changedCount; changedIndex++) {
                int index = frame.changedIndices[changedIndex];
                if (index < 0 || index >= entries) continue;
                if (!modelChangedEntries[index] && !materialChangedEntries[index]
                        && !deformationChangedEntries[index]) continue;
                boolean currentCandidate = candidateMembership[index] && isCaster(frame, index);
                long nextMask = currentCandidate ? masks[index] : EMPTY_MASK;
                for (int view = 0; view < MAX_VIEWS; view++) {
                    if (!active[view] || viewChanged[view]) continue;
                    long bit = 1L << view;
                    boolean included = currentCandidate
                            && include(view, frame.worldBounds[index], sceneVisibility);
                    if (included) nextMask |= bit; else nextMask &= ~bit;
                    if (sceneVisibility && currentCandidate
                            && !frame.worldBounds[index].unbounded) casterViewTests++;
                }
                applyMembershipMask(index, nextMask);
                if (oldMasks[index] != masks[index]) {
                    anyChanged = true;
                    long changedMask = oldMasks[index] ^ masks[index];
                    for (int view = 0; view < MAX_VIEWS; view++) {
                        if ((changedMask & (1L << view)) != 0L) membershipChanged[view] = true;
                    }
                }
            }
        }

        // Category epochs are derived from only the changed entries in the
        // sparse path.  The old all-entry/all-view aggregation made one dynamic
        // caster cost O(N x V) even when the membership itself was incremental.
        if (!full) {
            for (int changedIndex = 0; changedIndex < frame.changedCount; changedIndex++) {
                int index = frame.changedIndices[changedIndex];
                if (index < 0 || index >= entries) continue;
                long affected = oldMasks[index] | masks[index];
                for (int view = 0; view < MAX_VIEWS; view++) {
                    if (!active[view] || (affected & (1L << view)) == 0L) continue;
                    materialChanged[view] |= materialChangedEntries[index];
                    deformationChanged[view] |= deformationChangedEntries[index];
                    transformChanged[view] |= modelChangedEntries[index];
                }
            }
        }
        for (int view = 0; view < MAX_VIEWS; view++) {
            if (!active[view]) continue;
            if (membershipChanged[view]) membershipEpoch[view]++;
            if (transformChanged[view]) transformEpoch[view]++;
            if (materialChanged[view]) materialEpoch[view]++;
            if (deformationChanged[view]) deformationEpoch[view]++;
        }

        if (!instanceMembershipUpdated) {
            updateInstanceMembership(shadowPlan, frame, sceneVisibility, instanced);
        }
        lastPlanReused = initialized && !full && !anyChanged && !instancePlanChanged;
        lastFullRebuild = full;
        if (lastPlanReused) {
            // Keep the prior reference counters; no spatial tests were needed.
        } else {
            countReferences();
        }
        lastDirtyViews = 0;
        lastReusedViews = 0;
        lastEmptyViews = 0;
        lastBuildNanos = System.nanoTime() - start;
        initialized = true;
        previousSceneGeneration = sceneGeneration;
        previousEntryCount = entries;
        previousCandidateCount = candidateCount;
        previousWidth = context.width();
        previousHeight = context.height();
        previousCameraRevision = revisions.cameraRevision();
        previousLightingRevision = revisions.lightingRevision();
        previousMembershipRevision = revisions.membershipRevision();
        previousTransformModelRevision = revisions.transformModelRevision();
        previousMaterialRenderStateRevision = revisions.materialRenderStateRevision();
        previousActiveMask = published.activeMask;
        previousVisibility = sceneVisibility;
        lastViewLayoutSignature = published.layoutSignature;
        System.arraycopy(viewSignatures, 0, previousViewSignatures, 0, MAX_VIEWS);
        published.fullRebuild = full;
        published.planReused = lastPlanReused;
        published.buildNanos = lastBuildNanos;
        return published;
    }

    void recordCacheOutcome(int dirtyViews, int reusedViews, int emptyViews) {
        lastDirtyViews = Math.max(0, dirtyViews);
        lastReusedViews = Math.max(0, reusedViews);
        lastEmptyViews = Math.max(0, emptyViews);
    }

    ShadowCullingStatistics statistics() {
        if (!initialized) return ShadowCullingStatistics.UNAVAILABLE;
        return new ShadowCullingStatistics(true, lastPlanReused, lastFullRebuild,
                activeViewCount, lastDirtyViews, lastReusedViews, lastEmptyViews,
                candidateCasters, volatileCount, casterViewTests, casterViewReferences,
                Math.max(0, candidateCasters * activeViewCount - casterViewReferences),
                directionalReferences, pointReferences, spotReferences, lastBuildNanos);
    }

    void reset() {
        initialized = false;
        previousSceneGeneration = Long.MIN_VALUE;
        previousEntryCount = -1;
        previousCandidateCount = -1;
        previousWidth = -1;
        previousHeight = -1;
        previousCameraRevision = Long.MIN_VALUE;
        previousLightingRevision = Long.MIN_VALUE;
        previousMembershipRevision = Long.MIN_VALUE;
        previousTransformModelRevision = Long.MIN_VALUE;
        previousMaterialRenderStateRevision = Long.MIN_VALUE;
        previousActiveMask = 0L;
        previousVisibility = false;
        Arrays.fill(masks, 0L);
        Arrays.fill(candidateMembership, false);
        Arrays.fill(candidateRanks, -1);
        Arrays.fill(previousViewSignatures, 0L);
        Arrays.fill(viewSignatures, 0L);
        Arrays.fill(active, false);
        Arrays.fill(instanceEpoch, 0L);
        Arrays.fill(counts, 0);
        casterViewReferences = directionalReferences = pointReferences = spotReferences = 0;
        for (int view = 0; view < MAX_VIEWS; view++) {
            Arrays.fill(viewMembershipWords[view], 0L);
        }
        published.activeMask = 0L;
        published.layoutSignature = 0L;
        published.instanceMask = 0L;
        published.instanceTransformEpoch = Long.MIN_VALUE;
        published.instanceVolatile = false;
        instancePlanChanged = false;
    }

    private void configureViews(ShadowFramePlan source) {
        Arrays.fill(active, false);
        Arrays.fill(viewSignatures, 0L);
        Arrays.fill(instanceMembership, false);
        long activeMask = 0L;
        long layout = 0xcbf29ce484222325L;
        if (source.directional().isPresent()) {
            ShadowFramePlan.DirectionalPlan plan = source.directional().orElseThrow();
            for (int cascade = 0; cascade < plan.matrices().size(); cascade++) {
                int view = DIRECTIONAL_BASE + cascade;
                configure(view, 0, cascade, cascade, plan.entry(), plan.matrices().get(cascade),
                        Math.max(0.0f, plan.texelWorldSizes()[cascade]
                                * (source.filterMode().kernelRadius() + 2.0f)));
                active[view] = true;
                activeMask |= 1L << view;
                layout = mix(layout, viewSignatures[view]);
            }
        }
        for (PointShadowSlotPlan plan : source.points()) {
            for (int face = 0; face < PointShadowAtlas.FACE_COUNT; face++) {
                int view = POINT_BASE + plan.slot() * PointShadowAtlas.FACE_COUNT + face;
                configure(view, 1, plan.slot(), face, plan.entry(), plan.faceMatrices().get(face),
                        source.filterMode().kernelRadius() + 1.0f);
                active[view] = true;
                activeMask |= 1L << view;
                layout = mix(layout, viewSignatures[view]);
            }
        }
        for (SpotShadowSlotPlan plan : source.spots()) {
            int view = SPOT_BASE + plan.slot();
            configure(view, 2, plan.slot(), 0, plan.entry(), plan.lightSpaceMatrix(),
                    source.filterMode().kernelRadius() + 1.0f);
            active[view] = true;
            activeMask |= 1L << view;
            layout = mix(layout, viewSignatures[view]);
        }
        activeViewCount = Long.bitCount(activeMask);
        published.activeMask = activeMask;
        published.layoutSignature = layout;
        for (int view = 0; view < MAX_VIEWS; view++) {
            viewChanged[view] = active[view]
                    && (!initialized || previousViewSignatures[view] != viewSignatures[view]);
        }
        lastViewLayoutSignature = layout;
    }

    private void configure(int view, int kind, int owner, int face,
                           SceneLightEntry entry, Matrix4fc matrix, float padding) {
        viewKinds[view] = kind;
        viewOwners[view] = owner;
        viewFaces[view] = face;
        viewPadding[view] = padding;
        SceneLight light = entry.light();
        lightX[view] = light.position().x;
        lightY[view] = light.position().y;
        lightZ[view] = light.position().z;
        lightRange[view] = light.range();
        frustums[view].set(matrix);
        long signature = mix(entry.stableId(), entry.revision());
        signature = mix(signature, matrixKey(matrix));
        signature = mix(signature, Float.floatToIntBits(padding));
        viewSignatures[view] = signature;
    }

    private boolean include(int view, WorldBounds bounds, boolean sceneVisibility) {
        if (bounds.unbounded || degenerate(bounds) || !sceneVisibility) return true;
        if ((viewKinds[view] == 1 || viewKinds[view] == 2)
                && !rangeIntersects(bounds, view)) return false;
        return !frustums[view].outside(bounds, viewPadding[view]);
    }

    private boolean rangeIntersects(WorldBounds bounds, int view) {
        float minX = bounds.minX;
        float maxX = bounds.maxX;
        float minY = bounds.minY;
        float maxY = bounds.maxY;
        float minZ = bounds.minZ;
        float maxZ = bounds.maxZ;
        float x = clamp(lightX[view], minX, maxX);
        float y = clamp(lightY[view], minY, maxY);
        float z = clamp(lightZ[view], minZ, maxZ);
        float radius = lightRange[view] + viewPadding[view];
        return (x - lightX[view]) * (x - lightX[view])
                + (y - lightY[view]) * (y - lightY[view])
                + (z - lightZ[view]) * (z - lightZ[view]) <= radius * radius;
    }

    private void updateEntryRevisions(SceneFrame frame, int index) {
        MeshRenderer renderer = frame.renderer(index);
        long model = renderer.revisionedModel() ? renderer.modelRevision() : 0L;
        long material = renderer.material().revision();
        long deformation = renderer.drawBinding().boundsRevision();
        modelRevisions[index] = model;
        modelKeys[index] = matrixKey(frame.models[index]);
        boundsKeys[index] = boundsKey(frame.worldBounds[index]);
        materialRevisions[index] = material;
        deformationRevisions[index] = deformation;
    }

    private void clearMembershipSlices() {
        Arrays.fill(counts, 0);
        casterViewReferences = directionalReferences = pointReferences = spotReferences = 0;
        for (int view = 0; view < MAX_VIEWS; view++) {
            Arrays.fill(viewMembershipWords[view], 0L);
        }
    }

    private void setMembershipBit(int view, int index, boolean included) {
        long bit = 1L << view;
        boolean present = (masks[index] & bit) != 0L;
        if (present == included) return;
        updateViewMembership(view, index, included);
    }

    private void applyMembershipMask(int index, long nextMask) {
        long before = masks[index];
        long changed = before ^ nextMask;
        if (changed == 0L) return;
        for (int view = 0; view < MAX_VIEWS; view++) {
            long bit = 1L << view;
            if ((changed & bit) == 0L) continue;
            updateViewMembership(view, index, (nextMask & bit) != 0L);
        }
    }

    private void updateViewMembership(int view, int index, boolean included) {
        long bit = 1L << view;
        boolean present = (masks[index] & bit) != 0L;
        if (present == included) return;
        int rank = candidateRanks[index];
        if (rank < 0 || rank >= candidateCount) {
            throw new IllegalStateException("shadow candidate rank unavailable for entry " + index);
        }
        long rankBit = 1L << (rank & 63);
        int word = rank >>> 6;
        int delta;
        if (included) {
            viewMembershipWords[view][word] |= rankBit;
            masks[index] |= bit;
            counts[view]++;
            casterViewReferences++;
            delta = 1;
        } else {
            viewMembershipWords[view][word] &= ~rankBit;
            masks[index] &= ~bit;
            counts[view]--;
            casterViewReferences--;
            delta = -1;
        }
        switch (viewKinds[view]) {
            case 0 -> directionalReferences += delta;
            case 1 -> pointReferences += delta;
            default -> spotReferences += delta;
        }
    }

    private void countReferences() {
        casterViewReferences = 0;
        directionalReferences = pointReferences = spotReferences = 0;
        for (int view = 0; view < MAX_VIEWS; view++) {
            int count = active[view] ? counts[view] : 0;
            casterViewReferences += count;
            switch (viewKinds[view]) {
                case 0 -> directionalReferences += count;
                case 1 -> pointReferences += count;
                case 2 -> spotReferences += count;
                default -> { }
            }
        }
    }

    private void updateInstanceMembership(ShadowFramePlan source, SceneFrame frame,
                                           boolean sceneVisibility, InstancedRenderer renderer) {
        long previousMask = published.instanceMask;
        long previousEpoch = published.instanceTransformEpoch;
        boolean previousVolatile = published.instanceVolatile;
        if (renderer == null || !renderer.castShadows()) {
            Arrays.fill(instanceMembership, false);
            published.instanceMask = 0L;
            published.instanceVolatile = false;
            instancePlanChanged = previousMask != 0L || previousVolatile;
            return;
        }
        if (renderer.instanceCount() == 0) {
            Arrays.fill(instanceMembership, false);
            published.instanceMask = 0L;
            published.instanceVolatile = false;
            instancePlanChanged = previousMask != 0L || previousVolatile;
            return;
        }
        WorldBounds bounds = renderer.aggregateWorldBounds();
        boolean volatileBatch = renderer.instanceBoundsVolatile() || bounds.unbounded
                || degenerate(bounds);
        long mask = 0L;
        for (int view = 0; view < MAX_VIEWS; view++) {
            if (!active[view]) continue;
            boolean include = volatileBatch || !sceneVisibility || include(view, bounds, true);
            instanceMembership[view] = include;
            if (include) mask |= 1L << view;
        }
        published.instanceMask = mask;
        published.instanceVolatile = volatileBatch;
        long changedMembership = previousMask ^ mask;
        boolean contentChanged = previousEpoch != renderer.instanceTransformEpoch()
                || previousVolatile != volatileBatch;
        instancePlanChanged = changedMembership != 0L || contentChanged;
        for (int view = 0; view < MAX_VIEWS; view++) {
            if (!active[view]) continue;
            long bit = 1L << view;
            if ((changedMembership & bit) != 0L) membershipEpoch[view]++;
            if (contentChanged && ((previousMask | mask) & bit) != 0L) {
                instanceEpoch[view]++;
            }
        }
        published.instanceTransformEpoch = renderer.instanceTransformEpoch();
    }

    private void ensureEntryCapacity(int required) {
        if (required <= masks.length) return;
        int capacity = Math.max(16, masks.length);
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
        int previousCapacity = masks.length;
        masks = Arrays.copyOf(masks, capacity);
        modelRevisions = Arrays.copyOf(modelRevisions, capacity);
        modelKeys = Arrays.copyOf(modelKeys, capacity);
        boundsKeys = Arrays.copyOf(boundsKeys, capacity);
        materialRevisions = Arrays.copyOf(materialRevisions, capacity);
        deformationRevisions = Arrays.copyOf(deformationRevisions, capacity);
        volatileCasters = Arrays.copyOf(volatileCasters, capacity);
        modelChangedEntries = Arrays.copyOf(modelChangedEntries, capacity);
        materialChangedEntries = Arrays.copyOf(materialChangedEntries, capacity);
        deformationChangedEntries = Arrays.copyOf(deformationChangedEntries, capacity);
        oldMasks = Arrays.copyOf(oldMasks, capacity);
        candidateMembership = Arrays.copyOf(candidateMembership, capacity);
        candidateRanks = Arrays.copyOf(candidateRanks, capacity);
        Arrays.fill(candidateRanks, previousCapacity, capacity, -1);
    }

    private void ensureCandidateCapacity(int required) {
        if (required <= candidateOrder.length) return;
        int capacity = Math.max(16, candidateOrder.length);
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
        candidateOrder = Arrays.copyOf(candidateOrder, capacity);
        int words = (capacity + Long.SIZE - 1) / Long.SIZE;
        for (int view = 0; view < MAX_VIEWS; view++) {
            viewMembershipWords[view] = Arrays.copyOf(viewMembershipWords[view], words);
        }
    }

    private int nextCandidateRank(int view, int previousRank) {
        int next = previousRank + 1;
        if (next < 0 || next >= candidateCount) return -1;
        int wordIndex = next >>> 6;
        long word = viewMembershipWords[view][wordIndex] & (-1L << (next & 63));
        int wordLimit = (candidateCount + Long.SIZE - 1) / Long.SIZE;
        while (true) {
            if (word != 0L) {
                int rank = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                return rank < candidateCount ? rank : -1;
            }
            if (++wordIndex >= wordLimit) return -1;
            word = viewMembershipWords[view][wordIndex];
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isCaster(SceneFrame frame, int index) {
        MeshRenderer renderer = frame.renderer(index);
        return renderer.castShadows() && frame.castsOpaqueShadow(index);
    }

    /** A zero-volume bound cannot safely distinguish which cube face receives a shadow. */
    private static boolean degenerate(WorldBounds bounds) {
        return bounds.minX == bounds.maxX || bounds.minY == bounds.maxY
                || bounds.minZ == bounds.maxZ;
    }

    private static long boundsKey(WorldBounds bounds) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, Float.floatToIntBits(bounds.minX));
        hash = mix(hash, Float.floatToIntBits(bounds.minY));
        hash = mix(hash, Float.floatToIntBits(bounds.minZ));
        hash = mix(hash, Float.floatToIntBits(bounds.maxX));
        hash = mix(hash, Float.floatToIntBits(bounds.maxY));
        hash = mix(hash, Float.floatToIntBits(bounds.maxZ));
        return mix(hash, bounds.unbounded ? 1L : 0L);
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

    static final class ShadowCasterPlan {
        private final ShadowCasterPlanner owner;
        private long activeMask;
        private long layoutSignature;
        private long instanceMask;
        private long instanceTransformEpoch = Long.MIN_VALUE;
        private boolean instanceVolatile;
        private boolean planReused;
        private boolean fullRebuild;
        private long buildNanos;

        private ShadowCasterPlan(ShadowCasterPlanner owner) {
            this.owner = owner;
        }

        boolean active(int view) { return view >= 0 && view < MAX_VIEWS && owner.active[view]; }
        int count(int view) { return owner.counts[view]; }
        int firstCandidateRank(int view) { return owner.nextCandidateRank(view, -1); }
        int nextCandidateRank(int view, int rank) { return owner.nextCandidateRank(view, rank); }
        int casterAtRank(int rank) { return owner.candidateOrder[rank]; }
        boolean instanceVisible(int view) { return (instanceMask & (1L << view)) != 0L; }
        boolean instanceVolatile() { return instanceVolatile; }
        long membershipEpoch(int view) { return owner.membershipEpoch[view]; }
        long transformEpoch(int view) { return owner.transformEpoch[view]; }
        long materialEpoch(int view) { return owner.materialEpoch[view]; }
        long deformationEpoch(int view) { return owner.deformationEpoch[view]; }
        long instanceEpoch(int view) { return owner.instanceEpoch[view]; }
        boolean contentChanged(int view) {
            return owner.membershipChanged[view] || owner.transformChanged[view]
                    || owner.materialChanged[view] || owner.deformationChanged[view]
                    || owner.instancePlanChanged || fullRebuild;
        }
        boolean viewChanged(int view) { return owner.viewChanged[view]; }
        int activeViews() { return owner.activeViewCount; }
        long activeMask() { return activeMask; }
        long layoutSignature() { return layoutSignature; }
        int viewKind(int view) { return owner.viewKinds[view]; }
        int viewOwner(int view) { return owner.viewOwners[view]; }
        int viewFace(int view) { return owner.viewFaces[view]; }
        boolean fullRebuild() { return fullRebuild; }
        boolean planReused() { return planReused; }
        ShadowCullingStatistics statistics() { return owner.statistics(); }
    }
}
