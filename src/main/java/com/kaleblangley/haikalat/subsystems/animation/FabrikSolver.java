package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 固定迭代、连续单链 FABRIK solver definition。 */
public final class FabrikSolver implements AnimationConstraint {
    private static final float EPSILON = 1.0e-6f;

    private final Skeleton skeleton;
    private final int[] chain;
    private final int maximumIterations;
    private final float tolerance;
    private final List<JointLimit> limits;
    private final Vector3f[] positions;
    private final Quaternionf[] originalRotations;
    private final float[] lengths;
    private final Vector3f root = new Vector3f();
    private final Vector3f requestedTarget = new Vector3f();
    private final Vector3f pole = new Vector3f();
    private final Vector3f direction = new Vector3f();
    private final Vector3f current = new Vector3f();
    private final Vector3f desired = new Vector3f();
    private final Vector3f firstPosition = new Vector3f();
    private final Vector3f secondPosition = new Vector3f();
    private final Vector3f axis = new Vector3f();
    private final Vector3f projectedCurrent = new Vector3f();
    private final Vector3f projectedDesired = new Vector3f();
    private final Vector3f offset = new Vector3f();
    private final Quaternionf deltaRotation = new Quaternionf();
    private final Quaternionf globalRotation = new Quaternionf();
    private final Quaternionf desiredGlobalRotation = new Quaternionf();
    private final Quaternionf parentGlobalRotation = new Quaternionf();
    private final Quaternionf desiredLocalRotation = new Quaternionf();
    private final Quaternionf solvedRotation = new Quaternionf();

