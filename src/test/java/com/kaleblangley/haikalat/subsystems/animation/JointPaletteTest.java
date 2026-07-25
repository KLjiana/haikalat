package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JointPaletteTest {
    @Test
    void paletteCancelsBindPoseAndRetainsAnimatedDelta() {
        Skeleton skeleton = new Skeleton(List.of(new Skeleton.Joint("root", -1,
                new JointTransform(new Vector3f(1.0f, 0.0f, 0.0f),
                        new Quaternionf(), new Vector3f(1.0f)))));
        Skin skin = new Skin("skin", skeleton, new int[]{0},
                List.of(new Matrix4f().translation(-1.0f, 0.0f, 0.0f)));
        PoseBuffer pose = skeleton.createPoseBuffer();
        JointPalette palette = skin.createPalette().update(pose, new Matrix4f());

        assertEquals(0.0f, palette.matrix(0).m30(), 1.0e-6f);

        pose.setTranslation(0, new Vector3f(2.5f, 0.0f, 0.0f));
        palette.update(pose, new Matrix4f());
        assertEquals(1.5f, palette.matrix(0).m30(), 1.0e-6f);
        assertEquals(2L, palette.revision());
    }

    @Test
    void rejectsPoseFromDifferentSkeletonAndSingularMeshTransform() {
        Skeleton first = oneJoint();
        Skeleton second = oneJoint();
        JointPalette palette = new Skin("skin", first, new int[]{0},
                List.of(new Matrix4f())).createPalette();

        assertThrows(IllegalArgumentException.class,
                () -> palette.update(second.createPoseBuffer(), new Matrix4f()));
        assertThrows(IllegalArgumentException.class,
                () -> palette.update(first.createPoseBuffer(), new Matrix4f().scale(0.0f)));
    }

    private static Skeleton oneJoint() {
        return new Skeleton(List.of(new Skeleton.Joint("root", -1,
                JointTransform.identity())));
    }
}
