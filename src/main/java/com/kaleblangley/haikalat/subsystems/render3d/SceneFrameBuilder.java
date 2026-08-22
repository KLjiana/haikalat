package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.IdentityHashMap;

/** 构建并复用普通 renderer 的 transform、bounds 与独立可见队列。 */
final class SceneFrameBuilder {
    private static final int MINIMUM_CAPACITY = 16;

    private final boolean staticCacheEnabled = Boolean.parseBoolean(
            System.getProperty("haikalat.scene.staticCache", "true"));
    private final boolean queueCacheEnabled = Boolean.parseBoolean(
            System.getProperty("haikalat.scene.queueCache", "true"));
    private final Frustum cameraFrustum = new Frustum();
    private final Frustum shadowFrustum = new Frustum();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f clip = new Matrix4f();
    private final Matrix4f forwardKeyMatrix = new Matrix4f();
    private final Matrix4f shadowKeyMatrix = new Matrix4f();
    private final SceneFrame frame = new SceneFrame();
    private MeshRenderer[] renderers = new MeshRenderer[0];
    private Matrix4f[] models = new Matrix4f[0];
    private Matrix4f[] pendingModels = new Matrix4f[0];
    private WorldBounds[] bounds = new WorldBounds[0];
    private WorldBounds[] pendingBounds = new WorldBounds[0];
    private Bounds3f[] cachedLocalBounds = new Bounds3f[0];
    private long[] cachedModelRevisions = new long[0];
    private long[] requestedModelRevisions = new long[0];
    private long[] cachedDeformationRevisions = new long[0];
    private boolean[] cacheValid = new boolean[0];
    private boolean[] modelMiss = new boolean[0];
    private boolean[] boundsMiss = new boolean[0];
    private int[] changed = new int[0];
    private int[] forward = new int[0];
    private int[] shadow = new int[0];
    private int[] scratch = new int[0];
    private int[] queueClass = new int[0];
    private float[] cameraDepth = new float[0];
    private long[] materialRevisions = new long[0];
    private int[] shader = new int[0];
    private int[] material = new int[0];
    private int[] mesh = new int[0];
    private boolean[] mirrored = new boolean[0];
    private boolean[] castsShadow = new boolean[0];
    private Scene cachedScene;
    private long cachedRevision = Long.MIN_VALUE;
    private long boundsRevision;
    private int entryCount;
    private int shadowCandidateCount;
    private boolean allModelsImmutable;
    private long observedMaterialMutationEpoch = Long.MIN_VALUE;
    private boolean immutableSceneCacheReady;
    private int immutableFiniteCount;
    private long cachedCameraRevision = Long.MIN_VALUE;
    private int cachedWidth = -1;
    private int cachedHeight = -1;

    private boolean forwardCacheValid;
    private long forwardSceneRevision;
    private long forwardBoundsRevision;
    private boolean forwardCullingEnabled;
    private int forwardCount;
    private int forwardOpaque;
    private int forwardMasked;
    private int forwardAdditive;
    private int forwardAlpha;
    private int forwardShaderChanges;
    private int forwardMaterialChanges;
    private int forwardMeshChanges;
    private int forwardBlendChanges;
    private int forwardMirroredChanges;
    private int forwardTransparentStableTies;

    private boolean shadowCacheValid;
    private long shadowSceneRevision;
    private long shadowBoundsRevision;
    private boolean shadowCullingEnabled;
    private boolean shadowEnabledKey;
    private int shadowCount;

    SceneFrame build(Scene scene, int width, int height, Matrix4fc shadowMatrix,
                     boolean shadowEnabled, boolean cullingEnabled, int frameIndex) {
        return build(scene, scene.camera(), width, height, shadowMatrix,
                shadowEnabled, cullingEnabled, frameIndex);
    }

    SceneFrame build(Scene scene, Camera camera, int width, int height,
                     Matrix4fc shadowMatrix, boolean shadowEnabled,
                     boolean cullingEnabled, int frameIndex) {
        return build(scene, camera, width, height, shadowMatrix, shadowEnabled,
                cullingEnabled, cullingEnabled, frameIndex);
    }

