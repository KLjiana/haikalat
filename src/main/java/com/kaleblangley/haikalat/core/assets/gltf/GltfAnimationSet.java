package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable animation-only view of a glTF/GLB asset.
 *
 * <p>The view deliberately retains no mesh, material, image or sampler data. Animation
 * channel arrays are shared with the decoded source definitions; binding a set therefore
 * creates only a scene wrapper and never copies keyframes.</p>
 */
public final class GltfAnimationSet {
    private final AssetRef source;
    private final List<RigNode> nodes;
    private final List<RigSkin> skins;
    private final List<LoadedGltfScene.AnimationDef> animations;
    private final Map<String, LoadedGltfScene.AnimationDef> byName;
    private final long decodedBufferBytes;

    private GltfAnimationSet(AssetRef source, List<RigNode> nodes,
                             List<RigSkin> skins,
                             List<LoadedGltfScene.AnimationDef> animations,
                             long decodedBufferBytes) {
        this.source = Objects.requireNonNull(source, "source");
        this.nodes = List.copyOf(nodes);
        this.skins = List.copyOf(skins);
        this.animations = List.copyOf(animations);
        this.decodedBufferBytes = requireNonNegative(decodedBufferBytes,
                "decodedBufferBytes");
        LinkedHashMap<String, LoadedGltfScene.AnimationDef> names = new LinkedHashMap<>();
        for (LoadedGltfScene.AnimationDef animation : this.animations) {
            if (names.putIfAbsent(animation.name(), animation) != null) {
                throw new IllegalArgumentException("duplicate animation name "
                        + animation.name());
            }
        }
        this.byName = Map.copyOf(names);
    }

    static GltfAnimationSet from(LoadedGltfScene source) {
        Objects.requireNonNull(source, "source");
        if (source.animations().isEmpty()) {
            throw new IllegalArgumentException("animation source contains no animations");
        }
        List<RigNode> nodes = new ArrayList<>(source.nodes().size());
        for (LoadedGltfScene.Node node : source.nodes()) {
            LoadedGltfScene.NodeRigDef rig = source.nodeRigs().get(node.index());
            nodes.add(RigNode.from(node, rig));
        }
        List<RigSkin> skins = source.skins().stream().map(RigSkin::from).toList();
        return new GltfAnimationSet(source.source(), nodes, skins, source.animations(),
                source.statistics().decodedBufferBytes());
    }

    /** Source GLB/glTF reference used to create this immutable set. */
    public AssetRef source() {
        return source;
    }

    /** Number of nodes in the source rig. */
    public int nodeCount() {
        return nodes.size();
    }

    /** Names of clips in stable source order. */
    public List<String> animationNames() {
        return animations.stream().map(LoadedGltfScene.AnimationDef::name).toList();
    }

    /** Immutable source animation definitions; channel arrays are not duplicated on bind. */
    public List<LoadedGltfScene.AnimationDef> animations() {
        return animations;
    }

    /** Returns one source clip by exact name. */
    public LoadedGltfScene.AnimationDef animation(String name) {
        Objects.requireNonNull(name, "name");
        LoadedGltfScene.AnimationDef result = byName.get(name);
        if (result == null) {
            throw new IllegalArgumentException("animation set contains no clip named '"
                    + name + "'");
        }
        return result;
    }

    /** Decoded source bytes, excluding mesh/image data which this view does not retain. */
    public long decodedBufferBytes() {
        return decodedBufferBytes;
    }

    /** Binds every clip to a compatible model. */
    public LoadedGltfScene bind(LoadedGltfScene model) {
        return bind(model, animationNames());
    }

    /**
     * Binds selected clips by exact source name. The returned model owns no copy of the
     * keyframe arrays and can be uploaded/instantiated through the existing APIs.
     */
    public LoadedGltfScene bind(LoadedGltfScene model, Collection<String> clipNames) {
        Objects.requireNonNull(model, "model");
        List<String> names = List.copyOf(Objects.requireNonNull(clipNames, "clipNames"));
        if (names.isEmpty()) throw new IllegalArgumentException("clipNames must not be empty");
        validateCompatible(model);
        Set<String> selected = new HashSet<>();
        List<LoadedGltfScene.AnimationDef> selectedAnimations = new ArrayList<>(names.size());
        for (String name : names) {
            if (!selected.add(Objects.requireNonNull(name, "clip name"))) {
                throw new IllegalArgumentException("duplicate selected animation name " + name);
            }
            selectedAnimations.add(animation(name));
        }
        Set<String> modelNames = new HashSet<>();
        for (LoadedGltfScene.AnimationDef animation : model.animations()) {
            if (!modelNames.add(animation.name())) {
                throw new IllegalArgumentException("model contains duplicate animation name "
                        + animation.name());
            }
            if (selected.contains(animation.name())) {
                throw new IllegalArgumentException("model already contains animation name "
                        + animation.name());
            }
        }
        return model.withExternalAnimations(selectedAnimations, List.of(),
                decodedBufferBytes);
    }

