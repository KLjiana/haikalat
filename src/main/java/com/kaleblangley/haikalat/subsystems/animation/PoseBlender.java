package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;

/** 支持逐骨骼遮罩的局部姿态混合。 */
public final class PoseBlender {
    private PoseBlender() {
    }

    /**
     * 将 overlay 叠加到 base。destination 可以与任一输入相同。
     */
    public static void blend(PoseBuffer base, PoseBuffer overlay, float weight,
                             BoneMask mask, PoseBuffer destination) {
        PoseBuffer first = Objects.requireNonNull(base, "base");
        PoseBuffer second = Objects.requireNonNull(overlay, "overlay");
        PoseBuffer target = Objects.requireNonNull(destination, "destination");
        BoneMask jointMask = Objects.requireNonNull(mask, "mask");
        BoneMask.requireWeight(weight);
        Skeleton skeleton = first.skeleton();
        if (second.skeleton() != skeleton || target.skeleton() != skeleton
                || jointMask.skeleton() != skeleton) {
            throw new IllegalArgumentException("poses and mask must belong to the same skeleton");
        }

        for (int joint = 0; joint < skeleton.jointCount(); joint++) {
            JointTransform from = first.localTransform(joint);
            JointTransform to = second.localTransform(joint);
            float alpha = weight * jointMask.weight(joint);
            Vector3f translation = new Vector3f(from.translation()).lerp(to.translation(), alpha);
            Quaternionf rotation = new Quaternionf(from.rotation())
                    .slerp(to.rotation(), alpha).normalize();
            Vector3f scale = new Vector3f(from.scale()).lerp(to.scale(), alpha);
            target.setLocalTransform(joint, new JointTransform(translation, rotation, scale));
        }
    }
}
