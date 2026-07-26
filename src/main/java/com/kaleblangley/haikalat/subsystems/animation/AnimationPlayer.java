package com.kaleblangley.haikalat.subsystems.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 单动画片段的确定性播放游标。 */
public final class AnimationPlayer {
    private static final long MAX_EVENT_CYCLES_PER_UPDATE = 4_096L;

    private final Skeleton skeleton;
    private final ArrayList<AnimationEvent> pendingEvents = new ArrayList<>();
    private final PoseBuffer rootMotionFirst;
    private final PoseBuffer rootMotionSecond;
    private AnimationClip clip;
    private LoopMode loopMode = LoopMode.LOOP;
    private float timeSeconds;
    private float playbackSpeed = 1.0f;
    private boolean playing;

    public AnimationPlayer(Skeleton skeleton) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        rootMotionFirst = skeleton.createPoseBuffer();
        rootMotionSecond = skeleton.createPoseBuffer();
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public Optional<AnimationClip> clip() {
        return Optional.ofNullable(clip);
    }

    public LoopMode loopMode() {
        return loopMode;
    }

    public float timeSeconds() {
        return timeSeconds;
    }

    public float playbackSpeed() {
        return playbackSpeed;
    }

    public boolean isPlaying() {
        return playing;
    }

    public AnimationPlayer play(AnimationClip clip) {
        return play(clip, LoopMode.LOOP);
    }

    public AnimationPlayer play(AnimationClip clip, LoopMode loopMode) {
        AnimationClip next = Objects.requireNonNull(clip, "clip");
        if (next.skeleton() != skeleton) {
            throw new IllegalArgumentException("clip belongs to a different skeleton");
        }
        this.clip = next;
        this.loopMode = Objects.requireNonNull(loopMode, "loopMode");
        timeSeconds = playbackSpeed < 0.0f ? next.durationSeconds() : 0.0f;
        playing = next.durationSeconds() > 0.0f;
        pendingEvents.clear();
        appendEventsForward(timeSeconds, timeSeconds, true);
        return this;
    }

    public AnimationPlayer pause() {
        playing = false;
        return this;
    }

    public AnimationPlayer resume() {
        if (clip != null && clip.durationSeconds() > 0.0f
                && (loopMode == LoopMode.LOOP
                || playbackSpeed >= 0.0f && timeSeconds < clip.durationSeconds()
                || playbackSpeed < 0.0f && timeSeconds > 0.0f)) {
            playing = true;
        }
        return this;
    }

    public AnimationPlayer stop() {
        playing = false;
        timeSeconds = 0.0f;
        pendingEvents.clear();
        return this;
    }

    public AnimationPlayer seek(float timeSeconds) {
        requireFiniteNonNegative(timeSeconds, "timeSeconds");
        float duration = clip == null ? 0.0f : clip.durationSeconds();
        this.timeSeconds = Math.min(timeSeconds, duration);
        pendingEvents.clear();
        if (clip != null && loopMode == LoopMode.ONCE && this.timeSeconds >= duration) {
            playing = false;
        }
        return this;
    }

    public AnimationPlayer playbackSpeed(float playbackSpeed) {
        if (!Float.isFinite(playbackSpeed)) {
            throw new IllegalArgumentException("playbackSpeed must be finite");
        }
        this.playbackSpeed = playbackSpeed;
        return this;
    }

    /** 推进播放游标并把当前姿态写入目标缓冲。 */
    public void update(float deltaSeconds, PoseBuffer destination) {
        requireFiniteNonNegative(deltaSeconds, "deltaSeconds");
        requireDestination(destination);
        if (clip == null) {
            destination.resetToBindPose();
            return;
        }
        if (playing) {
            Advance advance = advance(deltaSeconds);
            dispatchEvents(advance);
        }
        clip.sample(timeSeconds, destination);
    }

