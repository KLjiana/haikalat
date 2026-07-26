package com.kaleblangley.haikalat.subsystems.animation;

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
            float alpha = weight * jointMask.weight(joint);
            target.setBlendedLocal(joint, first, second, alpha);
        }
    }

    /** 将 sample 相对 reference 的 delta 叠加到 base；destination 可以与任一输入相同。 */
    public static void additive(PoseBuffer base, PoseBuffer sample, PoseBuffer reference,
                                float weight, BoneMask mask, PoseBuffer destination) {
        PoseBuffer first = Objects.requireNonNull(base, "base");
        PoseBuffer second = Objects.requireNonNull(sample, "sample");
        PoseBuffer origin = Objects.requireNonNull(reference, "reference");
        PoseBuffer target = Objects.requireNonNull(destination, "destination");
        BoneMask jointMask = Objects.requireNonNull(mask, "mask");
        BoneMask.requireWeight(weight);
        Skeleton skeleton = first.skeleton();
        if (second.skeleton() != skeleton || origin.skeleton() != skeleton
                || target.skeleton() != skeleton || jointMask.skeleton() != skeleton) {
            throw new IllegalArgumentException("poses and mask must belong to the same skeleton");
        }
        for (int joint = 0; joint < skeleton.jointCount(); joint++) {
            target.setAdditiveLocal(joint, first, second, origin,
                    weight * jointMask.weight(joint));
        }
    }
}