    SceneFrame build(Scene scene, Camera camera, int width, int height,
                     Matrix4fc shadowMatrix, boolean shadowEnabled,
                     boolean cullingEnabled, boolean shadowCulling,
                     int frameIndex) {
        long totalStart = System.nanoTime();
        frame.available = false;
        try {
            syncMembership(scene);
            syncMaterialState();
            long sceneRevision = scene.membershipRevision();
            if (canReuseImmutableFrame(camera, sceneRevision, width, height, shadowMatrix,
                    shadowEnabled, cullingEnabled, shadowCulling)) {
                return reuseImmutableFrame(sceneRevision, cullingEnabled, frameIndex, totalStart);
            }

            CameraProjection.stable(camera, width, height, projection);
            camera.getViewMatrix(view);
            projection.mul(view, clip);

            int staticRenderers = 0;
            int dynamicRenderers = 0;
            int modelHits = 0;
            int modelMisses = 0;
            int boundsHits = 0;
            int boundsMisses = 0;
            int finite = 0;
            int changedCount = 0;
            long stageStart = System.nanoTime();
            if (staticCacheEnabled && allModelsImmutable && immutableSceneCacheReady) {
                staticRenderers = entryCount;
                modelHits = entryCount;
                boundsHits = entryCount;
                finite = immutableFiniteCount;
            } else {
                for (int index = 0; index < entryCount; index++) {
                    MeshRenderer renderer = renderers[index];
                    boolean revisioned = renderer.revisionedModel();
                    if (revisioned) staticRenderers++; else dynamicRenderers++;
                    long revision = revisioned ? renderer.modelRevision() : Long.MIN_VALUE;
                    long deformationRevision = renderer.drawBinding().boundsRevision();
                    requestedModelRevisions[index] = revision;
                    boolean hit = staticCacheEnabled && revisioned && cacheValid[index]
                            && cachedModelRevisions[index] == revision
                            && cachedDeformationRevisions[index] == deformationRevision;
                    modelMiss[index] = !hit;
                    if (hit) {
                        modelHits++;
                    } else {
                        modelMisses++;
                        try {
                            renderer.modelMatrix(pendingModels[index].identity(), frameIndex);
                        } catch (RuntimeException | Error failure) {
                            throw stageFailure(index, "model update", failure);
                        }
                    }

                    Bounds3f local = renderer.mesh().localBounds();
                    boolean boundsHit = hit && cacheValid[index]
                            && cachedLocalBounds[index] == local;
                    boundsMiss[index] = !boundsHit;
                    if (boundsHit) {
                        boundsHits++;
                        if (!bounds[index].unbounded) finite++;
                    } else {
                        boundsMisses++;
                        changed[changedCount++] = index;
                    }
                }
            }
            long modelNanos = System.nanoTime() - stageStart;

            stageStart = System.nanoTime();
            for (int changedIndex = 0; changedIndex < changedCount; changedIndex++) {
                int index = changed[changedIndex];
                Bounds3f local = renderers[index].mesh().localBounds();
                Matrix4fc model = modelMiss[index] ? pendingModels[index] : models[index];
                try {
                    BoundsTransforms.world(local, model, pendingBounds[index]);
                } catch (RuntimeException | Error failure) {
                    throw stageFailure(index, "world bounds", failure);
                }
                if (!pendingBounds[index].unbounded) finite++;
            }
            long boundsNanos = System.nanoTime() - stageStart;

            for (int changedIndex = 0; changedIndex < changedCount; changedIndex++) {
                int index = changed[changedIndex];
                if (modelMiss[index]) {
                    models[index].set(pendingModels[index]);
                    mirrored[index] = models[index].determinant3x3() < 0.0f;
                    cachedModelRevisions[index] = requestedModelRevisions[index];
                    cachedDeformationRevisions[index] = renderers[index].drawBinding().boundsRevision();
                }
                if (boundsMiss[index]) {
                    bounds[index].set(pendingBounds[index]);
                    cachedLocalBounds[index] = renderers[index].mesh().localBounds();
                }
                cacheValid[index] = true;
            }
            if (changedCount != 0) boundsRevision = Math.incrementExact(boundsRevision);
            if (staticCacheEnabled && allModelsImmutable && !immutableSceneCacheReady) {
                immutableFiniteCount = finite;
                immutableSceneCacheReady = true;
            }

            long frustumNanos = 0L;
            long sortNanos = 0L;
            long transparentSortNanos = 0L;
            boolean forwardReused = forwardCacheMatches(sceneRevision, cullingEnabled);
            if (!forwardReused) {
                stageStart = System.nanoTime();
                cameraFrustum.set(clip);
                forwardCount = 0;
                int transparentAlphaCount = 0;
                for (int index = 0; index < entryCount; index++) {
                    if (!cullingEnabled || !cameraFrustum.outside(bounds[index])) {
                        if (queueClass[index] == RenderQueueClass.TRANSPARENT_ALPHA.ordinal()) {
                            cameraDepth[index] = cameraDepth(index);
                            transparentAlphaCount++;
                        }
                        forward[forwardCount++] = index;
                    }
                }
                frustumNanos += System.nanoTime() - stageStart;
                stageStart = System.nanoTime();
                RenderQueueSorter.forward(forward, forwardCount, scratch, queueClass,
                        cameraDepth, shader, material, mesh);
                long elapsedSort = System.nanoTime() - stageStart;
                sortNanos += elapsedSort;
                if (transparentAlphaCount > 1) transparentSortNanos = elapsedSort;
                updateForwardStatistics();
                forwardKeyMatrix.set(clip);
                forwardSceneRevision = sceneRevision;
                forwardBoundsRevision = boundsRevision;
                forwardCullingEnabled = cullingEnabled;
                forwardCacheValid = true;
            }

            boolean shadowReused = shadowCacheMatches(sceneRevision, shadowMatrix,
                    shadowEnabled, shadowCulling);
            if (!shadowReused) {
                stageStart = System.nanoTime();
                if (shadowEnabled && shadowCulling) shadowFrustum.set(shadowMatrix);
                shadowCount = 0;
                for (int index = 0; index < entryCount; index++) {
                    if (castsShadow[index] && shadowEnabled
                            && (!shadowCulling || !shadowFrustum.outside(bounds[index]))) {
                        shadow[shadowCount++] = index;
                    }
                }
                frustumNanos += System.nanoTime() - stageStart;
                stageStart = System.nanoTime();
                RenderQueueSorter.shadow(shadow, shadowCount, scratch, mesh);
                sortNanos += System.nanoTime() - stageStart;
                shadowKeyMatrix.set(shadowMatrix);
                shadowSceneRevision = sceneRevision;
                shadowBoundsRevision = boundsRevision;
                shadowCullingEnabled = shadowCulling;
                shadowEnabledKey = shadowEnabled;
                shadowCacheValid = true;
            }

            long totalNanos = System.nanoTime() - totalStart;
            frame.renderers = renderers;
            frame.models = models;
            frame.worldBounds = bounds;
            frame.mirrored = mirrored;
            frame.queueClasses = queueClass;
            frame.forwardIndices = forward;
            frame.shadowIndices = shadow;
            frame.forwardCount = forwardCount;
            frame.shadowCount = shadowCount;
            frame.changedIndices = changed;
            frame.changedCount = changedCount;
            frame.forceShadowPlanRebuild = dynamicRenderers != 0
                    || scene.requiresPerFrameShadowDeformation();
            frame.frameIndex = Integer.toUnsignedLong(frameIndex);
            frame.sceneRevision = sceneRevision;
            frame.statistics = new SceneFrame.Statistics(cullingEnabled, frame.sceneRevision,
                    entryCount, finite, entryCount - finite, forwardCount,
                    entryCount - forwardCount, shadowCandidateCount, shadowCount,
                    shadowCandidateCount - shadowCount, staticRenderers, dynamicRenderers,
                    modelHits, modelMisses, boundsHits, boundsMisses,
                    forwardReused, !forwardReused, shadowReused, !shadowReused,
                    modelNanos, boundsNanos, frustumNanos, sortNanos, totalNanos,
                    forwardOpaque, forwardMasked, forwardAdditive, forwardAlpha,
                    forwardShaderChanges,
                    forwardMaterialChanges, forwardMeshChanges, forwardBlendChanges,
                    forwardMirroredChanges, forwardReused ? 0 : entryCount, changedCount,
                    entryCount - finite, 0, forwardAlpha + forwardAdditive,
                    transparentSortNanos, forwardTransparentStableTies);
            cachedCameraRevision = camera.visibilityRevision();
            cachedWidth = width;
            cachedHeight = height;
            frame.available = true;
            return frame;
        } catch (RuntimeException | Error failure) {
            abort();
            throw failure;
        }
    }

