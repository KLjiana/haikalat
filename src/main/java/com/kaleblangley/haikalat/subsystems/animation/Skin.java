package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable mapping from a mesh skin's palette slots to skeleton joints. */
public final class Skin {
    private final String name;
    private final Skeleton skeleton;
    private final int[] joints;
    private final List<Matrix4f> inverseBindMatrices;

    public Skin(String name, Skeleton skeleton, int[] joints,
                List<? extends Matrix4fc> inverseBindMatrices) {
        this.name = name == null ? "" : name;
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        this.joints = Objects.requireNonNull(joints, "joints").clone();
        Objects.requireNonNull(inverseBindMatrices, "inverseBindMatrices");
        if (this.joints.length == 0) {
            throw new IllegalArgumentException("skin must contain at least one joint");
        }
        if (this.joints.length != inverseBindMatrices.size()) {
            throw new IllegalArgumentException(
                    "joint and inverse-bind-matrix counts must match");
        }
        HashSet<Integer> unique = new HashSet<>();
        ArrayList<Matrix4f> copied = new ArrayList<>(this.joints.length);
        for (int index = 0; index < this.joints.length; index++) {
            int joint = this.joints[index];
            if (joint < 0 || joint >= skeleton.jointCount()) {
                throw new IndexOutOfBoundsException("skin joint index is outside skeleton: " + joint);
            }
            if (!unique.add(joint)) {
                throw new IllegalArgumentException("skin contains duplicate joint " + joint);
            }
            Matrix4f inverseBind = new Matrix4f(Objects.requireNonNull(
                    inverseBindMatrices.get(index), "inverseBindMatrices[" + index + "]"));
            if (!finite(inverseBind)) {
                throw new IllegalArgumentException(
                        "inverseBindMatrices[" + index + "] must be finite");
            }
            copied.add(inverseBind);
        }
        this.inverseBindMatrices = List.copyOf(copied);
    }

    public String name() {
        return name;
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public int jointCount() {
        return joints.length;
    }

    public int skeletonJointIndex(int paletteIndex) {
        return joints[paletteIndex];
    }

    public int[] skeletonJointIndices() {
        return joints.clone();
    }

    public Matrix4fc inverseBindMatrix(int paletteIndex) {
        return new Matrix4f(inverseBindMatrices.get(paletteIndex));
    }

    public JointPalette createPalette() {
        return new JointPalette(this);
    }

    private static boolean finite(Matrix4fc matrix) {
        float[] values = new float[16];
        matrix.get(values);
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }
}
