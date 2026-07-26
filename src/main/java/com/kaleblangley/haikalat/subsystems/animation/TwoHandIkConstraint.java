package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.util.Objects;

/**
 * Secondary-hand weapon constraint. The primary hand/weapon transform stays caller-owned;
 * callers provide the model-space secondary target, pole and optional hand-forward direction.
 */
public final class TwoHandIkConstraint implements AnimationConstraint {
    private static final float EPSILON = 1.0e-6f;

    private final Skeleton skeleton;
    private final int shoulder;
    private final int elbow;
    private final int hand;
    private final Vector3f localHandForward;
    private final boolean orientHand;

    public TwoHandIkConstraint(Skeleton skeleton, int shoulder, int elbow, int hand) {
        this(skeleton, shoulder, elbow, hand, null);
    }

    public TwoHandIkConstraint(Skeleton skeleton, int shoulder, int elbow, int hand,
                               Vector3f localHandForward) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        ConstraintMath.requireJoint(skeleton, shoulder, "shoulder");
        ConstraintMath.requireJoint(skeleton, elbow, "elbow");
        ConstraintMath.requireJoint(skeleton, hand, "hand");
        if (skeleton.joint(elbow).parentIndex() != shoulder
                || skeleton.joint(hand).parentIndex() != elbow) {
            throw new IllegalArgumentException(
                    "secondary arm must form a shoulder-elbow-hand chain");
        }
        this.shoulder = shoulder;
        this.elbow = elbow;
        this.hand = hand;
        if (localHandForward == null) {
            this.localHandForward = new Vector3f(1.0f, 0.0f, 0.0f);
            orientHand = false;
        } else {
            JointTransform.requireFinite(localHandForward, "localHandForward");
            if (localHandForward.lengthSquared() <= EPSILON * EPSILON) {
                throw new IllegalArgumentException("localHandForward must be non-zero");
            }
            this.localHandForward = new Vector3f(localHandForward).normalize();
            orientHand = true;
        }
    }

    @Override
    public Skeleton skeleton() {
        return skeleton;
    }

    @Override
    public Result apply(PoseBuffer pose, Context context) {
        Objects.requireNonNull(context, "context");
        TwoBoneIkSolver.Result solved = TwoBoneIkSolver.solve(
                Objects.requireNonNull(pose, "pose"), shoulder, elbow, hand,
                context.target(), context.pole(), context.weight());
        if (orientHand) {
            Quaternionf original = new Quaternionf(
                    pose.localTransform(hand).rotation());
            Vector3f desired = context.normal();
            if (desired.lengthSquared() <= EPSILON * EPSILON) {
                throw new IllegalArgumentException(
                        "orientation-enabled two-hand constraint requires a non-zero normal");
            }
            Vector3f current = ConstraintMath.globalRotation(pose, hand)
                    .transform(new Vector3f(localHandForward));
            ConstraintMath.rotateToward(pose, hand, current, desired.normalize());
            Quaternionf oriented = new Quaternionf(
                    pose.localTransform(hand).rotation());
            pose.setRotation(hand,
                    original.slerp(oriented, context.weight()).normalize());
        }
        return new Result(1, solved.requestedDistance(), solved.solvedDistance(),
                solved.tipError(), solved.clamped());
    }
}
