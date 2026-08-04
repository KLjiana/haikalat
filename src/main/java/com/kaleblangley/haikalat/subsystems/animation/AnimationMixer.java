package com.kaleblangley.haikalat.subsystems.animation;

import com.kaleblangley.haikalat.core.curve.Curve1f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 双层动画混合器，支持基础动画过渡、层权重过渡、归一化时间同步与 Bone Mask。 */
public final class AnimationMixer {
    private final Skeleton skeleton;
    private final AnimationPlayer layerPlayer;
    private final PoseBuffer basePose;
    private final PoseBuffer transitionSourcePose;
    private final PoseBuffer transitionTargetPose;
    private final PoseBuffer layerPose;
    private final BoneMask baseMask;
    private AnimationPlayer basePlayer;
    private AnimationPlayer transitionPlayer;
    private BoneMask layerMask;
    private Curve1f baseTransitionCurve;
    private float baseTransitionElapsed;
    private float baseTransitionDuration;
    private boolean transitionSourceFrozen;
    private float layerWeight = 1.0f;
    private Curve1f layerFadeCurve;
    private float layerFadeStart;
    private float layerFadeTarget;
    private float layerFadeElapsed;
    private float layerFadeDuration;
    private boolean layerEnabled;
    private boolean synchronizeLayer;

    public AnimationMixer(Skeleton skeleton) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        basePlayer = new AnimationPlayer(skeleton);
        layerPlayer = new AnimationPlayer(skeleton);
        basePose = skeleton.createPoseBuffer();
        transitionSourcePose = skeleton.createPoseBuffer();
        transitionTargetPose = skeleton.createPoseBuffer();
        layerPose = skeleton.createPoseBuffer();
        baseMask = BoneMask.all(skeleton);
        layerMask = baseMask;
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    /** During a base transition this returns the incoming player. */
    public AnimationPlayer basePlayer() {
        return transitionPlayer == null ? basePlayer : transitionPlayer;
    }

    public AnimationPlayer layerPlayer() {
        return layerPlayer;
    }

    /** Immediately switches the base clip and cancels any active base transition. */
    public AnimationMixer playBase(AnimationClip clip, AnimationPlayer.LoopMode loopMode) {
        cancelBaseTransition();
        basePlayer.play(requireClip(clip), Objects.requireNonNull(loopMode, "loopMode"));
        basePlayer.sample(basePose);
        return this;
    }

    /**
     * Cross-fades to an incoming base clip. A zero duration has the same immediate semantics as
     * {@link #playBase(AnimationClip, AnimationPlayer.LoopMode)}.
     */
    public AnimationMixer transitionBase(AnimationClip clip, AnimationPlayer.LoopMode loopMode,
                                           float durationSeconds, Curve1f curve) {
        AnimationClip next = requireClip(clip);
        AnimationPlayer.LoopMode nextLoop = Objects.requireNonNull(loopMode, "loopMode");
        Curve1f easing = Objects.requireNonNull(curve, "curve");
        requireFiniteNonNegative(durationSeconds, "durationSeconds");
        if (durationSeconds == 0.0f) return playBase(next, nextLoop);

        if (transitionPlayer == null) {
            basePlayer.sample(basePose);
            transitionSourceFrozen = false;
        } else {
            refreshCurrentTransitionPose();
            transitionSourcePose.load(basePose);
            transitionSourceFrozen = true;
            transitionPlayer.drainEvents();
        }
        basePlayer.drainEvents();
        transitionPlayer = new AnimationPlayer(skeleton).play(next, nextLoop);
        transitionPlayer.sample(transitionTargetPose);
        baseTransitionCurve = easing;
        baseTransitionElapsed = 0.0f;
        baseTransitionDuration = durationSeconds;
        return this;
    }

    /**
     * Cross-fades from an explicitly sampled pose to an incoming base clip.
     *
     * <p>The source pose is copied and frozen, so callers can bridge from another animation
     * system without constructing a temporary clip. A zero duration keeps the immediate
     * semantics of {@link #playBase(AnimationClip, AnimationPlayer.LoopMode)}.</p>
     */
    public AnimationMixer transitionBaseFromPose(PoseBuffer sourcePose, AnimationClip clip,
                                                   AnimationPlayer.LoopMode loopMode,
                                                   float durationSeconds, Curve1f curve) {
        PoseBuffer source = Objects.requireNonNull(sourcePose, "sourcePose");
        requireDestination(source);
        AnimationClip next = requireClip(clip);
        AnimationPlayer.LoopMode nextLoop = Objects.requireNonNull(loopMode, "loopMode");
        Curve1f easing = Objects.requireNonNull(curve, "curve");
        requireFiniteNonNegative(durationSeconds, "durationSeconds");
        if (durationSeconds == 0.0f) return playBase(next, nextLoop);

        if (transitionPlayer != null) transitionPlayer.drainEvents();
        basePlayer.drainEvents();
        transitionSourcePose.load(source);
        transitionSourceFrozen = true;
        transitionPlayer = new AnimationPlayer(skeleton).play(next, nextLoop);
        transitionPlayer.sample(transitionTargetPose);
        baseTransitionCurve = easing;
        baseTransitionElapsed = 0.0f;
        baseTransitionDuration = durationSeconds;
        return this;
    }

