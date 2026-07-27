package com.kaleblangley.haikalat.subsystems.animation.gltf;

import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.AnimationMarker;
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
    private final List<LoadedGltfScene.AnimationDef> animations;
    private final List<Map<Integer, MorphWeightTrack>> morphTracks;

    private GltfAnimationRig(Skeleton skeleton, List<Skin> skins,
                             List<AnimationClip> clips,
                             List<LoadedGltfScene.AnimationDef> animations,
                             List<Map<Integer, MorphWeightTrack>> morphTracks) {
        this.skeleton = skeleton;
        this.skins = List.copyOf(skins);
        this.clips = List.copyOf(clips);
        this.animations = List.copyOf(animations);
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
            addMarkers(builder, animation.markers());
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
        return new GltfAnimationRig(skeleton, skins, clips, scene.animations(), morphTracks);
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

    /**
     * Composes animations whose TRS targets do not overlap into one synchronized clip.
     *
     * <p>Some DCC exporters emit one glTF animation per object action even though the
     * actions share a timeline. Morph-weight animations remain separate because their
     * instance-owned buffers are sampled outside {@link AnimationClip}.</p>
     */
    public AnimationClip composePoseClips(String name, List<Integer> animationIndices) {
        List<Integer> indices = List.copyOf(
                Objects.requireNonNull(animationIndices, "animationIndices"));
        if (indices.isEmpty()) {
            throw new IllegalArgumentException("animationIndices must not be empty");
        }
        AnimationClip.Builder builder = AnimationClip.builder(name, skeleton);
        for (int animationIndex : indices) {
            LoadedGltfScene.AnimationDef animation = animations.get(animationIndex);
            builder.durationSeconds(animation.durationSeconds());
            addMarkers(builder, animation.markers());
            for (LoadedGltfScene.AnimationChannelDef channel : animation.channels()) {
                if (channel.path() == LoadedGltfScene.AnimationTargetPath.WEIGHTS) {
                    throw new IllegalArgumentException("animation[" + animationIndex
                            + "] contains morph weights and cannot be composed as a pose clip");
                }
                addChannel(builder, channel);
            }
        }
        return builder.build();
    }

    private static void addMarkers(AnimationClip.Builder builder,
                                   List<LoadedGltfScene.AnimationMarkerDef> markers) {
        for (LoadedGltfScene.AnimationMarkerDef marker : markers) {
            builder.marker(marker.timeSeconds(), marker.name(), markerPriority(marker.priority()));
        }
    }

    private static AnimationMarker.Priority markerPriority(String value) {
        if (value == null) return AnimationMarker.Priority.NORMAL;
        try {
            return AnimationMarker.Priority.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AnimationMarker.Priority.NORMAL;
        }
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
