package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** 模型空间目标与 Pole Vector 驱动的解析 Two-bone IK。 */
public final class TwoBoneIkSolver {
    private static final float EPSILON = 1.0e-6f;

    private TwoBoneIkSolver() {
    }

    public static Result solve(PoseBuffer pose, int rootJoint, int middleJoint, int tipJoint,
                               Vector3fc target, Vector3fc pole, float weight) {
        PoseBuffer buffer = Objects.requireNonNull(pose, "pose");
        JointTransform.requireFinite(target, "target");
        JointTransform.requireFinite(pole, "pole");
        BoneMask.requireWeight(weight);
        Skeleton skeleton = buffer.skeleton();
        requireJoint(skeleton, rootJoint, "rootJoint");
        requireJoint(skeleton, middleJoint, "middleJoint");
        requireJoint(skeleton, tipJoint, "tipJoint");
        if (skeleton.joint(middleJoint).parentIndex() != rootJoint
                || skeleton.joint(tipJoint).parentIndex() != middleJoint) {
            throw new IllegalArgumentException("IK joints must form a direct root-middle-tip chain");
        }

        Quaternionf originalRoot = new Quaternionf(buffer.localTransform(rootJoint).rotation());
        Quaternionf originalMiddle = new Quaternionf(buffer.localTransform(middleJoint).rotation());
        Vector3f rootPosition = position(buffer, rootJoint);
        Vector3f middlePosition = position(buffer, middleJoint);
        Vector3f tipPosition = position(buffer, tipJoint);
        float firstLength = rootPosition.distance(middlePosition);
        float secondLength = middlePosition.distance(tipPosition);
        if (!Float.isFinite(firstLength + secondLength)
                || firstLength <= EPSILON || secondLength <= EPSILON) {
            throw new IllegalArgumentException("IK bones must have finite non-zero lengths");
        }

        Vector3f requested = new Vector3f(target).sub(rootPosition);
        float requestedDistance = requested.length();
        Vector3f direction = requestedDistance > EPSILON
                ? requested.div(requestedDistance, new Vector3f())
                : fallbackDirection(rootPosition, tipPosition);
        float minimum = Math.abs(firstLength - secondLength) + EPSILON;
        float maximum = firstLength + secondLength - EPSILON;
        float solvedDistance = Math.clamp(requestedDistance, minimum, maximum);
        boolean clamped = Math.abs(solvedDistance - requestedDistance) > EPSILON;

        Vector3f bendDirection = bendDirection(rootPosition, middlePosition,
                direction, pole);
        float along = (solvedDistance * solvedDistance + firstLength * firstLength
                - secondLength * secondLength) / (2.0f * solvedDistance);
        float heightSquared = Math.max(0.0f, firstLength * firstLength - along * along);
        float height = (float) Math.sqrt(heightSquared);
        Vector3f desiredMiddle = new Vector3f(rootPosition)
                .fma(along, direction)
                .fma(height, bendDirection);
        Vector3f desiredTip = new Vector3f(rootPosition).fma(solvedDistance, direction);

        rotateJointToward(buffer, rootJoint,
                new Vector3f(middlePosition).sub(rootPosition),
                new Vector3f(desiredMiddle).sub(rootPosition));

        middlePosition = position(buffer, middleJoint);
        tipPosition = position(buffer, tipJoint);
        rotateJointToward(buffer, middleJoint,
                new Vector3f(tipPosition).sub(middlePosition),
                new Vector3f(desiredTip).sub(middlePosition));

        if (weight < 1.0f) {
            Quaternionf solvedRoot = new Quaternionf(buffer.localTransform(rootJoint).rotation());
            Quaternionf solvedMiddle = new Quaternionf(buffer.localTransform(middleJoint).rotation());
            buffer.setRotation(rootJoint, originalRoot.slerp(solvedRoot, weight).normalize());
            buffer.setRotation(middleJoint,
                    originalMiddle.slerp(solvedMiddle, weight).normalize());
        }

        float error = position(buffer, tipJoint).distance(target);
        return new Result(requestedDistance, solvedDistance, error, clamped);
    }

    private static void rotateJointToward(PoseBuffer pose, int joint,
                                          Vector3f currentDirection,
                                          Vector3f desiredDirection) {
        if (currentDirection.lengthSquared() <= EPSILON * EPSILON
                || desiredDirection.lengthSquared() <= EPSILON * EPSILON) {
            return;
        }
        currentDirection.normalize();
        desiredDirection.normalize();
        Quaternionf worldDelta = new Quaternionf().rotationTo(currentDirection, desiredDirection);
        Quaternionf currentGlobal = globalRotation(pose, joint);
        Quaternionf desiredGlobal = worldDelta.mul(currentGlobal, new Quaternionf()).normalize();
        int parent = pose.skeleton().joint(joint).parentIndex();
        Quaternionf desiredLocal = parent < 0
                ? desiredGlobal
                : globalRotation(pose, parent).conjugate().mul(desiredGlobal).normalize();
        pose.setRotation(joint, desiredLocal);
    }

    private static Vector3f bendDirection(Vector3f rootPosition, Vector3f middlePosition,
                                          Vector3f direction, Vector3fc pole) {
        Vector3f bend = reject(new Vector3f(pole).sub(rootPosition), direction);
        if (bend.lengthSquared() <= EPSILON * EPSILON) {
            bend = reject(new Vector3f(middlePosition).sub(rootPosition), direction);
        }
        if (bend.lengthSquared() <= EPSILON * EPSILON) {
            Vector3f axis = Math.abs(direction.y) < 0.9f
                    ? new Vector3f(0.0f, 1.0f, 0.0f)
                    : new Vector3f(1.0f, 0.0f, 0.0f);
            bend = reject(axis, direction);
        }
        return bend.normalize();
    }

    private static Vector3f reject(Vector3f value, Vector3f unitDirection) {
        return value.sub(new Vector3f(unitDirection).mul(value.dot(unitDirection)));
    }

    private static Vector3f fallbackDirection(Vector3f rootPosition, Vector3f tipPosition) {
        Vector3f direction = new Vector3f(tipPosition).sub(rootPosition);
        return direction.lengthSquared() <= EPSILON * EPSILON
                ? new Vector3f(1.0f, 0.0f, 0.0f)
                : direction.normalize();
    }

    private static Vector3f position(PoseBuffer pose, int joint) {
        Matrix4f matrix = pose.globalMatrix(joint, new Matrix4f());
        return new Vector3f(matrix.m30(), matrix.m31(), matrix.m32());
    }

    private static Quaternionf globalRotation(PoseBuffer pose, int joint) {
        return pose.globalMatrix(joint, new Matrix4f())
                .getUnnormalizedRotation(new Quaternionf()).normalize();
    }

    private static void requireJoint(Skeleton skeleton, int joint, String name) {
        if (joint < 0 || joint >= skeleton.jointCount()) {
            throw new IndexOutOfBoundsException(name + " is outside skeleton");
        }
    }

    public record Result(float requestedDistance, float solvedDistance,
                         float tipError, boolean clamped) {
        public Result {
            if (!Float.isFinite(requestedDistance) || requestedDistance < 0.0f
                    || !Float.isFinite(solvedDistance) || solvedDistance < 0.0f
                    || !Float.isFinite(tipError) || tipError < 0.0f) {
                throw new IllegalArgumentException("IK result distances must be finite and non-negative");
            }
        }
    }
}