    public boolean isBaseTransitioning() {
        return transitionPlayer != null;
    }

    public float baseTransitionWeight() {
        return transitionPlayer == null ? 1.0f : transitionWeight();
    }

    public AnimationMixer playLayer(AnimationClip clip, AnimationPlayer.LoopMode loopMode) {
        layerPlayer.play(requireClip(clip), loopMode);
        layerEnabled = true;
        return this;
    }

    public AnimationMixer clearLayer() {
        layerPlayer.stop();
        layerEnabled = false;
        cancelLayerFade();
        return this;
    }

    public AnimationMixer layerWeight(float weight) {
        BoneMask.requireWeight(weight);
        layerWeight = weight;
        cancelLayerFade();
        return this;
    }

    public float layerWeight() {
        return layerWeight;
    }

    /** Replaces any active layer fade and starts from the currently sampled weight. */
    public AnimationMixer fadeLayerTo(float targetWeight, float durationSeconds, Curve1f curve) {
        BoneMask.requireWeight(targetWeight);
        requireFiniteNonNegative(durationSeconds, "durationSeconds");
        Curve1f easing = Objects.requireNonNull(curve, "curve");
        if (durationSeconds == 0.0f) return layerWeight(targetWeight);
        layerFadeStart = layerWeight;
        layerFadeTarget = targetWeight;
        layerFadeElapsed = 0.0f;
        layerFadeDuration = durationSeconds;
        layerFadeCurve = easing;
        return this;
    }

    public AnimationMixer fadeOutLayer(float durationSeconds, Curve1f curve) {
        return fadeLayerTo(0.0f, durationSeconds, curve);
    }

    public boolean isLayerFading() {
        return layerFadeCurve != null;
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
        requireFiniteNonNegative(deltaSeconds, "deltaSeconds");
        updateBase(deltaSeconds);
        finishLayer(deltaSeconds, destination);
    }

    public RootMotionDelta updateWithRootMotion(float deltaSeconds, PoseBuffer destination,
                                                int rootJointIndex, boolean removeFromPose) {
        requireDestination(destination);
        requireFiniteNonNegative(deltaSeconds, "deltaSeconds");
        if (transitionPlayer == null) {
            RootMotionDelta delta = basePlayer.updateWithRootMotion(deltaSeconds, basePose,
                    rootJointIndex, false);
            finishLayer(deltaSeconds, destination);
            if (removeFromPose) removeRootFromPose(destination, rootJointIndex);
            return delta;
        }

        RootMotionDelta outgoing = RootMotionDelta.identity();
        if (!transitionSourceFrozen) {
            outgoing = basePlayer.updateWithRootMotion(deltaSeconds, transitionSourcePose,
                    rootJointIndex, false);
            basePlayer.drainEvents();
        }
        RootMotionDelta incoming = transitionPlayer.updateWithRootMotion(deltaSeconds,
                transitionTargetPose, rootJointIndex, false);
        advanceBaseTransition(deltaSeconds);
        float weight = transitionWeight();
        PoseBlender.blend(transitionSourcePose, transitionTargetPose, weight, baseMask, basePose);
        RootMotionDelta result = blendRootMotion(outgoing, incoming, weight);
        finishBaseTransition();
        finishLayer(deltaSeconds, destination);
        if (removeFromPose) removeRootFromPose(destination, rootJointIndex);
        return result;
    }

    /** Outgoing base events are suppressed while incoming and layer events remain observable. */
    public List<AnimationEvent> drainEvents() {
        AnimationPlayer eventSource = transitionPlayer == null ? basePlayer : transitionPlayer;
        ArrayList<AnimationEvent> events = new ArrayList<>(eventSource.drainEvents());
        events.addAll(layerPlayer.drainEvents());
        return List.copyOf(events);
    }

    private void updateBase(float deltaSeconds) {
        if (transitionPlayer == null) {
            basePlayer.update(deltaSeconds, basePose);
            return;
        }
        if (!transitionSourceFrozen) {
            basePlayer.update(deltaSeconds, transitionSourcePose);
            basePlayer.drainEvents();
        }
        transitionPlayer.update(deltaSeconds, transitionTargetPose);
        advanceBaseTransition(deltaSeconds);
        PoseBlender.blend(transitionSourcePose, transitionTargetPose,
                transitionWeight(), baseMask, basePose);
        finishBaseTransition();
    }

