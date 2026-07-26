package com.kaleblangley.haikalat.subsystems.animation;

import java.util.Arrays;
import java.util.Objects;

/** 与骨架绑定的不可变逐关节混合权重。 */
public final class BoneMask {
    private final Skeleton skeleton;
    private final float[] weights;

    private BoneMask(Skeleton skeleton, float[] weights) {
        this.skeleton = skeleton;
        this.weights = weights;
    }

    public static BoneMask all(Skeleton skeleton) {
        Skeleton owner = Objects.requireNonNull(skeleton, "skeleton");
        float[] weights = new float[owner.jointCount()];
        Arrays.fill(weights, 1.0f);
        return new BoneMask(owner, weights);
    }

    public static BoneMask none(Skeleton skeleton) {
        Skeleton owner = Objects.requireNonNull(skeleton, "skeleton");
        return new BoneMask(owner, new float[owner.jointCount()]);
    }

    public static Builder builder(Skeleton skeleton) {
        return new Builder(skeleton);
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public float weight(int jointIndex) {
        return weights[jointIndex];
    }

    public boolean isFullBody() {
        for (float weight : weights) {
            if (weight != 1.0f) return false;
        }
        return true;
    }

    public static final class Builder {
        private final Skeleton skeleton;
        private final float[] weights;

        private Builder(Skeleton skeleton) {
            this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
            weights = new float[skeleton.jointCount()];
        }

        public Builder fill(float weight) {
            requireWeight(weight);
            Arrays.fill(weights, weight);
            return this;
        }

        public Builder joint(int jointIndex, float weight) {
            requireJoint(jointIndex);
            requireWeight(weight);
            weights[jointIndex] = weight;
            return this;
        }

        /** 设置根关节及其全部后代，不依赖关节数组的排列顺序。 */
        public Builder subtree(int rootJointIndex, float weight) {
            requireJoint(rootJointIndex);
            requireWeight(weight);
            for (int joint = 0; joint < skeleton.jointCount(); joint++) {
                if (joint == rootJointIndex || descendsFrom(joint, rootJointIndex)) {
                    weights[joint] = weight;
                }
            }
            return this;
        }

        public BoneMask build() {
            return new BoneMask(skeleton, weights.clone());
        }

        private boolean descendsFrom(int joint, int ancestor) {
            int parent = skeleton.joint(joint).parentIndex();
            while (parent >= 0) {
                if (parent == ancestor) return true;
                parent = skeleton.joint(parent).parentIndex();
            }
            return false;
        }

        private void requireJoint(int jointIndex) {
            if (jointIndex < 0 || jointIndex >= weights.length) {
                throw new IndexOutOfBoundsException("joint index " + jointIndex
                        + " is outside 0.." + (weights.length - 1));
            }
        }
    }

    static void requireWeight(float weight) {
        if (!Float.isFinite(weight) || weight < 0.0f || weight > 1.0f) {
            throw new IllegalArgumentException("weight must be finite and in [0, 1]");
        }
    }
}