    private boolean canReuseImmutableFrame(Camera camera, long sceneRevision, int width, int height,
                                           Matrix4fc shadowMatrix, boolean shadowEnabled,
                                           boolean cullingEnabled, boolean shadowCulling) {
        // Camera is extensible. A subclass can override matrix generation without updating the
        // base revision, so the O(1) shortcut is deliberately limited to the built-in camera.
        return camera.getClass() == Camera.class
                && staticCacheEnabled && queueCacheEnabled
                && allModelsImmutable && immutableSceneCacheReady
                && cachedRevision == sceneRevision
                && cachedCameraRevision == camera.visibilityRevision()
                && cachedWidth == width && cachedHeight == height
                && forwardCacheValid
                && forwardSceneRevision == sceneRevision
                && forwardBoundsRevision == boundsRevision
                && forwardCullingEnabled == cullingEnabled
                && shadowCacheMatches(sceneRevision, shadowMatrix, shadowEnabled, shadowCulling);
    }

    private SceneFrame reuseImmutableFrame(long sceneRevision, boolean cullingEnabled,
                                           int frameIndex, long totalStart) {
        long totalNanos = System.nanoTime() - totalStart;
        frame.frameIndex = Integer.toUnsignedLong(frameIndex);
        frame.sceneRevision = sceneRevision;
        frame.changedIndices = changed;
        frame.changedCount = 0;
        frame.forceShadowPlanRebuild = false;
        frame.statistics = new SceneFrame.Statistics(cullingEnabled, sceneRevision,
                entryCount, immutableFiniteCount, entryCount - immutableFiniteCount,
                forwardCount, entryCount - forwardCount, shadowCandidateCount, shadowCount,
                shadowCandidateCount - shadowCount, entryCount, 0,
                entryCount, 0, entryCount, 0,
                true, false, true, false,
                0L, 0L, 0L, 0L, totalNanos,
                forwardOpaque, forwardMasked, forwardAdditive, forwardAlpha,
                forwardShaderChanges,
                forwardMaterialChanges, forwardMeshChanges, forwardBlendChanges,
                forwardMirroredChanges, 0, 0, entryCount - immutableFiniteCount, 0,
                forwardAlpha + forwardAdditive, 0L, forwardTransparentStableTies);
        frame.available = true;
        return frame;
    }

