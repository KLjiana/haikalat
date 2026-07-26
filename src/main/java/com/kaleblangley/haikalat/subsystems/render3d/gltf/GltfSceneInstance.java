package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.core.assets.gltf.GltfAlphaMode;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetException;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
import com.kaleblangley.haikalat.subsystems.animation.MorphWeightBuffer;
import com.kaleblangley.haikalat.subsystems.animation.Pose;
import com.kaleblangley.haikalat.subsystems.animation.PoseBuffer;
import com.kaleblangley.haikalat.subsystems.animation.gltf.GltfAnimationRig;
import com.kaleblangley.haikalat.subsystems.render3d.SceneDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Mutable playback instance for one uploaded glTF asset. */
public final class GltfSceneInstance implements AutoCloseable {
    private final GltfSceneAsset asset;
    private final GltfAnimationRig rig;
    private final PoseBuffer pose;
    private final AnimationPlayer player;
    private final Matrix4f rootTransform;
    private final Matrix4f[] nodeGlobals;
    private final Matrix4f[] nodeModels;
    private final Map<Integer, SkinSceneDrawBinding> skinBindings;
    private final Map<Integer, MorphWeightBuffer> morphWeights;
    private final List<MorphSceneDrawBinding> morphBindings = new ArrayList<>();
    private final List<SceneObject> objects;
    private int currentAnimationIndex = -1;
    private boolean closed;

