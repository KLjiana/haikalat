package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubicAnimationTest {
    @Test
    void cubicTranslationUsesTangentsScaledByKeyframeDuration() {
        Skeleton skeleton = oneJointSkeleton();
        AnimationClip clip = AnimationClip.builder("cubic", skeleton)
                .translationCubic(0, new float[]{0.0f, 2.0f},
                        new Vector3f[]{new Vector3f(1.0f, 0.0f, 0.0f),
                                new Vector3f(1.0f, 0.0f, 0.0f)},
                        new Vector3f[]{new Vector3f(), new Vector3f(2.0f, 0.0f, 0.0f)},
                        new Vector3f[]{new Vector3f(1.0f, 0.0f, 0.0f),
                                new Vector3f(1.0f, 0.0f, 0.0f)})
                .build();

        assertEquals(1.0f, clip.sample(1.0f).localTransform(0).translation().x(), 1.0e-6f);
    }

    @Test
    void cubicQuaternionIsNormalizedAfterComponentHermite() {
        Skeleton skeleton = oneJointSkeleton();
        Quaternionf zeroTangent = new Quaternionf(0.0f, 0.0f, 0.0f, 0.0f);
        AnimationClip clip = AnimationClip.builder("rotation", skeleton)
                .rotationCubic(0, new float[]{0.0f, 1.0f},
                        new Quaternionf[]{zeroTangent, zeroTangent},
                        new Quaternionf[]{new Quaternionf(),
                                new Quaternionf().rotateZ((float) Math.PI)},
                        new Quaternionf[]{zeroTangent, zeroTangent})
                .build();

        assertEquals(1.0f, clip.sample(0.5f).localTransform(0).rotation().lengthSquared(),
                1.0e-6f);
    }

    @Test
    void cubicInterpolationCannotUseTangentlessBuilder() {
        Skeleton skeleton = oneJointSkeleton();
        assertThrows(IllegalArgumentException.class,
                () -> AnimationClip.builder("invalid", skeleton).translation(0,
                        AnimationClip.Interpolation.CUBIC_SPLINE,
                        new float[]{0.0f}, new Vector3f()));
    }

    private static Skeleton oneJointSkeleton() {
        return new Skeleton(List.of(new Skeleton.Joint("root", -1,
                JointTransform.identity())));
    }
}