    private boolean forwardCacheMatches(long sceneRevision, boolean cullingEnabled) {
        return queueCacheEnabled && forwardCacheValid
                && forwardSceneRevision == sceneRevision
                && forwardBoundsRevision == boundsRevision
                && forwardCullingEnabled == cullingEnabled
                && matrixEquals(forwardKeyMatrix, clip);
    }

    private boolean shadowCacheMatches(long sceneRevision, Matrix4fc shadowMatrix,
                                       boolean shadowEnabled, boolean cullingEnabled) {
        return queueCacheEnabled && shadowCacheValid
                && shadowSceneRevision == sceneRevision
                && shadowBoundsRevision == boundsRevision
                && shadowCullingEnabled == cullingEnabled
                && shadowEnabledKey == shadowEnabled
                && matrixEquals(shadowKeyMatrix, shadowMatrix);
    }

    private void updateForwardStatistics() {
        forwardOpaque = forwardMasked = forwardAdditive = forwardAlpha = 0;
        forwardShaderChanges = forwardMaterialChanges = forwardMeshChanges = 0;
        forwardBlendChanges = forwardMirroredChanges = 0;
        forwardTransparentStableTies = 0;
        for (int queue = 0; queue < forwardCount; queue++) {
            int index = forward[queue];
            switch (queueClass[index]) {
                case 0 -> forwardOpaque++;
                case 1 -> forwardMasked++;
                case 2 -> forwardAlpha++;
                default -> forwardAdditive++;
            }
            if (queue == 0) continue;
            int previous = forward[queue - 1];
            if (queueClass[index] == RenderQueueClass.TRANSPARENT_ALPHA.ordinal()
                    && queueClass[previous] == RenderQueueClass.TRANSPARENT_ALPHA.ordinal()
                    && Float.floatToIntBits(cameraDepth[index])
                    == Float.floatToIntBits(cameraDepth[previous])) {
                forwardTransparentStableTies++;
            }
            if (shader[index] != shader[previous]) forwardShaderChanges++;
            if (material[index] != material[previous]) forwardMaterialChanges++;
            if (mesh[index] != mesh[previous]) forwardMeshChanges++;
            if (queueClass[index] != queueClass[previous]) forwardBlendChanges++;
            if (mirrored[index] != mirrored[previous]) forwardMirroredChanges++;
        }
    }

