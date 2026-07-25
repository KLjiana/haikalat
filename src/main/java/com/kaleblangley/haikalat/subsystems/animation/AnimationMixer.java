package com.kaleblangley.haikalat.subsystems.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 双层动画混合器，支持归一化时间同步与 Bone Mask。 */
public final class AnimationMixer {
    private final Skeleton skeleton;
    private final AnimationPlayer basePlayer;
    private final AnimationPlayer layerPlayer;
    private final PoseBuffer basePose;
    private final PoseBuffer layerPose;
    private BoneMask layerMask;
    private float layerWeight = 1.0f;
    private boolean layerEnabled;
    private boolean synchronizeLayer;

    public AnimationMixer(Skeleton skeleton) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        basePlayer = new AnimationPlayer(skeleton);
        layerPlayer = new AnimationPlayer(skeleton);
        basePose = skeleton.createPoseBuffer();
        layerPose = skeleton.createPoseBuffer();
        layerMask = BoneMask.all(skeleton);
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public AnimationPlayer basePlayer() {
        return basePlayer;
    }

    public AnimationPlayer layerPlayer() {
        return layerPlayer;
    }

    public AnimationMixer playBase(AnimationClip clip, AnimationPlayer.LoopMode loopMode) {
        basePlayer.play(requireClip(clip), loopMode);
        return this;
    }

    public AnimationMixer playLayer(AnimationClip clip, AnimationPlayer.LoopMode loopMode) {
        layerPlayer.play(requireClip(clip), loopMode);
        layerEnabled = true;
        return this;
    }

    public AnimationMixer clearLayer() {
        layerPlayer.stop();
        layerEnabled = false;
        return this;
    }

    public AnimationMixer layerWeight(float weight) {
        BoneMask.requireWeight(weight);
        layerWeight = weight;
        return this;
    }

    public float layerWeight() {
        return layerWeight;
    }

    public AnimationMixer layerMask(BoneMask mask) {
        BoneMask value = Objects.requireNonNull(mask, "mask");
        if (value.skeleton() != skeleton) {
            throw new IllegalArgumentException("mask belongs to a different skeleton");
        }
        layerMask = value;
        return this;
    }

    public AnimationMixer synchronizeLayer(boolean enabled) {
        synchronizeLayer = enabled;
        return this;
    }

    public boolean isLayerSynchronized() {
        return synchronizeLayer;
    }

    public void update(float deltaSeconds, PoseBuffer destination) {
        requireDestination(destination);
        basePlayer.update(deltaSeconds, basePose);
        finishLayer(deltaSeconds, destination);
    }

    public RootMotionDelta updateWithRootMotion(float deltaSeconds, PoseBuffer destination,
                                                int rootJointIndex, boolean removeFromPose) {
        requireDestination(destination);
        RootMotionDelta delta = basePlayer.updateWithRootMotion(deltaSeconds, basePose,
                rootJointIndex, removeFromPose);
        finishLayer(deltaSeconds, destination);
        return delta;
    }

    public List<AnimationEvent> drainEvents() {
        ArrayList<AnimationEvent> events = new ArrayList<>(basePlayer.drainEvents());
        events.addAll(layerPlayer.drainEvents());
        return List.copyOf(events);
    }

    private void finishLayer(float deltaSeconds, PoseBuffer destination) {
        if (!layerEnabled) {
            destination.load(basePose.snapshot());
            return;
        }
        if (synchronizeLayer) {
            float normalized = normalizedTime(basePlayer);
            float duration = layerPlayer.clip().map(AnimationClip::durationSeconds).orElse(0.0f);
            layerPlayer.seek(normalized * duration).sample(layerPose);
        } else {
            layerPlayer.update(deltaSeconds, layerPose);
        }
        PoseBlender.blend(basePose, layerPose, layerWeight, layerMask, destination);
    }

    private AnimationClip requireClip(AnimationClip clip) {
        AnimationClip value = Objects.requireNonNull(clip, "clip");
        if (value.skeleton() != skeleton) {
            throw new IllegalArgumentException("clip belongs to a different skeleton");
        }
        return value;
    }

    private void requireDestination(PoseBuffer destination) {
        PoseBuffer value = Objects.requireNonNull(destination, "destination");
        if (value.skeleton() != skeleton) {
            throw new IllegalArgumentException("destination belongs to a different skeleton");
        }
    }

    private static float normalizedTime(AnimationPlayer player) {
        float duration = player.clip().map(AnimationClip::durationSeconds).orElse(0.0f);
        return duration == 0.0f ? 0.0f : player.timeSeconds() / duration;
    }
}
