package com.kaleblangley.haikalat.subsystems.animation;

import java.util.Objects;

/** 对现有 AnimationClip 的零复制 motion 包装。 */
public final class ClipMotion implements AnimationMotion {
    private final AnimationClip clip;
    private final AnimationPlayer.LoopMode loopMode;
    private final float playbackSpeed;
    private final MorphWeightTrack morphTrack;

    public ClipMotion(AnimationClip clip) {
        this(clip, AnimationPlayer.LoopMode.LOOP, 1.0f, null);
    }

    public ClipMotion(AnimationClip clip, MorphWeightTrack morphTrack) {
        this(clip, AnimationPlayer.LoopMode.LOOP, 1.0f,
                Objects.requireNonNull(morphTrack, "morphTrack"));
    }

    public ClipMotion(AnimationClip clip, AnimationPlayer.LoopMode loopMode, float playbackSpeed) {
        this(clip, loopMode, playbackSpeed, null);
    }

    public ClipMotion(AnimationClip clip, AnimationPlayer.LoopMode loopMode,
                      float playbackSpeed, MorphWeightTrack morphTrack) {
        this.clip = Objects.requireNonNull(clip, "clip");
        this.loopMode = Objects.requireNonNull(loopMode, "loopMode");
        if (!Float.isFinite(playbackSpeed)) {
            throw new IllegalArgumentException("playbackSpeed must be finite");
        }
        this.playbackSpeed = playbackSpeed;
        this.morphTrack = morphTrack;
    }

    public AnimationClip clip() {
        return clip;
    }

    public AnimationPlayer.LoopMode loopMode() {
        return loopMode;
    }

    public float playbackSpeed() {
        return playbackSpeed;
    }

    public int morphTargetCount() {
        return morphTrack == null ? 0 : morphTrack.targetCount();
    }

    public java.util.Optional<MorphWeightTrack> morphTrack() {
        return java.util.Optional.ofNullable(morphTrack);
    }

    @Override
    public Skeleton skeleton() {
        return clip.skeleton();
    }

    @Override
    public float durationSeconds() {
        return Math.max(clip.durationSeconds(),
                morphTrack == null ? 0.0f : morphTrack.durationSeconds());
    }
}
