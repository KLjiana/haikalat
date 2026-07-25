package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.kaleblangley.haikalat.subsystems.animation.AnimationClip.Interpolation.LINEAR;
import static com.kaleblangley.haikalat.subsystems.animation.AnimationClip.Interpolation.STEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnimationClipTest {
    @Test
    void samplesLinearStepAndShortestQuaternionChannels() {
        Skeleton skeleton = skeleton();
        AnimationClip clip = AnimationClip.builder("motion", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Vector3f(), new Vector3f(10.0f, 0.0f, 0.0f))
                .rotation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Quaternionf(), new Quaternionf().rotateZ((float) Math.PI))
                .scale(1, STEP, new float[]{0.0f, 1.0f, 2.0f},
                        new Vector3f(1.0f), new Vector3f(2.0f), new Vector3f(3.0f))
                .build();

        Pose pose = clip.sample(1.0f);
        Vector3f rotated = new Vector3f(1.0f, 0.0f, 0.0f)
                .rotate(pose.localTransform(0).rotation());

        assertEquals(3, clip.channelCount());
        assertEquals(2.0f, clip.durationSeconds(), 0.0f);
        assertEquals(5.0f, pose.localTransform(0).translation().x(), 1.0e-6f);
        assertEquals(0.0f, rotated.x, 1.0e-5f);
        assertEquals(1.0f, Math.abs(rotated.y), 1.0e-5f);
        assertEquals(new Vector3f(2.0f), pose.localTransform(1).scale());
        assertEquals(2.0f, pose.localTransform(1).translation().y(), 0.0f,
                "An undriven component must retain its bind value");
    }

    @Test
    void samplingClampsAndClipOwnsKeyframeData() {
        Skeleton skeleton = skeleton();
        float[] times = {0.0f, 1.0f};
        Vector3f start = new Vector3f();
        Vector3f end = new Vector3f(4.0f, 0.0f, 0.0f);
        AnimationClip clip = AnimationClip.builder("copy", skeleton)
                .translation(0, LINEAR, times, start, end)
                .build();
        times[1] = 100.0f;
        end.x = 100.0f;

        assertEquals(0.0f, clip.sample(0.0f).localTransform(0).translation().x(), 0.0f);
        assertEquals(4.0f, clip.sample(5.0f).localTransform(0).translation().x(), 0.0f);
    }

    @Test
    void malformedChannelsAndForeignPoseBuffersAreRejected() {
        Skeleton skeleton = skeleton();
        AnimationClip.Builder builder = AnimationClip.builder("invalid", skeleton);

        assertThrows(IllegalArgumentException.class, () -> builder.translation(0, LINEAR,
                new float[]{0.0f, 0.0f}, new Vector3f(), new Vector3f()));
        builder.translation(0, LINEAR, new float[]{0.0f}, new Vector3f());
        assertThrows(IllegalArgumentException.class, () -> builder.translation(0, LINEAR,
                new float[]{0.0f}, new Vector3f()));
        assertThrows(IllegalArgumentException.class, () -> AnimationClip.builder("bad", skeleton)
                .rotation(0, LINEAR, new float[]{0.0f},
                        new Quaternionf(0.0f, 0.0f, 0.0f, 0.0f)));
        assertThrows(IllegalArgumentException.class, () -> builder.build().sample(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> builder.build().sample(0.0f, skeleton().createPoseBuffer()));
    }

    private static Skeleton skeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, JointTransform.identity()),
                new Skeleton.Joint("child", 0, new JointTransform(
                        new Vector3f(0.0f, 2.0f, 0.0f), new Quaternionf(), new Vector3f(1.0f)))));
    }
}
