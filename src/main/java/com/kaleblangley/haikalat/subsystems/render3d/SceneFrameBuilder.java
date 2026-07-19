package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.BlendMode;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.IdentityHashMap;

/** 构建并复用普通 renderer 的每帧 transform、bounds 与可见队列。 */
final class SceneFrameBuilder {
    private static final int MINIMUM_CAPACITY = 16;

    private final Frustum cameraFrustum = new Frustum();
    private final Frustum shadowFrustum = new Frustum();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f clip = new Matrix4f();
    private final SceneFrame frame = new SceneFrame();
    private MeshRenderer[] renderers = new MeshRenderer[0];
    private Matrix4f[] models = new Matrix4f[0];
    private WorldBounds[] bounds = new WorldBounds[0];
    private int[] forward = new int[0];
    private int[] shadow = new int[0];
    private int[] scratch = new int[0];
    private int[] blend = new int[0];
    private int[] shader = new int[0];
    private int[] material = new int[0];
    private int[] mesh = new int[0];
    private boolean[] mirrored = new boolean[0];
    private boolean[] castsShadow = new boolean[0];
    private long cachedRevision = Long.MIN_VALUE;
    private int entryCount;

    SceneFrame build(Scene scene, int width, int height, Matrix4fc shadowMatrix,
                     boolean shadowEnabled, boolean cullingEnabled, int frameIndex) {
        long totalStart = System.nanoTime();
        try {
            syncMembership(scene);
            CameraProjection.stable(scene.camera(), width, height, projection);
            scene.camera().getViewMatrix(view);
            cameraFrustum.set(projection.mul(view, clip));
            if (shadowEnabled) shadowFrustum.set(shadowMatrix);

            long stageStart = System.nanoTime();
            for (int index = 0; index < entryCount; index++) {
                Matrix4f model = models[index].identity();
                try {
                    renderers[index].modelMatrix(model, frameIndex);
                } catch (RuntimeException | Error failure) {
                    throw stageFailure(index, "model update", failure);
                }
                mirrored[index] = model.determinant3x3() < 0.0f;
            }
            long modelNanos = System.nanoTime() - stageStart;

            stageStart = System.nanoTime();
            int finite = 0;
            for (int index = 0; index < entryCount; index++) {
                try {
                    BoundsTransforms.world(renderers[index].mesh().localBounds(), models[index],
                            bounds[index]);
                } catch (RuntimeException | Error failure) {
                    throw stageFailure(index, "world bounds", failure);
                }
                if (!bounds[index].unbounded) finite++;
            }
            long boundsNanos = System.nanoTime() - stageStart;

            stageStart = System.nanoTime();
            int forwardCount = 0;
            int shadowCount = 0;
            int shadowCandidates = 0;
            for (int index = 0; index < entryCount; index++) {
                if (!cullingEnabled || !cameraFrustum.outside(bounds[index])) {
                    forward[forwardCount++] = index;
                }
                if (castsShadow[index]) {
                    shadowCandidates++;
                    if (shadowEnabled && (!cullingEnabled || !shadowFrustum.outside(bounds[index]))) {
                        shadow[shadowCount++] = index;
                    }
                }
            }
            long frustumNanos = System.nanoTime() - stageStart;

            stageStart = System.nanoTime();
            RenderQueueSorter.forward(forward, forwardCount, scratch, blend, shader, material, mesh);
            RenderQueueSorter.shadow(shadow, shadowCount, scratch, mesh);
            long sortNanos = System.nanoTime() - stageStart;

            int opaque = 0, additive = 0, alpha = 0;
            int shaderChanges = 0, materialChanges = 0, meshChanges = 0;
            int blendChanges = 0, mirroredChanges = 0;
            for (int queue = 0; queue < forwardCount; queue++) {
                int index = forward[queue];
                switch (blend[index]) {
                    case 0 -> opaque++;
                    case 1 -> additive++;
                    default -> alpha++;
                }
                if (queue > 0) {
                    int previous = forward[queue - 1];
                    if (shader[index] != shader[previous]) shaderChanges++;
                    if (material[index] != material[previous]) materialChanges++;
                    if (mesh[index] != mesh[previous]) meshChanges++;
                    if (blend[index] != blend[previous]) blendChanges++;
                    if (mirrored[index] != mirrored[previous]) mirroredChanges++;
                }
            }

            long totalNanos = System.nanoTime() - totalStart;
            frame.renderers = renderers;
            frame.models = models;
            frame.worldBounds = bounds;
            frame.forwardIndices = forward;
            frame.shadowIndices = shadow;
            frame.forwardCount = forwardCount;
            frame.shadowCount = shadowCount;
            frame.frameIndex = Integer.toUnsignedLong(frameIndex);
            frame.sceneRevision = scene.membershipRevision();
            frame.statistics = new SceneFrame.Statistics(cullingEnabled, frame.sceneRevision,
                    entryCount, finite, entryCount - finite, forwardCount,
                    entryCount - forwardCount, shadowCandidates, shadowCount,
                    shadowCandidates - shadowCount, modelNanos, boundsNanos, frustumNanos,
                    sortNanos, totalNanos, opaque, additive, alpha, shaderChanges,
                    materialChanges, meshChanges, blendChanges, mirroredChanges);
            frame.available = true;
            return frame;
        } catch (RuntimeException | Error failure) {
            abort();
            throw failure;
        }
    }

    private void syncMembership(Scene scene) {
        long revision = scene.membershipRevision();
        if (cachedRevision == revision) return;
        entryCount = scene.rendererCount();
        ensureCapacity(entryCount);
        IdentityHashMap<Object, Integer> materialOrdinals = new IdentityHashMap<>();
        int nextMaterial = 0;
        for (int index = 0; index < entryCount; index++) {
            MeshRenderer renderer = scene.rendererAt(index);
            renderers[index] = renderer;
            blend[index] = blendRank(renderer.material().material().blendMode());
            shader[index] = renderer.material().material().shader().id();
            Object materialIdentity = renderer.material().material();
            Integer ordinal = materialOrdinals.get(materialIdentity);
            if (ordinal == null) {
                ordinal = nextMaterial++;
                materialOrdinals.put(materialIdentity, ordinal);
            }
            material[index] = ordinal;
            mesh[index] = renderer.mesh().vertexArray().id();
            castsShadow[index] = renderer.castShadows();
        }
        Arrays.fill(renderers, entryCount, renderers.length, null);
        cachedRevision = revision;
    }

    private void ensureCapacity(int required) {
        if (required <= renderers.length) return;
        int capacity = Math.max(MINIMUM_CAPACITY, renderers.length);
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
        int previous = renderers.length;
        renderers = Arrays.copyOf(renderers, capacity);
        models = Arrays.copyOf(models, capacity);
        bounds = Arrays.copyOf(bounds, capacity);
        for (int index = previous; index < capacity; index++) {
            models[index] = new Matrix4f();
            bounds[index] = new WorldBounds();
        }
        forward = Arrays.copyOf(forward, capacity);
        shadow = Arrays.copyOf(shadow, capacity);
        scratch = Arrays.copyOf(scratch, capacity);
        blend = Arrays.copyOf(blend, capacity);
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

    private static IllegalStateException stageFailure(int rendererIndex, String stage,
                                                      Throwable cause) {
        return new IllegalStateException("SceneFrame renderer[" + rendererIndex
                + "] failed during " + stage, cause);
    }

    private static int blendRank(BlendMode mode) {
        return switch (mode) {
            case OPAQUE -> 0;
            case ADDITIVE -> 1;
            case ALPHA -> 2;
        };
    }
}