    private void syncMembership(Scene scene) {
        long revision = scene.membershipRevision();
        // A freshly published Scene can legitimately have the same local revision as the
        // previous Scene. Revision equality only proves that one Scene did not change; it is
        // not a cross-Scene content identity.
        if (cachedScene == scene && cachedRevision == revision) return;
        entryCount = scene.rendererCount();
        ensureCapacity(entryCount);
        IdentityHashMap<Object, Integer> materialOrdinals = new IdentityHashMap<>();
        int nextMaterial = 0;
        shadowCandidateCount = 0;
        allModelsImmutable = true;
        immutableSceneCacheReady = false;
        immutableFiniteCount = 0;
        for (int index = 0; index < entryCount; index++) {
            MeshRenderer renderer = scene.rendererAt(index);
            renderers[index] = renderer;
            allModelsImmutable &= renderer.immutableModel() && !renderer.drawBinding().deformsVertices();
            RenderQueueClass rendererQueue = RenderQueueClass.classify(renderer.material());
            queueClass[index] = rendererQueue.ordinal();
            materialRevisions[index] = renderer.material().revision();
            shader[index] = renderer.material().material().shader().id();
            // 排序只按不可变 Material 模板分组。独立 MaterialInstance 的 override 仍由
            // RenderPipeline binding cursor 逐实例判断，不能让可变实例身份破坏默认材质批次。
            Object materialIdentity = renderer.material().material();
            Integer ordinal = materialOrdinals.get(materialIdentity);
            if (ordinal == null) {
                ordinal = nextMaterial++;
                materialOrdinals.put(materialIdentity, ordinal);
            }
            material[index] = ordinal;
            mesh[index] = renderer.mesh().vertexArray().id();
            // Transparent shadow approximation is not part of the v0.23 contract. A caller
            // retaining SceneObject's default castShadows=true must not silently turn an
            // ALPHA/ADDITIVE object into an opaque depth caster.
            castsShadow[index] = renderer.castShadows() && rendererQueue.castsOpaqueShadow();
            if (castsShadow[index]) shadowCandidateCount++;
            cacheValid[index] = false;
            cachedLocalBounds[index] = null;
        }
        Arrays.fill(renderers, entryCount, renderers.length, null);
        Arrays.fill(cachedLocalBounds, entryCount, cachedLocalBounds.length, null);
        Arrays.fill(cacheValid, entryCount, cacheValid.length, false);
        forwardCacheValid = false;
        shadowCacheValid = false;
        cachedScene = scene;
        cachedRevision = revision;
        observedMaterialMutationEpoch = MaterialInstance.mutationEpoch();
    }