    private GltfSceneInstance(GltfSceneAsset asset, Matrix4fc rootTransform,
                              boolean castShadows) {
        this.asset = asset;
        this.rootTransform = new Matrix4f(rootTransform);
        float determinant = this.rootTransform.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) <= 1.0e-12f) {
            throw new IllegalArgumentException("rootTransform must be finite and invertible");
        }
        rig = GltfAnimationRig.from(asset.sourceData());
        pose = rig.skeleton().createPoseBuffer();
        player = new AnimationPlayer(rig.skeleton());
        morphWeights = createMorphWeights();
        if (!rig.clips().isEmpty()) {
            currentAnimationIndex = 0;
            player.play(rig.clips().getFirst());
        }
        player.sample(pose);
        sampleMorphWeights();
        nodeGlobals = new Matrix4f[rig.skeleton().jointCount()];
        nodeModels = new Matrix4f[nodeGlobals.length];
        for (int node = 0; node < nodeGlobals.length; node++) {
            nodeGlobals[node] = new Matrix4f();
            nodeModels[node] = new Matrix4f();
        }
        updateNodeMatrices();

        skinBindings = createSkinBindings();
        try {
            updatePalettes();
            objects = createObjects(castShadows);
        } catch (RuntimeException failure) {
            closeBindings();
            throw failure;
        }
    }

    static GltfSceneInstance create(GltfSceneAsset asset, Matrix4fc rootTransform,
                                    boolean castShadows) {
        Objects.requireNonNull(asset, "asset").retainInstance();
        try {
            return new GltfSceneInstance(asset,
                    Objects.requireNonNull(rootTransform, "rootTransform"), castShadows);
        } catch (RuntimeException failure) {
            asset.releaseInstance();
            throw failure;
        }
    }

    public List<SceneObject> objects() {
        ensureOpen();
        return objects;
    }

    public int animationCount() {
        return rig.clips().size();
    }

    public List<String> animationNames() {
        return rig.clips().stream().map(AnimationClip::name).toList();
    }

    public GltfSceneInstance play(int animationIndex, AnimationPlayer.LoopMode loopMode) {
        ensureOpen();
        currentAnimationIndex = animationIndex;
        player.play(rig.clips().get(animationIndex), loopMode);
        player.sample(pose);
        resetMorphWeights();
        sampleMorphWeights();
        refreshPoseDependents();
        return this;
    }

    public GltfSceneInstance seek(float timeSeconds) {
        ensureOpen();
        player.seek(timeSeconds).sample(pose);
        sampleMorphWeights();
        refreshPoseDependents();
        return this;
    }

    /** Advances CPU animation and refreshes node models/palettes; call once per rendered frame. */
    public GltfSceneInstance update(float deltaSeconds) {
        ensureOpen();
        player.update(deltaSeconds, pose);
        sampleMorphWeights();
        refreshPoseDependents();
        return this;
    }

    public float animationTimeSeconds() {
        return player.timeSeconds();
    }

    public Pose pose() {
        ensureOpen();
        return pose.snapshot();
    }

    public float morphWeight(int nodeIndex, int targetIndex) {
        ensureOpen();
        return requireMorphWeights(nodeIndex).weight(targetIndex);
    }

    public float[] morphWeights(int nodeIndex) {
        ensureOpen();
        return requireMorphWeights(nodeIndex).toArray();
    }

    /** Overrides one instance-owned morph weight within the configured safety envelope. */
    public GltfSceneInstance setMorphWeight(int nodeIndex, int targetIndex, float value) {
        ensureOpen();
        requireMorphWeights(nodeIndex).setWeight(targetIndex, value);
        return this;
    }

    public Matrix4fc nodeModelMatrix(int nodeIndex) {
        ensureOpen();
        return new Matrix4f(nodeModels[nodeIndex]);
    }

    public Matrix4fc jointPaletteMatrix(int skinnedNodeIndex, int paletteIndex) {
        ensureOpen();
        SkinSceneDrawBinding binding = skinBindings.get(skinnedNodeIndex);
        if (binding == null) {
            throw new IllegalArgumentException("node " + skinnedNodeIndex
                    + " has no GPU skin binding");
        }
        return binding.palette().matrix(paletteIndex);
    }

    public boolean isClosed() {
        return closed;
    }

    public int morphWeightBufferCount() {
        ensureOpen();
        return morphBindings.size();
    }

    public long morphWeightGpuBytes() {
        ensureOpen();
        long bytes = 0L;
        for (MorphSceneDrawBinding binding : morphBindings) {
            bytes = Math.addExact(bytes, binding.weightByteSize());
        }
        return bytes;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = closeBindings();
        try {
            asset.releaseInstance();
        } catch (RuntimeException releaseFailure) {
            if (failure == null) failure = releaseFailure;
            else failure.addSuppressed(releaseFailure);
        }
        if (failure != null) throw failure;
    }

    private Map<Integer, SkinSceneDrawBinding> createSkinBindings() {
        Map<Integer, SkinSceneDrawBinding> result = new LinkedHashMap<>();
        LoadedGltfScene source = asset.sourceData();
        try {
            for (LoadedGltfScene.Node node : source.nodes()) {
                if (!node.reachable() || node.meshIndex() < 0) continue;
                LoadedGltfScene.NodeRigDef rigNode = source.nodeRigs().get(node.index());
                if (rigNode.skinIndex() < 0 || !meshHasSkinning(node.meshIndex())) continue;
                result.put(node.index(), new SkinSceneDrawBinding(
                        rig.skins().get(rigNode.skinIndex()).createPalette()));
            }
            return Map.copyOf(result);
        } catch (RuntimeException failure) {
            for (SkinSceneDrawBinding binding : result.values()) {
                try { binding.close(); } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    private Map<Integer, MorphWeightBuffer> createMorphWeights() {
        Map<Integer, MorphWeightBuffer> result = new LinkedHashMap<>();
        for (LoadedGltfScene.NodeRigDef node : asset.sourceData().nodeRigs()) {
            float[] weights = node.morphWeights();
            if (weights.length > 0) {
                result.put(node.nodeIndex(), new MorphWeightBuffer(weights));
            }
        }
        return Map.copyOf(result);
    }

    private boolean meshHasSkinning(int meshIndex) {
        LoadedGltfScene source = asset.sourceData();
        for (LoadedGltfScene.Primitive primitive : source.primitives()) {
            if (primitive.meshIndex() == meshIndex
                    && source.primitiveSkinning(primitive.index()).isPresent()) return true;
        }
        return false;
    }

    private List<SceneObject> createObjects(boolean castShadows) {
        LoadedGltfScene source = asset.sourceData();
        List<SceneObject> result = new ArrayList<>();
        for (LoadedGltfScene.Node node : source.nodes()) {
            if (!node.reachable() || node.meshIndex() < 0) continue;
            LoadedGltfScene.NodeRigDef rigNode = source.nodeRigs().get(node.index());
            SkinSceneDrawBinding nodeBinding = skinBindings.get(node.index());
            for (LoadedGltfScene.Primitive primitive : source.primitives()) {
                if (primitive.meshIndex() != node.meshIndex()) continue;
                LoadedGltfScene.MaterialDef material = source.materials()
                        .get(primitive.materialIndex());
                if (castShadows && material.alphaMode() == GltfAlphaMode.MASK) {
                    throw new GltfAssetException(source.source(),
                            GltfAssetException.Phase.INSTANTIATE,
                            "nodes[" + node.index() + "].mesh", "MASK materials require "
                            + "castShadows=false until masked shadow depth is supported");
                }
                SceneDrawBinding drawBinding = rigNode.skinIndex() >= 0
                        && source.primitiveSkinning(primitive.index()).isPresent()
                        ? nodeBinding : SceneDrawBinding.NONE;
                if (source.primitiveMorphTargets(primitive.index()).isPresent()) {
                    MorphWeightBuffer nodeWeights = requireMorphWeights(node.index());
                    MorphSceneDrawBinding morphBinding = new MorphSceneDrawBinding(
                            asset.morphBuffer(primitive.index()), nodeWeights, drawBinding);
                    morphBindings.add(morphBinding);
                    drawBinding = morphBinding;
                }
                int nodeIndex = node.index();
                result.add(new SceneObject(asset.mesh(primitive.index()),
                        asset.material(primitive),
                        (destination, frameIndex) -> destination.set(nodeModels[nodeIndex]),
                        castShadows, drawBinding));
            }
        }
        return List.copyOf(result);
    }

    private void refreshPoseDependents() {
        updateNodeMatrices();
        updatePalettes();
    }

    private void resetMorphWeights() {
        LoadedGltfScene source = asset.sourceData();
        for (Map.Entry<Integer, MorphWeightBuffer> entry : morphWeights.entrySet()) {
            entry.getValue().set(source.nodeRigs().get(entry.getKey()).morphWeights());
        }
    }

    private void sampleMorphWeights() {
        if (currentAnimationIndex < 0) return;
        float time = player.timeSeconds();
        rig.morphWeightTracks(currentAnimationIndex).forEach((nodeIndex, track) ->
                track.sample(time, requireMorphWeights(nodeIndex)));
    }

    private void updateNodeMatrices() {
        for (int node = 0; node < nodeGlobals.length; node++) {
            pose.globalMatrix(node, nodeGlobals[node]);
            nodeModels[node].set(rootTransform).mul(nodeGlobals[node]);
        }
    }

    private void updatePalettes() {
        for (Map.Entry<Integer, SkinSceneDrawBinding> entry : skinBindings.entrySet()) {
            entry.getValue().palette().update(pose, nodeGlobals[entry.getKey()]);
        }
    }

    private RuntimeException closeBindings() {
        RuntimeException failure = null;
        for (MorphSceneDrawBinding binding : morphBindings) {
            try {
                binding.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        for (SkinSceneDrawBinding binding : skinBindings.values()) {
            try {
                binding.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        return failure;
    }

    private MorphWeightBuffer requireMorphWeights(int nodeIndex) {
        MorphWeightBuffer result = morphWeights.get(nodeIndex);
        if (result == null) {
            throw new IllegalArgumentException("node " + nodeIndex
                    + " has no morph targets");
        }
        return result;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("glTF scene instance is closed");
    }
}
