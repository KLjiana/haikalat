package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Quaternionf;

import java.util.Objects;

/**
 * Stateful, pure-input two-foot IK composition. Terrain queries remain the caller's
 * responsibility; this class only consumes targets, normals, poles and pelvis offset.
 */
public final class FootIkSolver {
    private static final float EPSILON = 1.0e-6f;

    private final Skeleton skeleton;
    private final int pelvisJoint;
    private final Leg left;
    private final Leg right;
    private final float smoothingRate;
    private float smoothedPelvisOffset;
    private float smoothedLeftWeight;
    private float smoothedRightWeight;

    public FootIkSolver(Skeleton skeleton, int pelvisJoint, Leg left, Leg right,
                        float smoothingRate) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        ConstraintMath.requireJoint(skeleton, pelvisJoint, "pelvisJoint");
        this.pelvisJoint = pelvisJoint;
        this.left = validateLeg(Objects.requireNonNull(left, "left"), "left");
        this.right = validateLeg(Objects.requireNonNull(right, "right"), "right");
        if (!Float.isFinite(smoothingRate) || smoothingRate <= 0.0f) {
            throw new IllegalArgumentException("smoothingRate must be finite and positive");
        }
        this.smoothingRate = smoothingRate;
    }

    public FootIkSolver(Skeleton skeleton, int pelvisJoint, Leg left, Leg right) {
        this(skeleton, pelvisJoint, left, right, 16.0f);
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    /** Applies pelvis, left leg, then right leg in stable order. */
    public Result solve(PoseBuffer pose, Input input, float deltaSeconds) {
        PoseBuffer target = Objects.requireNonNull(pose, "pose");
        if (target.skeleton() != skeleton) {
            throw new IllegalArgumentException("pose belongs to a different skeleton");
        }
        Input values = Objects.requireNonNull(input, "input");
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        float alpha = deltaSeconds == 0.0f
                ? 1.0f : 1.0f - (float) Math.exp(-smoothingRate * deltaSeconds);
        smoothedPelvisOffset += (values.pelvisOffset() - smoothedPelvisOffset) * alpha;
        smoothedLeftWeight += (values.left().weight() - smoothedLeftWeight) * alpha;
        smoothedRightWeight += (values.right().weight() - smoothedRightWeight) * alpha;

        JointTransform pelvis = target.localTransform(pelvisJoint);
        Vector3f translatedPelvis = new Vector3f(pelvis.translation())
                .add(0.0f, smoothedPelvisOffset, 0.0f);
        target.setTranslation(pelvisJoint, translatedPelvis);

        TwoBoneIkSolver.Result leftResult = solveLeg(target, left, values.left(),
                smoothedLeftWeight);
        TwoBoneIkSolver.Result rightResult = solveLeg(target, right, values.right(),
                smoothedRightWeight);
        return new Result(leftResult, rightResult, smoothedPelvisOffset,
                smoothedLeftWeight, smoothedRightWeight);
    }

    private TwoBoneIkSolver.Result solveLeg(PoseBuffer pose, Leg leg,
                                            FootTarget target, float weight) {
        TwoBoneIkSolver.Result result = TwoBoneIkSolver.solve(pose,
                leg.hip(), leg.knee(), leg.ankle(), target.position(), target.pole(), weight);
        if (weight <= 0.0f) return result;
        Quaternionf original = new Quaternionf(
                pose.localTransform(leg.ankle()).rotation());
        Vector3f normal = new Vector3f(target.normal());
        Vector3f currentUp = ConstraintMath.globalRotation(pose, leg.ankle())
                .transform(new Vector3f(leg.localFootUp()));
        ConstraintMath.rotateToward(pose, leg.ankle(), currentUp, normal.normalize());
        Quaternionf oriented = new Quaternionf(
                pose.localTransform(leg.ankle()).rotation());
        pose.setRotation(leg.ankle(),
                original.slerp(oriented, weight).normalize());
        return result;
    }

    private Leg validateLeg(Leg leg, String name) {
        ConstraintMath.requireJoint(skeleton, leg.hip(), name + ".hip");
        ConstraintMath.requireJoint(skeleton, leg.knee(), name + ".knee");
        ConstraintMath.requireJoint(skeleton, leg.ankle(), name + ".ankle");
        if (skeleton.joint(leg.knee()).parentIndex() != leg.hip()
                || skeleton.joint(leg.ankle()).parentIndex() != leg.knee()) {
            throw new IllegalArgumentException(name
                    + " leg must form a hip-knee-ankle chain");
        }
        return leg;
    }

    public record Leg(int hip, int knee, int ankle, Vector3fc localFootUp) {
        public Leg {
            JointTransform.requireFinite(
                    Objects.requireNonNull(localFootUp, "localFootUp"), "localFootUp");
            if (localFootUp.lengthSquared() <= EPSILON * EPSILON) {
                throw new IllegalArgumentException("localFootUp must be non-zero");
            }
            localFootUp = new Vector3f(localFootUp).normalize();
        }

        @Override public Vector3fc localFootUp() {
            return new Vector3f(localFootUp);
        }
    }

    public record FootTarget(Vector3fc position, Vector3fc normal,
                             Vector3fc pole, float weight) {
        public FootTarget {
            JointTransform.requireFinite(
                    Objects.requireNonNull(position, "position"), "position");
            JointTransform.requireFinite(
                    Objects.requireNonNull(normal, "normal"), "normal");
            JointTransform.requireFinite(
                    Objects.requireNonNull(pole, "pole"), "pole");
            if (normal.lengthSquared() <= EPSILON * EPSILON) {
                throw new IllegalArgumentException("normal must be non-zero");
            }
            BoneMask.requireWeight(weight);
            position = new Vector3f(position);
            normal = new Vector3f(normal).normalize();
            pole = new Vector3f(pole);
        }

        @Override public Vector3fc position() { return new Vector3f(position); }
        @Override public Vector3fc normal() { return new Vector3f(normal); }
        @Override public Vector3fc pole() { return new Vector3f(pole); }
    }

    public record Input(FootTarget left, FootTarget right, float pelvisOffset) {
        public Input {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            if (!Float.isFinite(pelvisOffset)) {
                throw new IllegalArgumentException("pelvisOffset must be finite");
            }
        }
    }

    public record Result(TwoBoneIkSolver.Result left, TwoBoneIkSolver.Result right,
                         float pelvisOffset, float leftWeight, float rightWeight) {
        public Result {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            if (!Float.isFinite(pelvisOffset) || !Float.isFinite(leftWeight)
                    || !Float.isFinite(rightWeight)) {
                throw new IllegalArgumentException("foot IK result must be finite");
            }
        }
    }
}
