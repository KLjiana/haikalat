package com.kaleblangley.haikalat.subsystems.animation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 不可变的关节层次与绑定姿态。 */
public final class Skeleton {
    private final List<Joint> joints;
    private final int[] evaluationOrder;

    public Skeleton(List<Joint> joints) {
        Objects.requireNonNull(joints, "joints");
        if (joints.isEmpty()) {
            throw new IllegalArgumentException("skeleton must contain at least one joint");
        }
        this.joints = List.copyOf(joints);
        this.evaluationOrder = validateAndOrder(this.joints);
    }

    public int jointCount() {
        return joints.size();
    }

    public Joint joint(int index) {
        return joints.get(index);
    }

    public List<Joint> joints() {
        return joints;
    }

    public Pose bindPose() {
        return createPoseBuffer().snapshot();
    }

    public PoseBuffer createPoseBuffer() {
        return new PoseBuffer(this);
    }

    int evaluationCount() {
        return evaluationOrder.length;
    }

    int evaluationJoint(int orderIndex) {
        return evaluationOrder[orderIndex];
    }

    boolean descendsFrom(int jointIndex, int ancestorIndex) {
        int parent = joints.get(jointIndex).parentIndex();
        while (parent >= 0) {
            if (parent == ancestorIndex) return true;
            parent = joints.get(parent).parentIndex();
        }
        return false;
    }

    private static int[] validateAndOrder(List<Joint> joints) {
        int jointCount = joints.size();
        List<List<Integer>> children = new ArrayList<>(jointCount);
        for (int index = 0; index < jointCount; index++) {
            children.add(new ArrayList<>());
        }
        ArrayDeque<Integer> roots = new ArrayDeque<>();
        for (int index = 0; index < jointCount; index++) {
            Joint joint = Objects.requireNonNull(joints.get(index), "joints[" + index + "]");
            int parent = joint.parentIndex();
            if (parent < -1 || parent >= jointCount) {
                throw new IllegalArgumentException("joint[" + index + "] parent index is out of range: "
                        + parent);
            }
            if (parent == index) {
                throw new IllegalArgumentException("joint[" + index + "] cannot parent itself");
            }
            if (parent < 0) {
                roots.addLast(index);
            } else {
                children.get(parent).add(index);
            }
        }

        int[] order = new int[jointCount];
        int count = 0;
        while (!roots.isEmpty()) {
            int joint = roots.removeFirst();
            order[count++] = joint;
            for (int child : children.get(joint)) {
                roots.addLast(child);
            }
        }
        if (count != jointCount) {
            throw new IllegalArgumentException("skeleton hierarchy contains a cycle");
        }
        return order;
    }

    @Override
    public String toString() {
        return "Skeleton[joints=" + joints.size() + ", evaluationOrder="
                + Arrays.toString(evaluationOrder) + ']';
    }

    public record Joint(String name, int parentIndex, JointTransform bindTransform) {
        public Joint {
            name = name == null ? "" : name;
            bindTransform = Objects.requireNonNull(bindTransform, "bindTransform");
        }
    }
}