    /**
     * 推进并提取根关节的模型空间运动。removeFromPose 用于原地播放动画。
     */
    public RootMotionDelta updateWithRootMotion(float deltaSeconds, PoseBuffer destination,
                                                int rootJointIndex, boolean removeFromPose) {
        requireFiniteNonNegative(deltaSeconds, "deltaSeconds");
        requireDestination(destination);
        requireRootJoint(rootJointIndex);
        if (clip == null) {
            destination.resetToBindPose();
            return RootMotionDelta.identity();
        }

        Advance advance = playing
                ? advance(deltaSeconds)
                : new Advance(timeSeconds, timeSeconds, 0L);
        if (playing || advance.completedLoops() > 0L || advance.previousTime() != advance.currentTime()) {
            dispatchEvents(advance);
        }
        clip.sample(timeSeconds, destination);
        RootMotionDelta delta = rootMotion(advance, rootJointIndex);
        if (removeFromPose) {
            JointTransform sampled = destination.localTransform(rootJointIndex);
            JointTransform bind = skeleton.joint(rootJointIndex).bindTransform();
            destination.setLocalTransform(rootJointIndex, new JointTransform(
                    bind.translation(), bind.rotation(), sampled.scale()));
        }
        return delta;
    }

    /** 不推进时间，只把当前姿态写入目标缓冲。 */
    public void sample(PoseBuffer destination) {
        requireDestination(destination);
        if (clip == null) {
            destination.resetToBindPose();
        } else {
            clip.sample(timeSeconds, destination);
        }
    }

    public List<AnimationEvent> pendingEvents() {
        return List.copyOf(pendingEvents);
    }

    public List<AnimationEvent> drainEvents() {
        List<AnimationEvent> result = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return result;
    }

    private Advance advance(float deltaSeconds) {
        float duration = clip.durationSeconds();
        float previous = timeSeconds;
        double advanced = timeSeconds + (double) deltaSeconds * playbackSpeed;
        if (advanced == timeSeconds) {
            return new Advance(previous, previous, 0L);
        }
        long completedLoops = 0L;
        if (loopMode == LoopMode.LOOP) {
            double loopCount = duration == 0.0f ? 0.0 : Math.floor(advanced / duration);
            completedLoops = loopCount >= Long.MAX_VALUE ? Long.MAX_VALUE
                    : loopCount <= Long.MIN_VALUE ? Long.MIN_VALUE : (long) loopCount;
            if (!clip.events().isEmpty()
                    && absoluteCycles(completedLoops) > MAX_EVENT_CYCLES_PER_UPDATE) {
                throw new IllegalArgumentException("update crosses more than "
                        + MAX_EVENT_CYCLES_PER_UPDATE + " event cycles");
            }
            timeSeconds = duration == 0.0f ? 0.0f
                    : (float) (advanced - Math.floor(advanced / duration) * duration);
        } else if (advanced >= duration) {
            timeSeconds = duration;
            playing = false;
        } else if (advanced <= 0.0) {
            timeSeconds = 0.0f;
            playing = false;
        } else {
            timeSeconds = (float) advanced;
        }
        return new Advance(previous, timeSeconds, completedLoops);
    }

    private void dispatchEvents(Advance advance) {
        if (clip.events().isEmpty()) return;
        if (advance.completedLoops() == 0L) {
            if (advance.currentTime() > advance.previousTime()) {
                appendEventsForward(advance.previousTime(), advance.currentTime(), false);
            } else if (advance.currentTime() < advance.previousTime()) {
                appendEventsReverse(advance.previousTime(), advance.currentTime(), false);
            }
            return;
        }
        if (advance.completedLoops() > 0L) {
            appendEventsForward(advance.previousTime(), clip.durationSeconds(), false);
            for (long loop = 1L; loop < advance.completedLoops(); loop++) {
                appendEventsForward(0.0f, clip.durationSeconds(), true);
            }
            appendEventsForward(0.0f, advance.currentTime(), true);
        } else {
            long cycles = absoluteCycles(advance.completedLoops());
            appendEventsReverse(advance.previousTime(), 0.0f, false);
            for (long loop = 1L; loop < cycles; loop++) {
                appendEventsReverse(clip.durationSeconds(), 0.0f, true);
            }
            appendEventsReverse(clip.durationSeconds(), advance.currentTime(), true);
        }
    }

