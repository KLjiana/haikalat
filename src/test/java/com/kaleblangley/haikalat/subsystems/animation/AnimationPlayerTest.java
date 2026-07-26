package com.kaleblangley.haikalat.subsystems.animation;

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

class AnimationPlayerTest {
    @Test
    void loopAndOnceModesHaveDeterministicEndBehavior() {
        Skeleton skeleton = skeleton();
        AnimationClip clip = clip(skeleton);
        PoseBuffer pose = skeleton.createPoseBuffer();
        AnimationPlayer player = new AnimationPlayer(skeleton).play(clip, LOOP);

        player.update(2.5f, pose);
        assertEquals(0.5f, player.timeSeconds(), 1.0e-6f);
        assertEquals(0.5f, pose.localTransform(0).translation().x(), 1.0e-6f);
        assertTrue(player.isPlaying());

        player.play(clip, ONCE).update(3.0f, pose);
        assertEquals(2.0f, player.timeSeconds(), 0.0f);
        assertEquals(2.0f, pose.localTransform(0).translation().x(), 0.0f);
        assertFalse(player.isPlaying());
        player.resume();
        assertFalse(player.isPlaying());
    }

    @Test
    void pauseSeekSpeedAndNoClipSamplingAreExplicit() {
        Skeleton skeleton = skeleton();
        PoseBuffer pose = skeleton.createPoseBuffer();
        AnimationPlayer player = new AnimationPlayer(skeleton);
        pose.setTranslation(0, new Vector3f(99.0f, 0.0f, 0.0f));

        player.sample(pose);
        assertEquals(0.0f, pose.localTransform(0).translation().x(), 0.0f);

        player.play(clip(skeleton), LOOP)
                .playbackSpeed(2.0f)
                .update(0.25f, pose);
        assertEquals(0.5f, player.timeSeconds(), 1.0e-6f);
        player.pause().update(1.0f, pose);
        assertEquals(0.5f, player.timeSeconds(), 1.0e-6f);
        player.seek(100.0f);
        assertEquals(2.0f, player.timeSeconds(), 0.0f);
        player.stop();
        assertEquals(0.0f, player.timeSeconds(), 0.0f);
    }

    @Test
    void playbackRejectsInvalidTimeAndSkeletonOwnership() {
        Skeleton first = skeleton();
        Skeleton second = skeleton();
        AnimationPlayer player = new AnimationPlayer(first);

        assertThrows(IllegalArgumentException.class, () -> player.play(clip(second)));
        assertThrows(IllegalArgumentException.class, () -> player.playbackSpeed(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> player.update(-1.0f, first.createPoseBuffer()));
        assertThrows(IllegalArgumentException.class,
                () -> player.sample(second.createPoseBuffer()));
    }

    @Test
    void reversePlaybackTraversesLoopsEventsAndRootMotionDeterministically() {
        Skeleton skeleton = skeleton();
        AnimationClip clip = AnimationClip.builder("reverse", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Vector3f(), new Vector3f(2.0f, 0.0f, 0.0f))
                .event(0.5f, "early")
                .event(1.5f, "late-a")
                .event(1.5f, "late-b")
                .build();
        PoseBuffer pose = skeleton.createPoseBuffer();
        AnimationPlayer player = new AnimationPlayer(skeleton)
                .playbackSpeed(-1.0f)
                .play(clip, LOOP);

        RootMotionDelta delta = player.updateWithRootMotion(2.5f, pose, 0, false);

        assertEquals(1.5f, player.timeSeconds(), 1.0e-6f);
        assertEquals(-2.5f, delta.translation().x(), 1.0e-5f);
        assertEquals(List.of("late-a", "late-b", "early", "late-a", "late-b"),
                player.drainEvents().stream().map(AnimationEvent::name).toList());

        player.play(clip, ONCE).drainEvents();
        player.update(3.0f, pose);
        assertEquals(0.0f, player.timeSeconds(), 0.0f);
        assertFalse(player.isPlaying());
    }

    @Test
    void zeroPlaybackSpeedHoldsWithoutStopping() {
        Skeleton skeleton = skeleton();
        PoseBuffer pose = skeleton.createPoseBuffer();
        AnimationPlayer player = new AnimationPlayer(skeleton)
                .play(clip(skeleton), LOOP)
                .playbackSpeed(0.0f);

        player.update(1.0f, pose);

        assertEquals(0.0f, player.timeSeconds(), 0.0f);
        assertTrue(player.isPlaying());
    }

    private static AnimationClip clip(Skeleton skeleton) {
        return AnimationClip.builder("move", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Vector3f(), new Vector3f(2.0f, 0.0f, 0.0f))
                .build();
    }

    private static Skeleton skeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, JointTransform.identity())));
    }
}