    public FabrikSolver(Skeleton skeleton, int[] chain, int maximumIterations,
                        float tolerance, List<JointLimit> limits) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        Objects.requireNonNull(chain, "chain");
        if (chain.length < 3) {
            throw new IllegalArgumentException("FABRIK chain requires at least three joints");
        }
        this.chain = chain.clone();
        for (int index = 0; index < this.chain.length; index++) {
            ConstraintMath.requireJoint(skeleton, this.chain[index], "chain[" + index + "]");
            if (index > 0 && skeleton.joint(this.chain[index]).parentIndex()
                    != this.chain[index - 1]) {
                throw new IllegalArgumentException(
                        "FABRIK joints must form a continuous parent chain");
            }
        }
        if (maximumIterations < 1 || maximumIterations > 16) {
            throw new IllegalArgumentException("maximumIterations must be in [1, 16]");
        }
        if (!Float.isFinite(tolerance) || tolerance <= 0.0f) {
            throw new IllegalArgumentException("tolerance must be finite and positive");
        }
        this.maximumIterations = maximumIterations;
        this.tolerance = tolerance;
        this.limits = List.copyOf(Objects.requireNonNull(limits, "limits"));
        for (JointLimit limit : this.limits) {
            if (limit.skeleton() != skeleton) {
                throw new IllegalArgumentException("joint limit belongs to another skeleton");
            }
        }
        positions = new Vector3f[this.chain.length];
        originalRotations = new Quaternionf[this.chain.length - 1];
        lengths = new float[this.chain.length - 1];
        for (int index = 0; index < positions.length; index++) {
            positions[index] = new Vector3f();
            if (index < originalRotations.length) {
                originalRotations[index] = new Quaternionf();
            }
        }
    }

    public FabrikSolver(Skeleton skeleton, int... chain) {
        this(skeleton, chain, 12, 1.0e-4f, List.of());
    }

    @Override
    public Skeleton skeleton() {
        return skeleton;
    }

    public int[] chain() {
        return chain.clone();
    }

    @Override
    public Result apply(PoseBuffer pose, Context context) {
        PoseBuffer targetPose = Objects.requireNonNull(pose, "pose");
        if (targetPose.skeleton() != skeleton) {
            throw new IllegalArgumentException("pose belongs to a different skeleton");
        }
        Context input = Objects.requireNonNull(context, "context");
        int count = chain.length;
        float totalLength = 0.0f;
        for (int index = 0; index < count; index++) {
            targetPose.globalPosition(chain[index], positions[index]);
            if (index < count - 1) {
                targetPose.copyLocalRotation(chain[index], originalRotations[index]);
            }
            if (index > 0) {
                lengths[index - 1] = positions[index - 1].distance(positions[index]);
                if (!Float.isFinite(lengths[index - 1]) || lengths[index - 1] <= EPSILON) {
                    throw new IllegalArgumentException(
                            "FABRIK bones must have finite non-zero lengths");
                }
                totalLength += lengths[index - 1];
            }
        }
        root.set(positions[0]);
        requestedTarget.set(input.targetX(), input.targetY(), input.targetZ());
        pole.set(input.poleX(), input.poleY(), input.poleZ());
        float requestedDistance = root.distance(requestedTarget);
        boolean clamped = requestedDistance > totalLength;
        int iterations = 0;
        if (clamped) {
            direction.set(requestedTarget).sub(root);
            if (direction.lengthSquared() <= EPSILON * EPSILON) direction.set(1.0f, 0.0f, 0.0f);
            else direction.normalize();
            for (int index = 1; index < count; index++) {
                positions[index].set(positions[index - 1]).fma(lengths[index - 1], direction);
            }
            iterations = 1;
        } else {
            for (int iteration = 0; iteration < maximumIterations; iteration++) {
                positions[count - 1].set(requestedTarget);
                for (int index = count - 2; index >= 0; index--) {
                    placeAtDistance(positions[index], positions[index + 1],
                            lengths[index], positions[index], direction);
                }
                positions[0].set(root);
                for (int index = 1; index < count; index++) {
                    placeAtDistance(positions[index], positions[index - 1],
                            lengths[index - 1], positions[index], direction);
                }
                applyPole();
                iterations = iteration + 1;
                if (positions[count - 1].distance(requestedTarget) <= tolerance) break;
            }
        }

        for (int index = 0; index < count - 1; index++) {
            targetPose.globalPosition(chain[index + 1], firstPosition);
            targetPose.globalPosition(chain[index], secondPosition);
            current.set(firstPosition).sub(secondPosition);
            desired.set(positions[index + 1]).sub(positions[index]);
            rotateToward(targetPose, chain[index]);
            for (int limitIndex = 0; limitIndex < limits.size(); limitIndex++) {
                JointLimit limit = limits.get(limitIndex);
                if (limit.jointIndex() == chain[index]) clamped |= limit.apply(targetPose);
            }
        }
        if (input.weight() < 1.0f) {
            for (int index = 0; index < count - 1; index++) {
                targetPose.copyLocalRotation(chain[index], solvedRotation);
                targetPose.setRotation(chain[index],
                        desiredLocalRotation.set(originalRotations[index])
                                .slerp(solvedRotation, input.weight()).normalize());
            }
        }
        targetPose.globalPosition(chain[count - 1], firstPosition);
        float solvedDistance = firstPosition.distance(root);
        float residual = firstPosition.distance(requestedTarget);
        return new Result(iterations, requestedDistance, solvedDistance, residual, clamped);
    }

    private static void placeAtDistance(Vector3f point, Vector3f anchor,
                                        float distance, Vector3f destination,
                                        Vector3f scratch) {
        scratch.set(point).sub(anchor);
        if (scratch.lengthSquared() <= EPSILON * EPSILON) {
            scratch.set(1.0f, 0.0f, 0.0f);
        } else {
            scratch.normalize();
        }
        destination.set(anchor).fma(distance, scratch);
    }

    private void applyPole() {
        for (int index = 1; index < positions.length - 1; index++) {
            axis.set(positions[index + 1]).sub(positions[index - 1]);
            if (axis.lengthSquared() <= EPSILON * EPSILON) continue;
            axis.normalize();
            reject(projectedCurrent.set(positions[index])
                    .sub(positions[index - 1]), axis);
            reject(projectedDesired.set(pole).sub(positions[index - 1]), axis);
            if (projectedCurrent.lengthSquared() <= EPSILON * EPSILON
                    || projectedDesired.lengthSquared() <= EPSILON * EPSILON) continue;
            deltaRotation.rotationTo(
                    projectedCurrent.normalize(), projectedDesired.normalize());
            offset.set(positions[index]).sub(positions[index - 1]);
            deltaRotation.transform(offset);
            positions[index].set(positions[index - 1]).add(offset);
        }
    }

    private void reject(Vector3f value, Vector3f unitAxis) {
        direction.set(unitAxis).mul(value.dot(unitAxis));
        value.sub(direction);
    }

    private void rotateToward(PoseBuffer pose, int joint) {
        if (current.lengthSquared() <= EPSILON
                || desired.lengthSquared() <= EPSILON) return;
        deltaRotation.rotationTo(current.normalize(), desired.normalize());
        pose.globalRotation(joint, globalRotation);
        deltaRotation.mul(globalRotation, desiredGlobalRotation).normalize();
        int parent = pose.skeleton().joint(joint).parentIndex();
        if (parent < 0) {
            desiredLocalRotation.set(desiredGlobalRotation);
        } else {
            pose.globalRotation(parent, parentGlobalRotation);
            parentGlobalRotation.conjugate()
                    .mul(desiredGlobalRotation, desiredLocalRotation).normalize();
        }
        pose.setRotation(joint, desiredLocalRotation);
    }
}