    private void ensureCapacity(int required) {
        if (required <= renderers.length) return;
        int capacity = Math.max(MINIMUM_CAPACITY, renderers.length);
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
        int previous = renderers.length;
        renderers = Arrays.copyOf(renderers, capacity);
        models = Arrays.copyOf(models, capacity);
        pendingModels = Arrays.copyOf(pendingModels, capacity);
        bounds = Arrays.copyOf(bounds, capacity);
        pendingBounds = Arrays.copyOf(pendingBounds, capacity);
        for (int index = previous; index < capacity; index++) {
            models[index] = new Matrix4f();
            pendingModels[index] = new Matrix4f();
            bounds[index] = new WorldBounds();
            pendingBounds[index] = new WorldBounds();
        }
        cachedLocalBounds = Arrays.copyOf(cachedLocalBounds, capacity);
        cachedModelRevisions = Arrays.copyOf(cachedModelRevisions, capacity);
        requestedModelRevisions = Arrays.copyOf(requestedModelRevisions, capacity);
        cachedDeformationRevisions = Arrays.copyOf(cachedDeformationRevisions, capacity);
        cacheValid = Arrays.copyOf(cacheValid, capacity);
        modelMiss = Arrays.copyOf(modelMiss, capacity);
        boundsMiss = Arrays.copyOf(boundsMiss, capacity);
        changed = Arrays.copyOf(changed, capacity);
        forward = Arrays.copyOf(forward, capacity);
        shadow = Arrays.copyOf(shadow, capacity);
        scratch = Arrays.copyOf(scratch, capacity);
        queueClass = Arrays.copyOf(queueClass, capacity);
        cameraDepth = Arrays.copyOf(cameraDepth, capacity);
        materialRevisions = Arrays.copyOf(materialRevisions, capacity);
        shader = Arrays.copyOf(shader, capacity);
        material = Arrays.copyOf(material, capacity);
        mesh = Arrays.copyOf(mesh, capacity);
        mirrored = Arrays.copyOf(mirrored, capacity);
        castsShadow = Arrays.copyOf(castsShadow, capacity);
    }

    private void abort() {
        frame.available = false;
        frame.forwardCount = 0;
        frame.shadowCount = 0;
        frame.statistics = SceneFrame.Statistics.UNAVAILABLE;
    }

    private static boolean matrixEquals(Matrix4fc left, Matrix4fc right) {
        return bits(left.m00()) == bits(right.m00()) && bits(left.m01()) == bits(right.m01())
                && bits(left.m02()) == bits(right.m02()) && bits(left.m03()) == bits(right.m03())
                && bits(left.m10()) == bits(right.m10()) && bits(left.m11()) == bits(right.m11())
                && bits(left.m12()) == bits(right.m12()) && bits(left.m13()) == bits(right.m13())
                && bits(left.m20()) == bits(right.m20()) && bits(left.m21()) == bits(right.m21())
                && bits(left.m22()) == bits(right.m22()) && bits(left.m23()) == bits(right.m23())
                && bits(left.m30()) == bits(right.m30()) && bits(left.m31()) == bits(right.m31())
                && bits(left.m32()) == bits(right.m32()) && bits(left.m33()) == bits(right.m33());
    }

    private static int bits(float value) { return Float.floatToIntBits(value); }

    private static IllegalStateException stageFailure(int rendererIndex, String stage,
                                                      Throwable cause) {
        return new IllegalStateException("SceneFrame renderer[" + rendererIndex
                + "] failed during " + stage, cause);
    }

    private void syncMaterialState() {
        long mutationEpoch = MaterialInstance.mutationEpoch();
        if (observedMaterialMutationEpoch == mutationEpoch) return;
        for (int index = 0; index < entryCount; index++) {
            long revision = renderers[index].material().revision();
            if (materialRevisions[index] == revision) continue;
            materialRevisions[index] = revision;
            RenderQueueClass rendererQueue = RenderQueueClass.classify(renderers[index].material());
            queueClass[index] = rendererQueue.ordinal();
            boolean nextCastsShadow = renderers[index].castShadows()
                    && rendererQueue.castsOpaqueShadow();
            if (castsShadow[index] != nextCastsShadow) {
                shadowCandidateCount += nextCastsShadow ? 1 : -1;
                castsShadow[index] = nextCastsShadow;
                shadowCacheValid = false;
            }
            forwardCacheValid = false;
            immutableSceneCacheReady = false;
        }
        observedMaterialMutationEpoch = mutationEpoch;
    }

    private float cameraDepth(int index) {
        WorldBounds world = bounds[index];
        float x = world.unbounded ? models[index].m30() : (world.minX + world.maxX) * 0.5f;
        float y = world.unbounded ? models[index].m31() : (world.minY + world.maxY) * 0.5f;
        float z = world.unbounded ? models[index].m32() : (world.minZ + world.maxZ) * 0.5f;
        // Camera looks down -Z. Bounds-center sorting cannot order intersecting geometry inside
        // one mesh; applications must split those meshes when exact ordering is required.
        return -(view.m02() * x + view.m12() * y + view.m22() * z + view.m32());
    }
}
