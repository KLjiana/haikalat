package com.kaleblangley.haikalat.subsystems.animation;

import com.kaleblangley.haikalat.core.curve.Curves;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.kaleblangley.haikalat.subsystems.animation.AnimationClip.Interpolation.LINEAR;
import static com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer.LoopMode.LOOP;
import static com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer.LoopMode.ONCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedAnimationTest {
    private static final float EPSILON = 1.0e-4f;

    @Test
    void boneMaskSubtreeUsesHierarchyInsteadOfArrayOrderAndIsImmutable() {
        Skeleton skeleton = unorderedSkeleton();
        BoneMask.Builder builder = BoneMask.builder(skeleton).joint(3, 0.25f).subtree(2, 0.75f);
        BoneMask mask = builder.build();
        builder.fill(0.0f);

        assertEquals(0.75f, mask.weight(0));
        assertEquals(0.0f, mask.weight(1));
        assertEquals(0.75f, mask.weight(2));
        assertEquals(0.25f, mask.weight(3));
        assertThrows(IllegalArgumentException.class,
                () -> BoneMask.builder(skeleton).joint(0, Float.NaN));
        assertThrows(IndexOutOfBoundsException.class, () -> mask.weight(99));
    }

    @Test
    void poseBlendSupportsMasksAndDestinationAliasing() {
        Skeleton skeleton = unorderedSkeleton();
        PoseBuffer base = skeleton.createPoseBuffer();
        PoseBuffer overlay = skeleton.createPoseBuffer()
                .setTranslation(0, new Vector3f(4.0f, 0.0f, 0.0f))
                .setTranslation(3, new Vector3f(8.0f, 0.0f, 0.0f));
        BoneMask mask = BoneMask.builder(skeleton).subtree(2, 1.0f).build();

        PoseBlender.blend(base, overlay, 0.5f, mask, base);

        assertEquals(2.0f, base.localTransform(0).translation().x(), EPSILON);
        assertEquals(0.0f, base.localTransform(3).translation().x(), EPSILON);
        assertThrows(IllegalArgumentException.class, () -> PoseBlender.blend(
                base, new Skeleton(List.of(new Skeleton.Joint("other", -1,
                        JointTransform.identity()))).createPoseBuffer(),
                1.0f, mask, base));
    }

    @Test
    void playerDispatchesOrderedEventsAcrossLoopBoundaries() {
        Skeleton skeleton = oneJointSkeleton();
        AnimationClip clip = AnimationClip.builder("events", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Vector3f(), new Vector3f(2.0f, 0.0f, 0.0f))
                .event(2.0f, "end")
                .event(0.5f, "step", "left")
                .event(0.0f, "start")
                .build();
        AnimationPlayer player = new AnimationPlayer(skeleton).play(clip, LOOP);

        assertEquals(List.of("start"), names(player.drainEvents()));
        player.update(2.5f, skeleton.createPoseBuffer());
        assertEquals(List.of("step", "end", "start", "step"),
                names(player.drainEvents()));
        assertEquals(0.5f, player.timeSeconds(), EPSILON);

        player.play(clip, ONCE).drainEvents();
        player.update(5.0f, skeleton.createPoseBuffer());
        assertEquals(List.of("step", "end"), names(player.drainEvents()));
        assertFalse(player.isPlaying());
        assertThrows(IllegalArgumentException.class, () -> AnimationClip.builder("bad", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 1.0f},
                        new Vector3f(), new Vector3f(1.0f, 0.0f, 0.0f))
                .event(1.1f, "late").build());
    }

    @Test
    void rootMotionAccumulatesLoopsAndCanBeRemovedFromPose() {
        Skeleton skeleton = oneJointSkeleton();
        AnimationClip clip = AnimationClip.builder("stride", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Vector3f(), new Vector3f(2.0f, 0.0f, 0.0f))
                .rotation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Quaternionf(), new Quaternionf().rotateY((float) Math.toRadians(90.0)))
                .build();
        PoseBuffer pose = skeleton.createPoseBuffer();
        AnimationPlayer player = new AnimationPlayer(skeleton).play(clip, LOOP);

        RootMotionDelta delta = player.updateWithRootMotion(2.5f, pose, 0, true);

        assertEquals(2.5f, delta.translation().x(), EPSILON);
        assertEquals(112.5f, (float) Math.toDegrees(new Quaternionf(delta.rotation()).angle()),
                1.0e-2f);
        assertEquals(0.0f, pose.localTransform(0).translation().x(), EPSILON);
        assertEquals(0.0f, new Quaternionf(pose.localTransform(0).rotation()).angle(), EPSILON);

        player.pause();
        RootMotionDelta paused = player.updateWithRootMotion(1.0f, pose, 0, false);
        assertEquals(0.0f, paused.translation().length(), 0.0f);
    }

    @Test
    void mixerSynchronizesNormalizedTimeAndAppliesLayerMask() {
        Skeleton skeleton = twoJointSkeleton();
        AnimationClip base = AnimationClip.builder("walk", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Vector3f(), new Vector3f(2.0f, 0.0f, 0.0f))
                .build();
        AnimationClip layer = AnimationClip.builder("wave", skeleton)
                .translation(1, LINEAR, new float[]{0.0f, 1.0f},
                        new Vector3f(1.0f, 0.0f, 0.0f), new Vector3f(1.0f, 2.0f, 0.0f))
                .build();
        PoseBuffer result = skeleton.createPoseBuffer();
        AnimationMixer mixer = new AnimationMixer(skeleton)
                .playBase(base, LOOP)
                .playLayer(layer, LOOP)
                .layerMask(BoneMask.builder(skeleton).joint(1, 1.0f).build())
                .synchronizeLayer(true);

        mixer.update(1.0f, result);

        assertEquals(1.0f, result.localTransform(0).translation().x(), EPSILON);
        assertEquals(1.0f, result.localTransform(1).translation().y(), EPSILON);
        assertEquals(0.5f, mixer.layerPlayer().timeSeconds(), EPSILON);
    }

    @Test
    void mixerCrossFadesBasePoseAndSupportsImmediateTransitions() {
        Skeleton skeleton = oneJointSkeleton();
        AnimationClip idle = constantTranslation("idle", skeleton, 0.0f);
        AnimationClip run = constantTranslation("run", skeleton, 10.0f);
        PoseBuffer result = skeleton.createPoseBuffer();
        AnimationMixer mixer = new AnimationMixer(skeleton).playBase(idle, LOOP);

        mixer.transitionBase(run, LOOP, 2.0f, Curves.LINEAR).update(1.0f, result);
        assertEquals(5.0f, result.localTransform(0).translation().x(), EPSILON);
        assertEquals(0.5f, mixer.baseTransitionWeight(), EPSILON);
        mixer.update(1.0f, result);
        assertEquals(10.0f, result.localTransform(0).translation().x(), EPSILON);
        assertFalse(mixer.isBaseTransitioning());

        mixer.transitionBase(idle, LOOP, 0.0f, Curves.EASE_IN_OUT_CUBIC).update(0.0f, result);
        assertEquals(0.0f, result.localTransform(0).translation().x(), EPSILON);
        assertEquals("idle", mixer.basePlayer().clip().orElseThrow().name());
    }

    @Test
    void mixerUsesEasedWeightsAndRetargetsWithoutPoseJump() {
        Skeleton skeleton = oneJointSkeleton();
        AnimationClip zero = constantTranslation("zero", skeleton, 0.0f);
        AnimationClip ten = constantTranslation("ten", skeleton, 10.0f);
        AnimationClip twenty = constantTranslation("twenty", skeleton, 20.0f);
        PoseBuffer result = skeleton.createPoseBuffer();
        AnimationMixer mixer = new AnimationMixer(skeleton).playBase(zero, LOOP)
                .transitionBase(ten, LOOP, 1.0f, Curves.EASE_IN_OUT_CUBIC);

        mixer.update(0.25f, result);
        assertEquals(0.625f, result.localTransform(0).translation().x(), EPSILON);
        mixer.update(0.25f, result);
        float beforeRetarget = result.localTransform(0).translation().x();
        mixer.transitionBase(twenty, LOOP, 1.0f, Curves.LINEAR).update(0.0f, result);
        assertEquals(beforeRetarget, result.localTransform(0).translation().x(), EPSILON);
        mixer.update(0.5f, result);
        assertEquals((beforeRetarget + 20.0f) * 0.5f,
                result.localTransform(0).translation().x(), EPSILON);
    }

    @Test
    void crossFadePublishesOnlyIncomingEventsAndBlendsRootMotion() {
        Skeleton skeleton = oneJointSkeleton();
        AnimationClip outgoing = AnimationClip.builder("outgoing", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Vector3f(), new Vector3f(2.0f, 0.0f, 0.0f))
                .event(0.5f, "outgoing-event").build();
        AnimationClip incoming = AnimationClip.builder("incoming", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Vector3f(), new Vector3f(4.0f, 0.0f, 0.0f))
                .event(0.5f, "incoming-event").build();
        PoseBuffer result = skeleton.createPoseBuffer();
        AnimationMixer mixer = new AnimationMixer(skeleton).playBase(outgoing, LOOP);
        mixer.drainEvents();
        mixer.transitionBase(incoming, LOOP, 2.0f, Curves.LINEAR);

        RootMotionDelta delta = mixer.updateWithRootMotion(1.0f, result, 0, true);

        assertEquals(1.5f, delta.translation().x(), EPSILON);
        assertEquals(0.0f, result.localTransform(0).translation().x(), EPSILON);
        assertEquals(List.of("incoming-event"), names(mixer.drainEvents()));
        assertEquals(List.of(), names(mixer.drainEvents()));
    }

    @Test
    void crossFadeHandlesLargeLoopDeltaAndRetargetedRootMotionDeterministically() {
        Skeleton skeleton = oneJointSkeleton();
        AnimationClip slow = movingClip("slow", skeleton, 1.0f);
        AnimationClip fast = movingClip("fast", skeleton, 3.0f);
        AnimationClip reverse = movingClip("reverse", skeleton, -1.0f);
        PoseBuffer firstPose = skeleton.createPoseBuffer();
        PoseBuffer secondPose = skeleton.createPoseBuffer();
        AnimationMixer first = new AnimationMixer(skeleton).playBase(slow, LOOP)
                .transitionBase(fast, LOOP, 4.0f, Curves.LINEAR);
        AnimationMixer second = new AnimationMixer(skeleton).playBase(slow, LOOP)
                .transitionBase(fast, LOOP, 4.0f, Curves.LINEAR);

        RootMotionDelta firstDelta = first.updateWithRootMotion(2.5f, firstPose, 0, true);
        RootMotionDelta secondDelta = second.updateWithRootMotion(2.5f, secondPose, 0, true);
        assertEquals(firstDelta.translation().x(), secondDelta.translation().x(), EPSILON);
        assertEquals(0.0f, firstPose.localTransform(0).translation().x(), EPSILON);

        first.transitionBase(reverse, LOOP, 1.0f, Curves.LINEAR);
        RootMotionDelta retargeted = first.updateWithRootMotion(0.5f, firstPose, 0, true);
        assertTrue(Float.isFinite(retargeted.translation().x()));
        assertEquals(0.0f, firstPose.localTransform(0).translation().x(), EPSILON);
    }

    @Test
    void rootMotionRemovalAppliesAfterLayerComposition() {
        Skeleton skeleton = oneJointSkeleton();
        AnimationClip base = movingClip("base", skeleton, 2.0f);
        AnimationClip layer = constantTranslation("root-layer", skeleton, 9.0f);
        PoseBuffer result = skeleton.createPoseBuffer();
        AnimationMixer mixer = new AnimationMixer(skeleton).playBase(base, LOOP)
                .playLayer(layer, LOOP).layerWeight(1.0f);

        RootMotionDelta delta = mixer.updateWithRootMotion(0.5f, result, 0, true);

        assertEquals(1.0f, delta.translation().x(), EPSILON);
        assertEquals(0.0f, result.localTransform(0).translation().x(), EPSILON);
    }

    @Test
    void layerFadeCanBeReplacedAndWorksWithBoneMask() {
        Skeleton skeleton = twoJointSkeleton();
        AnimationClip base = constantTranslation("base", skeleton, 0.0f);
        AnimationClip layer = AnimationClip.builder("layer", skeleton)
                .translation(1, LINEAR, new float[]{0.0f, 1.0f},
                        transform(1.0f, 2.0f, 0.0f).translation(),
                        transform(1.0f, 2.0f, 0.0f).translation()).build();
        PoseBuffer result = skeleton.createPoseBuffer();
        AnimationMixer mixer = new AnimationMixer(skeleton).playBase(base, LOOP)
                .playLayer(layer, LOOP)
                .layerMask(BoneMask.builder(skeleton).joint(1, 1.0f).build())
                .layerWeight(0.0f)
                .fadeLayerTo(1.0f, 1.0f, Curves.LINEAR);

        mixer.update(0.5f, result);
        assertEquals(0.5f, mixer.layerWeight(), EPSILON);
        assertEquals(1.0f, result.localTransform(1).translation().y(), EPSILON);
        mixer.fadeOutLayer(1.0f, Curves.LINEAR).update(0.5f, result);
        assertEquals(0.25f, mixer.layerWeight(), EPSILON);
        assertEquals(0.5f, result.localTransform(1).translation().y(), EPSILON);
        mixer.clearLayer();
        assertFalse(mixer.isLayerFading());
    }

    @Test
    void twoBoneIkReachesTargetsClampsAndHonorsWeight() {
        Skeleton skeleton = ikSkeleton();
        PoseBuffer reachable = skeleton.createPoseBuffer();

        TwoBoneIkSolver.Result result = TwoBoneIkSolver.solve(reachable, 0, 1, 2,
                new Vector3f(1.0f, 1.0f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f), 1.0f);

        assertFalse(result.clamped());
        assertEquals(0.0f, result.tipError(), 2.0e-3f);
        assertPosition(reachable, 2, 1.0f, 1.0f, 0.0f, 2.0e-3f);

        PoseBuffer unreachable = skeleton.createPoseBuffer();
        TwoBoneIkSolver.Result clamped = TwoBoneIkSolver.solve(unreachable, 0, 1, 2,
                new Vector3f(4.0f, 0.0f, 0.0f), new Vector3f(0.0f, 1.0f, 0.0f), 1.0f);
        assertTrue(clamped.clamped());
        assertEquals(2.0f, clamped.solvedDistance(), 2.0e-3f);
        assertEquals(2.0f, clamped.tipError(), 2.0e-3f);

        PoseBuffer zeroWeight = skeleton.createPoseBuffer();
        TwoBoneIkSolver.solve(zeroWeight, 0, 1, 2,
                new Vector3f(0.0f, 2.0f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f), 0.0f);
        assertPosition(zeroWeight, 2, 2.0f, 0.0f, 0.0f, EPSILON);
        assertThrows(IllegalArgumentException.class, () -> TwoBoneIkSolver.solve(
                zeroWeight, 0, 2, 1, new Vector3f(), new Vector3f(0.0f, 1.0f, 0.0f), 1.0f));
    }

    private static List<String> names(List<AnimationEvent> events) {
        return events.stream().map(AnimationEvent::name).toList();
    }

    private static AnimationClip constantTranslation(String name, Skeleton skeleton, float x) {
        return AnimationClip.builder(name, skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 1.0f},
                        new Vector3f(x, 0.0f, 0.0f), new Vector3f(x, 0.0f, 0.0f))
                .build();
    }

    private static AnimationClip movingClip(String name, Skeleton skeleton, float distance) {
        return AnimationClip.builder(name, skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 1.0f},
                        new Vector3f(), new Vector3f(distance, 0.0f, 0.0f))
                .build();
    }

    private static void assertPosition(PoseBuffer pose, int joint, float x, float y, float z,
                                       float epsilon) {
        assertEquals(x, pose.globalMatrix(joint).m30(), epsilon);
        assertEquals(y, pose.globalMatrix(joint).m31(), epsilon);
        assertEquals(z, pose.globalMatrix(joint).m32(), epsilon);
    }

    private static Skeleton oneJointSkeleton() {
        return new Skeleton(List.of(new Skeleton.Joint("root", -1,
                JointTransform.identity())));
    }

    private static Skeleton twoJointSkeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, JointTransform.identity()),
                new Skeleton.Joint("child", 0, transform(1.0f, 0.0f, 0.0f))));
    }

    private static Skeleton unorderedSkeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("hand", 2, JointTransform.identity()),
                new Skeleton.Joint("root", -1, JointTransform.identity()),
                new Skeleton.Joint("arm", 1, JointTransform.identity()),
                new Skeleton.Joint("leg", 1, JointTransform.identity())));
    }

    private static Skeleton ikSkeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, JointTransform.identity()),
                new Skeleton.Joint("middle", 0, transform(1.0f, 0.0f, 0.0f)),
                new Skeleton.Joint("tip", 1, transform(1.0f, 0.0f, 0.0f))));
    }

    private static JointTransform transform(float x, float y, float z) {
        return new JointTransform(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(1.0f));
    }
}
