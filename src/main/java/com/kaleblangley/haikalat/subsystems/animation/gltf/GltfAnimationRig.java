package com.kaleblangley.haikalat.subsystems.animation.gltf;

import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.JointTransform;
import com.kaleblangley.haikalat.subsystems.animation.MorphWeightTrack;
import com.kaleblangley.haikalat.subsystems.animation.Skeleton;
import com.kaleblangley.haikalat.subsystems.animation.Skin;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Adapts decoded glTF node/skin/channel data to the GL-free animation subsystem. */
public final class GltfAnimationRig {
    private final Skeleton skeleton;
    private final List<Skin> skins;
    private final List<AnimationClip> clips;
    private final List<Map<Integer, MorphWeightTrack>> morphTracks;

    private GltfAnimationRig(Skeleton skeleton, List<Skin> skins,
                             List<AnimationClip> clips,
                             List<Map<Integer, MorphWeightTrack>> morphTracks) {
        this.skeleton = skeleton;
        this.skins = List.copyOf(skins);
        this.clips = List.copyOf(clips);
        this.morphTracks = morphTracks.stream().map(Map::copyOf).toList();
    }

    public static GltfAnimationRig from(LoadedGltfScene source) {
        LoadedGltfScene scene = Objects.requireNonNull(source, "source");
        if (scene.nodeRigs().size() != scene.nodes().size()) {
            throw new IllegalArgumentException("glTF node rig data is incomplete");
        }
        List<Skeleton.Joint> joints = new ArrayList<>(scene.nodes().size());
        for (LoadedGltfScene.NodeRigDef node : scene.nodeRigs()) {
            if (node.matrixAuthored()) {
                throw new IllegalArgumentException("nodes[" + node.nodeIndex()
                        + "] uses matrix; animated runtime currently requires TRS nodes");
            }
            joints.add(new Skeleton.Joint(scene.nodes().get(node.nodeIndex()).name(),
                    node.parentIndex(), new JointTransform(
                    node.translation(), node.rotation(), node.scale())));
        }
        Skeleton skeleton = new Skeleton(joints);

        List<Skin> skins = new ArrayList<>(scene.skins().size());
        for (LoadedGltfScene.SkinDef skin : scene.skins()) {
            int[] mappedJoints = skin.joints().stream().mapToInt(Integer::intValue).toArray();
            skins.add(new Skin(skin.name(), skeleton, mappedJoints,
                    skin.inverseBindMatrices()));
        }

        List<AnimationClip> clips = new ArrayList<>(scene.animations().size());
        List<Map<Integer, MorphWeightTrack>> morphTracks =
                new ArrayList<>(scene.animations().size());
        for (LoadedGltfScene.AnimationDef animation : scene.animations()) {
            AnimationClip.Builder builder = AnimationClip.builder(animation.name(), skeleton)
                    .durationSeconds(animation.durationSeconds());
            Map<Integer, MorphWeightTrack> animationMorphTracks = new LinkedHashMap<>();
            for (LoadedGltfScene.AnimationChannelDef channel : animation.channels()) {
                if (channel.path() == LoadedGltfScene.AnimationTargetPath.WEIGHTS) {
                    animationMorphTracks.put(channel.nodeIndex(), morphTrack(channel));
                } else {
                    addChannel(builder, channel);
                }
            }
            clips.add(builder.build());
            morphTracks.add(Map.copyOf(animationMorphTracks));
        }
        return new GltfAnimationRig(skeleton, skins, clips, morphTracks);
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public List<Skin> skins() {
        return skins;
    }

    public List<AnimationClip> clips() {
        return clips;
    }

    public Optional<MorphWeightTrack> morphWeightTrack(int animationIndex, int nodeIndex) {
        return Optional.ofNullable(morphTracks.get(animationIndex).get(nodeIndex));
    }

    public Map<Integer, MorphWeightTrack> morphWeightTracks(int animationIndex) {
        return morphTracks.get(animationIndex);
    }

    private static void addChannel(AnimationClip.Builder builder,
                                   LoadedGltfScene.AnimationChannelDef channel) {
        float[] times = channel.timesSeconds();
        float[] values = channel.values();
        int node = channel.nodeIndex();
        if (channel.interpolation() == LoadedGltfScene.AnimationInterpolation.CUBIC_SPLINE) {
            addCubicChannel(builder, node, channel.path(), times, values);
            return;
        }
        AnimationClip.Interpolation interpolation = channel.interpolation()
                == LoadedGltfScene.AnimationInterpolation.STEP
                ? AnimationClip.Interpolation.STEP : AnimationClip.Interpolation.LINEAR;
        switch (channel.path()) {
            case TRANSLATION -> builder.translation(node, interpolation, times,
                    vectors(values, times.length, 1, 0));
            case SCALE -> builder.scale(node, interpolation, times,
                    vectors(values, times.length, 1, 0));
            case ROTATION -> builder.rotation(node, interpolation, times,
                    rotations(values, times.length, 1, 0));
            case WEIGHTS -> throw new IllegalArgumentException(
                    "morph weight channels must use morphTrack");
        }
    }

    private static void addCubicChannel(AnimationClip.Builder builder, int node,
                                        LoadedGltfScene.AnimationTargetPath path,
                                        float[] times, float[] values) {
        switch (path) {
            case TRANSLATION -> builder.translationCubic(node, times,
                    vectors(values, times.length, 3, 0),
                    vectors(values, times.length, 3, 1),
                    vectors(values, times.length, 3, 2));
            case SCALE -> builder.scaleCubic(node, times,
                    vectors(values, times.length, 3, 0),
                    vectors(values, times.length, 3, 1),
                    vectors(values, times.length, 3, 2));
            case ROTATION -> builder.rotationCubic(node, times,
                    rotations(values, times.length, 3, 0),
                    rotations(values, times.length, 3, 1),
                    rotations(values, times.length, 3, 2));
            case WEIGHTS -> throw new IllegalArgumentException(
                    "morph weight channels must use morphTrack");
        }
    }

    private static MorphWeightTrack morphTrack(
            LoadedGltfScene.AnimationChannelDef channel) {
        MorphWeightTrack.Interpolation interpolation = switch (channel.interpolation()) {
            case STEP -> MorphWeightTrack.Interpolation.STEP;
            case LINEAR -> MorphWeightTrack.Interpolation.LINEAR;
            case CUBIC_SPLINE -> MorphWeightTrack.Interpolation.CUBIC_SPLINE;
        };
        return new MorphWeightTrack(channel.componentCount(), interpolation,
                channel.timesSeconds(), channel.values());
    }

    private static Vector3f[] vectors(float[] values, int keyframes,
                                      int tuplesPerKeyframe, int tuple) {
        Vector3f[] result = new Vector3f[keyframes];
        for (int key = 0; key < keyframes; key++) {
            int offset = (key * tuplesPerKeyframe + tuple) * 3;
            result[key] = new Vector3f(values[offset], values[offset + 1], values[offset + 2]);
        }
        return result;
    }

    private static Quaternionf[] rotations(float[] values, int keyframes,
                                           int tuplesPerKeyframe, int tuple) {
        Quaternionf[] result = new Quaternionf[keyframes];
        for (int key = 0; key < keyframes; key++) {
            int offset = (key * tuplesPerKeyframe + tuple) * 4;
            result[key] = new Quaternionf(values[offset], values[offset + 1],
                    values[offset + 2], values[offset + 3]);
        }
        return result;
    }
}