    private void appendEventsForward(float start, float end, boolean includeStart) {
        if (clip == null) return;
        for (AnimationEvent event : clip.events()) {
            float time = event.timeSeconds();
            if ((includeStart ? time >= start : time > start) && time <= end) {
                pendingEvents.add(event);
            }
        }
    }

    private void appendEventsReverse(float start, float end, boolean includeStart) {
        if (clip == null) return;
        List<AnimationEvent> events = clip.events();
        int groupEnd = events.size();
        while (groupEnd > 0) {
            float time = events.get(groupEnd - 1).timeSeconds();
            int groupStart = groupEnd - 1;
            while (groupStart > 0
                    && events.get(groupStart - 1).timeSeconds() == time) {
                groupStart--;
            }
            if ((includeStart ? time <= start : time < start) && time >= end) {
                for (int index = groupStart; index < groupEnd; index++) {
                    pendingEvents.add(events.get(index));
                }
            }
            groupEnd = groupStart;
        }
    }

    private RootMotionDelta rootMotion(Advance advance, int rootJointIndex) {
        if (advance.completedLoops() == 0L) {
            return rootMotionSegment(rootJointIndex,
                    advance.previousTime(), advance.currentTime());
        }
        if (advance.completedLoops() > 0L) {
            RootMotionDelta result = rootMotionSegment(rootJointIndex,
                    advance.previousTime(), clip.durationSeconds());
            if (advance.completedLoops() > 1L) {
                RootMotionDelta cycle = rootMotionSegment(rootJointIndex,
                        0.0f, clip.durationSeconds());
                result = result.then(cycle.repeated(advance.completedLoops() - 1L));
            }
            return result.then(rootMotionSegment(rootJointIndex, 0.0f, advance.currentTime()));
        }
        long cycles = absoluteCycles(advance.completedLoops());
        RootMotionDelta result = rootMotionSegment(rootJointIndex,
                advance.previousTime(), 0.0f);
        if (cycles > 1L) {
            RootMotionDelta cycle = rootMotionSegment(rootJointIndex,
                    clip.durationSeconds(), 0.0f);
            result = result.then(cycle.repeated(cycles - 1L));
        }
        return result.then(rootMotionSegment(rootJointIndex,
                clip.durationSeconds(), advance.currentTime()));
    }

    private RootMotionDelta rootMotionSegment(int rootJointIndex, float firstTime, float secondTime) {
        if (firstTime == secondTime) return RootMotionDelta.identity();
        clip.sample(firstTime, rootMotionFirst);
        clip.sample(secondTime, rootMotionSecond);
        return RootMotionDelta.between(rootMotionFirst.localTransform(rootJointIndex),
                rootMotionSecond.localTransform(rootJointIndex));
    }

    private void requireRootJoint(int rootJointIndex) {
        if (rootJointIndex < 0 || rootJointIndex >= skeleton.jointCount()) {
            throw new IndexOutOfBoundsException("root joint index is outside skeleton");
        }
        if (skeleton.joint(rootJointIndex).parentIndex() >= 0) {
            throw new IllegalArgumentException("root motion joint must be a skeleton root");
        }
    }

    private void requireDestination(PoseBuffer destination) {
        PoseBuffer target = Objects.requireNonNull(destination, "destination");
        if (target.skeleton() != skeleton) {
            throw new IllegalArgumentException("destination belongs to a different skeleton");
        }
    }

    private static void requireFiniteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static long absoluteCycles(long cycles) {
        return cycles == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(cycles);
    }

    public enum LoopMode {
        ONCE,
        LOOP
    }

    private record Advance(float previousTime, float currentTime, long completedLoops) {
    }
}
