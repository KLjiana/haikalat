package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class ConstraintMath {
    private static final float EPSILON = 1.0e-8f;

    private ConstraintMath() {
    }

    static Vector3f position(PoseBuffer pose, int joint) {
        Matrix4f matrix = pose.globalMatrix(joint, new Matrix4f());
        return new Vector3f(matrix.m30(), matrix.m31(), matrix.m32());
    }

    static Quaternionf globalRotation(PoseBuffer pose, int joint) {
        return pose.globalMatrix(joint, new Matrix4f())
                .getUnnormalizedRotation(new Quaternionf()).normalize();
    }

    static void rotateToward(PoseBuffer pose, int joint,
                             Vector3f currentDirection, Vector3f desiredDirection) {
        if (currentDirection.lengthSquared() <= EPSILON
                || desiredDirection.lengthSquared() <= EPSILON) return;
        Quaternionf delta = new Quaternionf().rotationTo(
                currentDirection.normalize(), desiredDirection.normalize());
        Quaternionf desiredGlobal = delta.mul(globalRotation(pose, joint),
                new Quaternionf()).normalize();
        int parent = pose.skeleton().joint(joint).parentIndex();
        Quaternionf desiredLocal = parent < 0 ? desiredGlobal
                : globalRotation(pose, parent).conjugate()
                .mul(desiredGlobal, new Quaternionf()).normalize();
        pose.setRotation(joint, desiredLocal);
    }

    static void requireJoint(Skeleton skeleton, int joint, String label) {
        if (joint < 0 || joint >= skeleton.jointCount()) {
            throw new IndexOutOfBoundsException(label + " is outside skeleton");
        }
    }
}
