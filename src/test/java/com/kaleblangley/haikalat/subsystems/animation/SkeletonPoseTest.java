package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkeletonPoseTest {
    @Test
    void hierarchyEvaluationDoesNotDependOnJointIndexOrder() {
        Skeleton skeleton = new Skeleton(List.of(
                new Skeleton.Joint("child", 1, transform(0.0f, 2.0f, 0.0f)),
                new Skeleton.Joint("root", -1, transform(1.0f, 0.0f, 0.0f)),
                new Skeleton.Joint("tip", 0, transform(0.0f, 0.0f, 3.0f))));

        PoseBuffer buffer = skeleton.createPoseBuffer();

        assertEquals(1.0f, buffer.globalMatrix(0).m30(), 0.0f);
        assertEquals(2.0f, buffer.globalMatrix(0).m31(), 0.0f);
        assertEquals(1.0f, buffer.globalMatrix(2).m30(), 0.0f);
        assertEquals(2.0f, buffer.globalMatrix(2).m31(), 0.0f);
        assertEquals(3.0f, buffer.globalMatrix(2).m32(), 0.0f);
    }

    @Test
    void poseBufferRecomputesGlobalsAndSnapshotsStayImmutable() {
        Skeleton skeleton = new Skeleton(List.of(
                new Skeleton.Joint("root", -1, transform(1.0f, 0.0f, 0.0f)),
                new Skeleton.Joint("child", 0, transform(0.0f, 2.0f, 0.0f))));
        PoseBuffer buffer = skeleton.createPoseBuffer();
        Pose before = buffer.snapshot();

        buffer.setTranslation(0, new Vector3f(4.0f, 0.0f, 0.0f));
        Pose after = buffer.snapshot();

        assertEquals(1L, buffer.revision());
        assertEquals(1.0f, before.globalMatrix(1).m30(), 0.0f);
        assertEquals(4.0f, after.globalMatrix(1).m30(), 0.0f);
        assertEquals(4.0f, buffer.globalMatrix(1).m30(), 0.0f);
        assertNotSame(before.globalMatrix(1), before.globalMatrix(1));

        buffer.resetToBindPose();
        assertEquals(1.0f, buffer.globalMatrix(1).m30(), 0.0f);
    }

    @Test
    void hierarchyAndPoseOwnershipFailuresAreExplicit() {
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(List.of(
                new Skeleton.Joint("bad", 1, JointTransform.identity()))));
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(List.of(
                new Skeleton.Joint("a", 1, JointTransform.identity()),
                new Skeleton.Joint("b", 0, JointTransform.identity()))));

        Skeleton first = oneJointSkeleton();
        Skeleton second = oneJointSkeleton();
        assertThrows(IllegalArgumentException.class,
                () -> first.createPoseBuffer().load(second.bindPose()));
        assertThrows(IndexOutOfBoundsException.class,
                () -> first.createPoseBuffer().globalMatrix(1));
    }

    private static Skeleton oneJointSkeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, JointTransform.identity())));
    }

    private static JointTransform transform(float x, float y, float z) {
        return new JointTransform(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(1.0f));
    }
}
