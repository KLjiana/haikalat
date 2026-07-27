package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.core.assets.gltf.GltfAlphaMode;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetException;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.AnimationController;
import com.kaleblangley.haikalat.subsystems.animation.AnimationGraph;
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
import com.kaleblangley.haikalat.subsystems.animation.AnimationSignal;
import com.kaleblangley.haikalat.subsystems.animation.ClipMotion;
import com.kaleblangley.haikalat.subsystems.animation.MorphWeightBuffer;
import com.kaleblangley.haikalat.subsystems.animation.Pose;
import com.kaleblangley.haikalat.subsystems.animation.PoseBuffer;
import com.kaleblangley.haikalat.subsystems.animation.Skeleton;
import com.kaleblangley.haikalat.subsystems.animation.gltf.GltfAnimationRig;
import com.kaleblangley.haikalat.subsystems.render3d.SceneDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final LinkedHashSet<String> activeAnimationWindows = new LinkedHashSet<>();
    private AnimationController controller;
    private long processedSignalSequence = -1L;
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

    /** Returns the immutable skeleton used by clips when constructing an AnimationGraph. */
    public Skeleton animationSkeleton() {
        ensureOpen();
        return rig.skeleton();
    }

    public AnimationClip animationClip(int animationIndex) {
        ensureOpen();
        return rig.clips().get(animationIndex);
    }

    /** Builds a no-transition graph exposing every imported animation as a state. */
    public AnimationGraph createAnimationGraph(String name) {
        ensureOpen();
        if (rig.clips().isEmpty()) {
            throw new IllegalStateException("glTF asset contains no animations");
        }
        AnimationGraph.Builder builder = AnimationGraph.builder(name, rig.skeleton());
        for (AnimationClip clip : rig.clips()) {
            builder.state(clip.name(), new ClipMotion(clip), AnimationPlayer.LoopMode.LOOP);
        }
        builder.entry(rig.clips().getFirst().name());
        return builder.build();
    }

    /**
     * Attaches a graph-driven controller to this instance.
     *
     * <p>The graph must use {@link #animationSkeleton()} and its motions should normally be
     * {@code ClipMotion} instances created from {@link #animationClip(int)}. Once attached,
     * {@link #update(float)} evaluates the graph. Calling a direct {@link #play(int,
     * AnimationPlayer.LoopMode)} API later detaches the controller and returns to simple
     * playback.</p>
     */
    public GltfSceneInstance attachAnimationGraph(AnimationGraph graph) {
        ensureOpen();
        Objects.requireNonNull(graph, "graph");
        if (graph.skeleton() != rig.skeleton()) {
            throw new IllegalArgumentException(
                    "AnimationGraph skeleton must be the instance animationSkeleton()");
        }
        if (graph.morphTargetCount() > 0 && morphWeights.size() != 1) {
            throw new IllegalArgumentException(
                    "graph-driven morph output currently requires exactly one glTF morph node");
        }
        detachAnimationGraph();
        controller = graph.createController();
        currentAnimationIndex = -1;
        controller.update(0.0f, pose);
        processAnimationWindows();
        copyControllerMorphWeights();
        refreshPoseDependents();
        return this;
    }

    public boolean hasAnimationController() {
        ensureOpen();
        return controller != null;
    }

    public AnimationController animationController() {
        ensureOpen();
        if (controller == null) {
            throw new IllegalStateException("no AnimationGraph controller is attached");
        }
        return controller;
    }

    public GltfSceneInstance detachAnimationGraph() {
        ensureOpen();
        closeAnimationController();
        return this;
    }

    private void closeAnimationController() {
        if (controller != null) {
            controller.close();
            controller = null;
            processedSignalSequence = -1L;
            activeAnimationWindows.clear();
        }
    }

    public GltfSceneInstance setBoolean(String name, boolean value) {
        animationController().setBoolean(name, value);
        return this;
    }

    public GltfSceneInstance setFloat(String name, float value) {
        animationController().setFloat(name, value);
        return this;
    }

    public GltfSceneInstance setInteger(String name, int value) {
        animationController().setInteger(name, value);
        return this;
    }

    public GltfSceneInstance fireTrigger(String name) {
        animationController().fireTrigger(name);
        return this;
    }

    public List<AnimationSignal> drainEvents() {
        return animationController().drainSignals();
    }

    public List<AnimationSignal> drainSignals() {
        return drainEvents();
    }

    public List<String> activeAnimationWindows() {
        ensureOpen();
        return List.copyOf(activeAnimationWindows);
    }

    public String currentAnimationState() {
        ensureOpen();
        return controller == null ? currentAnimationIndex < 0 ? "" :
                rig.clips().get(currentAnimationIndex).name() : controller.currentStateName();
    }

    public float currentAnimationNormalizedTime() {
        ensureOpen();
        return controller == null ? player.timeSeconds() /
                Math.max(1.0e-6f, player.clip().map(AnimationClip::durationSeconds).orElse(0.0f))
                : controller.currentNormalizedTime();
    }

    public float transitionWeight() {
        ensureOpen();
        return controller == null ? 1.0f : controller.transitionWeight();
    }

    public String animationTransitionTarget() {
        ensureOpen();
        return controller == null ? "" : controller.targetStateName();
    }

    public String animationTransitionReason() {
        ensureOpen();
        return controller == null ? "direct playback" : controller.lastTransitionReason();
    }

    public GltfSceneInstance play(int animationIndex, AnimationPlayer.LoopMode loopMode) {
        ensureOpen();
        detachAnimationGraph();
        currentAnimationIndex = animationIndex;
        player.play(rig.clips().get(animationIndex), loopMode);
        player.sample(pose);
        resetMorphWeights();
        sampleMorphWeights();
        refreshPoseDependents();
        return this;
    }

    /**
     * Plays disjoint TRS-only glTF animations on one shared timeline.
     *
     * <p>This supports exporters that split a single object animation into one glTF
     * animation per animated node. Overlapping channels and morph-weight animations
     * are rejected instead of applying an order-dependent result.</p>
     */
    public GltfSceneInstance playCombined(List<Integer> animationIndices,
                                          AnimationPlayer.LoopMode loopMode) {
        ensureOpen();
        detachAnimationGraph();
        AnimationClip combined = rig.composePoseClips("combined", animationIndices);
        currentAnimationIndex = -1;
        player.play(combined, loopMode);
        player.sample(pose);
        resetMorphWeights();
        refreshPoseDependents();
        return this;
    }

    public GltfSceneInstance seek(float timeSeconds) {
        ensureOpen();
        if (controller != null) {
            throw new IllegalStateException("seek is unavailable while an AnimationGraph "
                    + "controller is attached; use graph state offsets");
        }
        player.seek(timeSeconds).sample(pose);
        sampleMorphWeights();
        refreshPoseDependents();
        return this;
    }

    /** Advances CPU animation and refreshes node models/palettes; call once per rendered frame. */
    public GltfSceneInstance update(float deltaSeconds) {
        ensureOpen();
        if (controller != null) {
            controller.update(deltaSeconds, pose);
            processAnimationWindows();
            copyControllerMorphWeights();
        } else {
            player.update(deltaSeconds, pose);
            sampleMorphWeights();
        }
        refreshPoseDependents();
        return this;
    }

    public float animationTimeSeconds() {
        ensureOpen();
        return controller == null ? player.timeSeconds() : controller.currentTimeSeconds();
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
        RuntimeException failure = null;
        try {
            closeAnimationController();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        failure = closeBindings(failure);
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
        return closeBindings(null);
    }

    private RuntimeException closeBindings(RuntimeException failure) {
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

    private void copyControllerMorphWeights() {
        if (controller == null || controller.morphTargetCount() == 0) return;
        controller.copyMorphWeights(morphWeights.values().iterator().next());
    }

    private void processAnimationWindows() {
        for (AnimationSignal signal : controller.pendingSignals()) {
            if (signal.sequence() <= processedSignalSequence) continue;
            processedSignalSequence = signal.sequence();
            if (signal.type() != AnimationSignal.Type.MARKER) continue;
            String marker = signal.name();
            if (marker.endsWith("_start")) {
                activeAnimationWindows.add(marker.substring(0, marker.length() - 6));
            } else if (marker.endsWith("_end")) {
                activeAnimationWindows.remove(marker.substring(0, marker.length() - 4));
            } else if (marker.endsWith("_open")) {
                activeAnimationWindows.add(marker.substring(0, marker.length() - 5));
            } else if (marker.endsWith("_close")) {
                activeAnimationWindows.remove(marker.substring(0, marker.length() - 6));
            }
        }
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
