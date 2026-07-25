package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.Objects;

/** 一张不可变的骨架局部与模型空间姿态快照。 */
public final class Pose {
    private final Skeleton skeleton;
    private final List<JointTransform> localTransforms;
    private final Matrix4f[] globalMatrices;

    Pose(Skeleton skeleton, List<JointTransform> localTransforms) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        this.localTransforms = List.copyOf(localTransforms);
        if (this.localTransforms.size() != skeleton.jointCount()) {
            throw new IllegalArgumentException("pose transform count must match skeleton joint count");
        }
        globalMatrices = new Matrix4f[skeleton.jointCount()];
        Matrix4f local = new Matrix4f();
        for (int order = 0; order < skeleton.evaluationCount(); order++) {
            int joint = skeleton.evaluationJoint(order);
            localTransforms.get(joint).matrix(local);
            int parent = skeleton.joint(joint).parentIndex();
            globalMatrices[joint] = parent < 0
                    ? new Matrix4f(local)
                    : new Matrix4f(globalMatrices[parent]).mul(local);
        }
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public int jointCount() {
        return localTransforms.size();
    }

    public JointTransform localTransform(int jointIndex) {
        return localTransforms.get(jointIndex);
    }

    public Matrix4fc globalMatrix(int jointIndex) {
        return new Matrix4f(globalMatrices[jointIndex]);
    }

    public Matrix4f globalMatrix(int jointIndex, Matrix4f destination) {
        return Objects.requireNonNull(destination, "destination").set(globalMatrices[jointIndex]);
    }
}