    /** Validates the strict node/skin contract without allocating a bound scene. */
    void validateCompatible(LoadedGltfScene model) {
        Objects.requireNonNull(model, "model");
        if (model.nodes().size() != nodes.size()
                || model.nodeRigs().size() != nodes.size()) {
            throw incompatible("node count differs: source=" + nodes.size()
                    + ", model=" + model.nodes().size());
        }
        Map<String, Integer> modelNames = uniqueNodeNames(model, "model");
        Map<String, Integer> sourceNames = uniqueNodeNames(nodes, "animation source");
        if (!sourceNames.keySet().equals(modelNames.keySet())) {
            throw incompatible("node name set differs");
        }
        for (int index = 0; index < nodes.size(); index++) {
            RigNode expected = nodes.get(index);
            LoadedGltfScene.Node actualNode = model.nodes().get(index);
            LoadedGltfScene.NodeRigDef actual = model.nodeRigs().get(index);
            if (!expected.name().equals(actualNode.name())
                    || expected.parentIndex() != actual.parentIndex()
                    || expected.matrixAuthored() != actual.matrixAuthored()
                    || !same(expected.translation(), actual.translation())
                    || !same(expected.rotation(), actual.rotation())
                    || !same(expected.scale(), actual.scale())) {
                throw incompatible("node rig differs at index " + index + " ('"
                        + expected.name() + "')");
            }
        }
        if (model.skins().size() != skins.size()) {
            throw incompatible("skin count differs: source=" + skins.size()
                    + ", model=" + model.skins().size());
        }
        for (int index = 0; index < skins.size(); index++) {
            if (!skins.get(index).compatibleWith(model.skins().get(index))) {
                throw incompatible("skin contract differs at index " + index);
            }
        }
    }

    private static Map<String, Integer> uniqueNodeNames(LoadedGltfScene model, String owner) {
        Map<String, Integer> result = new HashMap<>();
        for (LoadedGltfScene.Node node : model.nodes()) {
            if (node.name().isBlank() || result.putIfAbsent(node.name(), node.index()) != null) {
                throw incompatible(owner + " node names must be non-blank and unique");
            }
        }
        return result;
    }

    private static Map<String, Integer> uniqueNodeNames(List<RigNode> source, String owner) {
        Map<String, Integer> result = new HashMap<>();
        for (RigNode node : source) {
            if (node.name().isBlank() || result.putIfAbsent(node.name(), node.index()) != null) {
                throw incompatible(owner + " node names must be non-blank and unique");
            }
        }
        return result;
    }

    private static boolean same(org.joml.Vector3fc left, org.joml.Vector3fc right) {
        return same(left.x(), right.x()) && same(left.y(), right.y()) && same(left.z(), right.z());
    }

    private static boolean same(org.joml.Quaternionfc left, org.joml.Quaternionfc right) {
        return same(left.x(), right.x()) && same(left.y(), right.y())
                && same(left.z(), right.z()) && same(left.w(), right.w());
    }

    private static boolean same(float left, float right) {
        return Float.floatToIntBits(left) == Float.floatToIntBits(right)
                || Math.abs(left - right) <= 1.0e-6f;
    }

    private static IllegalArgumentException incompatible(String message) {
        return new IllegalArgumentException("incompatible glTF animation rig: " + message);
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0L) throw new IllegalArgumentException(name + " must be non-negative");
        return value;
    }

    private record RigNode(int index, String name, int parentIndex,
                           org.joml.Vector3fc translation,
                           org.joml.Quaternionfc rotation,
                           org.joml.Vector3fc scale, boolean matrixAuthored) {
        private static RigNode from(LoadedGltfScene.Node node,
                                    LoadedGltfScene.NodeRigDef rig) {
            return new RigNode(node.index(), node.name(), rig.parentIndex(), rig.translation(),
                    rig.rotation(), rig.scale(), rig.matrixAuthored());
        }
    }

    private record RigSkin(int index, int skeletonRootNode, List<Integer> joints,
                           List<org.joml.Matrix4fc> inverseBindMatrices) {
        private static RigSkin from(LoadedGltfScene.SkinDef skin) {
            return new RigSkin(skin.index(), skin.skeletonRootNode(), skin.joints(),
                    skin.inverseBindMatrices());
        }

        private boolean compatibleWith(LoadedGltfScene.SkinDef other) {
            if (skeletonRootNode != other.skeletonRootNode()
                    || !joints.equals(other.joints())
                    || inverseBindMatrices.size() != other.inverseBindMatrices().size()) {
                return false;
            }
            List<org.joml.Matrix4fc> otherMatrices = other.inverseBindMatrices();
            for (int index = 0; index < inverseBindMatrices.size(); index++) {
                org.joml.Matrix4fc left = inverseBindMatrices.get(index);
                org.joml.Matrix4fc right = otherMatrices.get(index);
                float[] leftValues = new float[16];
                float[] rightValues = new float[16];
                left.get(leftValues);
                right.get(rightValues);
                for (int element = 0; element < leftValues.length; element++) {
                    if (!same(leftValues[element], rightValues[element])) return false;
                }
            }
            return true;
        }
    }
}