    private void refreshCurrentTransitionPose() {
        if (!transitionSourceFrozen) basePlayer.sample(transitionSourcePose);
        transitionPlayer.sample(transitionTargetPose);
        PoseBlender.blend(transitionSourcePose, transitionTargetPose,
                transitionWeight(), baseMask, basePose);
    }

    private void advanceBaseTransition(float deltaSeconds) {
        baseTransitionElapsed = Math.min(baseTransitionDuration,
                baseTransitionElapsed + deltaSeconds);
    }

    private float transitionWeight() {
        float progress = baseTransitionElapsed / baseTransitionDuration;
        float sampled = baseTransitionCurve.sample(progress);
        if (!Float.isFinite(sampled)) {
            throw new IllegalStateException("base transition curve produced a non-finite value");
        }
        return clamp01(sampled);
    }

    private void finishBaseTransition() {
        if (baseTransitionElapsed < baseTransitionDuration) return;
        basePlayer = transitionPlayer;
        transitionPlayer = null;
        baseTransitionCurve = null;
        baseTransitionElapsed = 0.0f;
        baseTransitionDuration = 0.0f;
        transitionSourceFrozen = false;
    }

    private void cancelBaseTransition() {
        if (transitionPlayer != null) transitionPlayer.drainEvents();
        transitionPlayer = null;
        baseTransitionCurve = null;
        baseTransitionElapsed = 0.0f;
        baseTransitionDuration = 0.0f;
        transitionSourceFrozen = false;
    }

    private void finishLayer(float deltaSeconds, PoseBuffer destination) {
        updateLayerFade(deltaSeconds);
        if (!layerEnabled) {
            destination.load(basePose);
            return;
        }
        if (synchronizeLayer) {
            float normalized = normalizedTime(basePlayer());
            float duration = layerPlayer.clip().map(AnimationClip::durationSeconds).orElse(0.0f);
            layerPlayer.seek(normalized * duration).sample(layerPose);
        } else {
            layerPlayer.update(deltaSeconds, layerPose);
        }
        PoseBlender.blend(basePose, layerPose, layerWeight, layerMask, destination);
    }

    private void updateLayerFade(float deltaSeconds) {
        if (layerFadeCurve == null) return;
        layerFadeElapsed = Math.min(layerFadeDuration, layerFadeElapsed + deltaSeconds);
        float progress = layerFadeElapsed / layerFadeDuration;
        float sampled = layerFadeCurve.sample(progress);
        if (!Float.isFinite(sampled)) {
            throw new IllegalStateException("layer fade curve produced a non-finite value");
        }
        float weight = clamp01(sampled);
        layerWeight = clamp01(layerFadeStart + (layerFadeTarget - layerFadeStart) * weight);
        if (layerFadeElapsed >= layerFadeDuration) {
            layerWeight = layerFadeTarget;
            cancelLayerFade();
        }
    }

    private void cancelLayerFade() {
        layerFadeCurve = null;
        layerFadeElapsed = 0.0f;
        layerFadeDuration = 0.0f;
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

    private void removeRootFromPose(PoseBuffer pose, int rootJointIndex) {
        if (rootJointIndex < 0 || rootJointIndex >= skeleton.jointCount()) {
            throw new IndexOutOfBoundsException("root joint index is outside skeleton");
        }
        if (skeleton.joint(rootJointIndex).parentIndex() >= 0) {
            throw new IllegalArgumentException("root motion joint must be a skeleton root");
        }
        JointTransform sampled = pose.localTransform(rootJointIndex);
        JointTransform bind = skeleton.joint(rootJointIndex).bindTransform();
        pose.setLocalTransform(rootJointIndex, new JointTransform(
                bind.translation(), bind.rotation(), sampled.scale()));
    }

    private static RootMotionDelta blendRootMotion(RootMotionDelta outgoing,
                                                    RootMotionDelta incoming, float weight) {
        Vector3f translation = new Vector3f(outgoing.translation())
                .lerp(incoming.translation(), weight);
        Quaternionf rotation = new Quaternionf(outgoing.rotation())
                .slerp(incoming.rotation(), weight).normalize();
        return new RootMotionDelta(translation, rotation);
    }

    private static float normalizedTime(AnimationPlayer player) {
        float duration = player.clip().map(AnimationClip::durationSeconds).orElse(0.0f);
        return duration == 0.0f ? 0.0f : player.timeSeconds() / duration;
    }

    private static void requireFiniteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
